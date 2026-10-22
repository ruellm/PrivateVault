// Segment writer: one H.264 track, and since Phase 3F an optional AAC track,
// as a fragmented MP4 inside a guarded buffer. Layout and rules in pv.h,
// docs/PHASE3E_DESIGN.md Section 4.4, and docs/PHASE3F_DESIGN.md Section 4.3.
// The moov goes in first, then a slot for the sidx. Each fragment starts
// with a slot for its moof; the mdat follows and grows as video samples
// arrive. Audio samples of the open fragment wait in a guarded stage and
// are copied behind the video samples when the fragment closes: the real
// moof is written into the slot and the mdat moved back to follow it. At
// finish the sidx is written into its slot and the fragments moved back
// once. No sample is held anywhere else.
#include "common.h"

struct pv_segment_writer {
    uint8_t* buf;
    size_t cap;
    size_t pos;
    bool finished;
    bool fragment_open;
    size_t moof_slot;
    size_t sidx_slot;
    size_t fragments_start;
    size_t moof_pos;
    size_t mdat_pos;
    uint64_t last_pts;
    uint32_t sample_total;
    struct Sample {
        uint64_t pts;
        uint32_t size;
        bool key;
    };
    std::vector<Sample> samples;
    struct Fragment {
        uint64_t start_us;
        uint32_t size;
    };
    std::vector<Fragment> fragments;
    // The audio track, when configured at open.
    bool has_audio;
    uint32_t audio_rate;
    uint8_t* stage;
    size_t stage_cap;
    size_t stage_pos;
    std::vector<uint32_t> audio_sizes;   // the open fragment
    uint32_t audio_total;                // the whole segment
    uint64_t audio_first_tick;
    uint64_t audio_last_pts;
    bool audio_started;
};

namespace pv {
namespace {

constexpr size_t kBox = 8;
constexpr size_t kFullBox = 12;
constexpr size_t kMfhd = 16;
constexpr size_t kTfhd = 16;
constexpr size_t kTfdt = 20;
constexpr size_t kTrunHeader = 20;
constexpr size_t kPerSample = 12;
constexpr size_t kPerAudioSample = 8;
constexpr size_t kSidxHeader = 40;
constexpr size_t kPerReference = 12;
constexpr size_t kVideoTrafSlot = kBox + kTfhd + kTfdt + kTrunHeader + kPerSample * PV_SEGMENT_MAX_SAMPLES_PER_FRAGMENT;
constexpr size_t kAudioTrafSlot = kBox + kTfhd + kTfdt + kTrunHeader + kPerAudioSample * PV_SEGMENT_MAX_AUDIO_SAMPLES_PER_FRAGMENT;
constexpr size_t kSidxSlot = kSidxHeader + kPerReference * PV_SEGMENT_MAX_FRAGMENTS;
constexpr uint32_t kKeyFlags = 0x02000000u;
constexpr uint32_t kNonKeyFlags = 0x01010000u;
constexpr uint32_t kSapFlags = 0x90000000u;
constexpr uint32_t kAudioFrame = 1024u;

inline void be16(uint8_t* p, uint16_t v) {
    p[0] = static_cast<uint8_t>(v >> 8);
    p[1] = static_cast<uint8_t>(v);
}

inline void be32(uint8_t* p, uint32_t v) {
    for (int i = 0; i < 4; ++i) {
        p[i] = static_cast<uint8_t>(v >> (24 - 8 * i));
    }
}

inline void be64(uint8_t* p, uint64_t v) {
    for (int i = 0; i < 8; ++i) {
        p[i] = static_cast<uint8_t>(v >> (56 - 8 * i));
    }
}

// Builds the boxes of the header into a vector (no sample data).
class Builder {
public:
    void u8(uint8_t v) { out_.push_back(v); }
    void u16(uint16_t v) {
        uint8_t b[2];
        be16(b, v);
        out_.insert(out_.end(), b, b + 2);
    }
    void u32(uint32_t v) {
        uint8_t b[4];
        be32(b, v);
        out_.insert(out_.end(), b, b + 4);
    }
    void bytes(const uint8_t* p, size_t n) { out_.insert(out_.end(), p, p + n); }
    void zeros(size_t n) { out_.insert(out_.end(), n, 0); }
    void type(const char* t) { out_.insert(out_.end(), t, t + 4); }
    // Opens a box; close patches its size.
    size_t open(const char* t) {
        size_t at = out_.size();
        u32(0);
        type(t);
        return at;
    }
    size_t open_full(const char* t, uint8_t version, uint32_t flags) {
        size_t at = open(t);
        u32((static_cast<uint32_t>(version) << 24) | (flags & 0xffffffu));
        return at;
    }
    void close(size_t at) { be32(out_.data() + at, static_cast<uint32_t>(out_.size() - at)); }
    const std::vector<uint8_t>& data() const { return out_; }

private:
    std::vector<uint8_t> out_;
};

void matrix(Builder& b, uint16_t rotation) {
    const uint32_t one = 0x00010000u;
    const uint32_t neg = 0xffff0000u;
    uint32_t m[9] = {one, 0, 0, 0, one, 0, 0, 0, 0x40000000u};
    if (rotation == 90) {
        m[0] = 0; m[1] = one; m[3] = neg; m[4] = 0;
    } else if (rotation == 180) {
        m[0] = neg; m[4] = neg;
    } else if (rotation == 270) {
        m[0] = 0; m[1] = neg; m[3] = one; m[4] = 0;
    }
    for (uint32_t v : m) {
        b.u32(v);
    }
}

// Strips a 3 or 4 byte start code from a parameter set.
void strip_start_code(const uint8_t*& p, size_t& n) {
    if (n >= 4 && p[0] == 0 && p[1] == 0 && p[2] == 0 && p[3] == 1) {
        p += 4;
        n -= 4;
    } else if (n >= 3 && p[0] == 0 && p[1] == 0 && p[2] == 1) {
        p += 3;
        n -= 3;
    }
}

void tkhd(Builder& b, uint32_t track, uint16_t volume, uint16_t rotation, uint32_t width, uint32_t height) {
    size_t at = b.open_full("tkhd", 0, 3);
    b.u32(0);
    b.u32(0);
    b.u32(track);
    b.u32(0);               // reserved
    b.u32(0);               // duration
    b.zeros(8);
    b.u16(0);               // layer
    b.u16(0);               // alternate_group
    b.u16(volume);
    b.u16(0);               // reserved
    matrix(b, rotation);
    b.u32(width << 16);
    b.u32(height << 16);
    b.close(at);
}

void mdhd(Builder& b, uint32_t timescale) {
    size_t at = b.open_full("mdhd", 0, 0);
    b.u32(0);
    b.u32(0);
    b.u32(timescale);
    b.u32(0);
    b.u16(0x55c4);          // language: undetermined
    b.u16(0);
    b.close(at);
}

void hdlr(Builder& b, const char* type, const char* name) {
    size_t at = b.open_full("hdlr", 0, 0);
    b.u32(0);
    b.type(type);
    b.zeros(12);
    b.bytes(reinterpret_cast<const uint8_t*>(name), std::strlen(name) + 1);
    b.close(at);
}

void dinf(Builder& b) {
    size_t at = b.open("dinf");
    size_t dref = b.open_full("dref", 0, 0);
    b.u32(1);
    size_t url = b.open_full("url ", 0, 1);
    b.close(url);
    b.close(dref);
    b.close(at);
}

void empty_tables(Builder& b) {
    for (const char* t : {"stts", "stsc"}) {
        size_t at = b.open_full(t, 0, 0);
        b.u32(0);
        b.close(at);
    }
    size_t stsz = b.open_full("stsz", 0, 0);
    b.u32(0);
    b.u32(0);
    b.close(stsz);
    size_t stco = b.open_full("stco", 0, 0);
    b.u32(0);
    b.close(stco);
}

void trex(Builder& b, uint32_t track) {
    size_t at = b.open_full("trex", 0, 0);
    b.u32(track);
    b.u32(1);               // default_sample_description_index
    b.u32(0);
    b.u32(0);
    b.u32(0);
    b.close(at);
}

void video_trak(Builder& b, uint32_t width, uint32_t height, uint16_t rotation,
                const uint8_t* sps, size_t sps_len, const uint8_t* pps, size_t pps_len) {
    size_t trak = b.open("trak");
    tkhd(b, 1, 0, rotation, width, height);
    size_t mdia = b.open("mdia");
    mdhd(b, PV_SEGMENT_TIMESCALE);
    hdlr(b, "vide", "VideoHandler");
    size_t minf = b.open("minf");
    size_t vmhd = b.open_full("vmhd", 0, 1);
    b.zeros(8);
    b.close(vmhd);
    dinf(b);
    size_t stbl = b.open("stbl");
    size_t stsd = b.open_full("stsd", 0, 0);
    b.u32(1);
    size_t avc1 = b.open("avc1");
    b.zeros(6);
    b.u16(1);               // data_reference_index
    b.zeros(16);            // pre_defined and reserved
    b.u16(static_cast<uint16_t>(width));
    b.u16(static_cast<uint16_t>(height));
    b.u32(0x00480000u);     // horizresolution
    b.u32(0x00480000u);     // vertresolution
    b.u32(0);
    b.u16(1);               // frame_count
    b.zeros(32);            // compressorname
    b.u16(0x0018);          // depth
    b.u16(0xffff);          // pre_defined
    size_t avcC = b.open("avcC");
    b.u8(1);
    b.u8(sps[1]);
    b.u8(sps[2]);
    b.u8(sps[3]);
    b.u8(0xff);             // 4-byte NAL lengths
    b.u8(0xe1);             // one SPS
    b.u16(static_cast<uint16_t>(sps_len));
    b.bytes(sps, sps_len);
    b.u8(1);                // one PPS
    b.u16(static_cast<uint16_t>(pps_len));
    b.bytes(pps, pps_len);
    b.close(avcC);
    b.close(avc1);
    b.close(stsd);
    empty_tables(b);
    b.close(stbl);
    b.close(minf);
    b.close(mdia);
    b.close(trak);
}

// The ES descriptor: decoder config (AAC, audio stream) with the
// AudioSpecificConfig, then the SL config. One-byte descriptor lengths.
void esds(Builder& b, const pv_segment_audio* a) {
    size_t at = b.open_full("esds", 0, 0);
    const uint8_t dsi_len = static_cast<uint8_t>(a->config_len);
    const uint8_t dcd_len = static_cast<uint8_t>(13 + 2 + dsi_len);
    const uint8_t es_len = static_cast<uint8_t>(3 + 2 + dcd_len + 2 + 1);
    b.u8(0x03);
    b.u8(es_len);
    b.u16(0);               // ES_ID
    b.u8(0);                // flags
    b.u8(0x04);
    b.u8(dcd_len);
    b.u8(0x40);             // Audio ISO/IEC 14496-3
    b.u8(0x15);             // audio stream
    b.u8(0);
    b.u16(0x1800);          // bufferSizeDB
    b.u32(a->max_bitrate);
    b.u32(a->avg_bitrate);
    b.u8(0x05);
    b.u8(dsi_len);
    b.bytes(a->config, a->config_len);
    b.u8(0x06);
    b.u8(1);
    b.u8(0x02);
    b.close(at);
}

void audio_trak(Builder& b, const pv_segment_audio* a) {
    size_t trak = b.open("trak");
    tkhd(b, 2, 0x0100, 0, 0, 0);
    size_t mdia = b.open("mdia");
    mdhd(b, a->sample_rate);
    hdlr(b, "soun", "SoundHandler");
    size_t minf = b.open("minf");
    size_t smhd = b.open_full("smhd", 0, 0);
    b.u16(0);               // balance
    b.u16(0);
    b.close(smhd);
    dinf(b);
    size_t stbl = b.open("stbl");
    size_t stsd = b.open_full("stsd", 0, 0);
    b.u32(1);
    size_t mp4a = b.open("mp4a");
    b.zeros(6);
    b.u16(1);               // data_reference_index
    b.zeros(8);
    b.u16(a->channels);
    b.u16(16);              // samplesize
    b.u16(0);
    b.u16(0);
    b.u32(a->sample_rate << 16);
    esds(b, a);
    b.close(mp4a);
    b.close(stsd);
    empty_tables(b);
    b.close(stbl);
    b.close(minf);
    b.close(mdia);
    b.close(trak);
}

std::vector<uint8_t> header_boxes(uint32_t width, uint32_t height, uint16_t rotation,
                                  const uint8_t* sps, size_t sps_len, const uint8_t* pps, size_t pps_len,
                                  const pv_segment_audio* audio) {
    Builder b;
    size_t ftyp = b.open("ftyp");
    b.type("iso6");
    b.u32(0);
    b.type("isom");
    b.type("iso6");
    b.type("avc1");
    b.type("mp41");
    b.close(ftyp);

    size_t moov = b.open("moov");
    size_t mvhd = b.open_full("mvhd", 0, 0);
    b.u32(0);               // creation time
    b.u32(0);               // modification time
    b.u32(1000);            // timescale
    b.u32(0);               // duration: unknown for a fragmented file
    b.u32(0x00010000u);     // rate
    b.u16(0x0100);          // volume
    b.zeros(10);            // reserved
    matrix(b, 0);
    b.zeros(24);            // pre_defined
    b.u32(audio ? 3 : 2);   // next_track_ID
    b.close(mvhd);
    video_trak(b, width, height, rotation, sps, sps_len, pps, pps_len);
    if (audio) {
        audio_trak(b, audio);
    }
    size_t mvex = b.open("mvex");
    trex(b, 1);
    if (audio) {
        trex(b, 2);
    }
    b.close(mvex);
    b.close(moov);
    return b.data();
}

// Bytes a sample takes with 4-byte length prefixes in place of its start
// codes. A buffer without any start code is one NAL unit.
size_t prefixed_size(const uint8_t* p, size_t n) {
    size_t total = 0;
    bool any = false;
    size_t i = 0;
    size_t nal_start = 0;
    while (i + 3 <= n) {
        if (p[i] == 0 && p[i + 1] == 0) {
            size_t code = 0;
            if (p[i + 2] == 1) {
                code = 3;
            } else if (i + 4 <= n && p[i + 2] == 0 && p[i + 3] == 1) {
                code = 4;
            }
            if (code) {
                if (any) {
                    total += 4 + (i - nal_start);
                }
                any = true;
                i += code;
                nal_start = i;
                continue;
            }
        }
        ++i;
    }
    if (!any) {
        return 4 + n;
    }
    total += 4 + (n - nal_start);
    return total;
}

// Writes the sample with length prefixes at dst; returns the bytes written.
size_t write_prefixed(uint8_t* dst, const uint8_t* p, size_t n) {
    size_t out = 0;
    bool any = false;
    size_t i = 0;
    size_t nal_start = 0;
    auto emit = [&](size_t from, size_t to) {
        be32(dst + out, static_cast<uint32_t>(to - from));
        std::memcpy(dst + out + 4, p + from, to - from);
        out += 4 + (to - from);
    };
    while (i + 3 <= n) {
        if (p[i] == 0 && p[i + 1] == 0) {
            size_t code = 0;
            if (p[i + 2] == 1) {
                code = 3;
            } else if (i + 4 <= n && p[i + 2] == 0 && p[i + 3] == 1) {
                code = 4;
            }
            if (code) {
                if (any) {
                    emit(nal_start, i);
                }
                any = true;
                i += code;
                nal_start = i;
                continue;
            }
        }
        ++i;
    }
    if (!any) {
        emit(0, n);
    } else {
        emit(nal_start, n);
    }
    return out;
}

// Bytes free in the buffer, with the staged audio counted as taken.
size_t free_bytes(const pv_segment_writer* w) {
    return w->cap - w->pos - w->stage_pos;
}

// Writes a traf header: tfhd (default-base-is-moof), tfdt, and the trun
// header; returns the trun row start.
uint8_t* traf_header(uint8_t* traf, size_t traf_size, uint32_t track, uint64_t tfdt_value,
                     uint32_t trun_flags, size_t trun_size, uint32_t count, uint32_t data_offset) {
    be32(traf, static_cast<uint32_t>(traf_size));
    std::memcpy(traf + 4, "traf", 4);
    uint8_t* tfhd = traf + kBox;
    be32(tfhd, static_cast<uint32_t>(kTfhd));
    std::memcpy(tfhd + 4, "tfhd", 4);
    be32(tfhd + 8, 0x020000u);
    be32(tfhd + 12, track);
    uint8_t* tfdt = tfhd + kTfhd;
    be32(tfdt, static_cast<uint32_t>(kTfdt));
    std::memcpy(tfdt + 4, "tfdt", 4);
    be32(tfdt + 8, 0x01000000u);
    be64(tfdt + 12, tfdt_value);
    uint8_t* trun = tfdt + kTfdt;
    be32(trun, static_cast<uint32_t>(trun_size));
    std::memcpy(trun + 4, "trun", 4);
    be32(trun + 8, trun_flags);
    be32(trun + 12, count);
    be32(trun + 16, data_offset);
    return trun + kTrunHeader;
}

// Writes the moof of the open fragment into its slot, appends the staged
// audio behind the video samples, and moves the mdat up behind the moof.
// next_start_us ends the last video sample.
int close_fragment(pv_segment_writer* w, uint64_t next_start_us) {
    const size_t n = w->samples.size();
    if (n == 0) {
        return PV_ERR_SEGMENT_STATE;
    }
    const uint64_t last_pts = w->samples[n - 1].pts;
    if (next_start_us <= last_pts) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    const size_t na = w->audio_sizes.size();
    const size_t video_trun = kTrunHeader + kPerSample * n;
    const size_t video_traf = kBox + kTfhd + kTfdt + video_trun;
    const size_t audio_trun = na ? kTrunHeader + kPerAudioSample * na : 0;
    const size_t audio_traf = na ? kBox + kTfhd + kTfdt + audio_trun : 0;
    const size_t moof_size = kBox + kMfhd + video_traf + audio_traf;
    const size_t video_bytes = w->pos - w->mdat_pos - kBox;
    uint8_t* m = w->buf + w->moof_pos;
    be32(m, static_cast<uint32_t>(moof_size));
    std::memcpy(m + 4, "moof", 4);
    uint8_t* mfhd = m + kBox;
    be32(mfhd, static_cast<uint32_t>(kMfhd));
    std::memcpy(mfhd + 4, "mfhd", 4);
    be32(mfhd + 8, 0);
    be32(mfhd + 12, static_cast<uint32_t>(w->fragments.size() + 1));
    uint8_t* row = traf_header(mfhd + kMfhd, video_traf, 1, w->samples[0].pts, 0x000701u, video_trun,
                               static_cast<uint32_t>(n), static_cast<uint32_t>(moof_size + kBox));
    for (size_t i = 0; i < n; ++i) {
        const uint64_t next = i + 1 < n ? w->samples[i + 1].pts : next_start_us;
        be32(row, static_cast<uint32_t>(next - w->samples[i].pts));
        be32(row + 4, w->samples[i].size);
        be32(row + 8, w->samples[i].key ? kKeyFlags : kNonKeyFlags);
        row += kPerSample;
    }
    if (na) {
        // The audio of this fragment follows the audio before it by count.
        const uint64_t tfdt = w->audio_first_tick + static_cast<uint64_t>(kAudioFrame) * (w->audio_total - na);
        row = traf_header(mfhd + kMfhd + video_traf, audio_traf, 2, tfdt, 0x000301u, audio_trun,
                          static_cast<uint32_t>(na), static_cast<uint32_t>(moof_size + kBox + video_bytes));
        for (size_t i = 0; i < na; ++i) {
            be32(row, kAudioFrame);
            be32(row + 4, w->audio_sizes[i]);
            row += kPerAudioSample;
        }
        std::memcpy(w->buf + w->pos, w->stage, w->stage_pos);
        w->pos += w->stage_pos;
        sodium_memzero(w->stage, w->stage_pos);
        w->stage_pos = 0;
        w->audio_sizes.clear();
    }
    // The mdat sits at the end of the slot; move it up behind the moof.
    const size_t mdat_len = w->pos - w->mdat_pos;
    uint8_t* mdat_dst = m + moof_size;
    std::memmove(mdat_dst, w->buf + w->mdat_pos, mdat_len);
    be32(mdat_dst, static_cast<uint32_t>(mdat_len));
    std::memcpy(mdat_dst + 4, "mdat", 4);
    const size_t slack = w->moof_slot - moof_size;
    sodium_memzero(w->buf + w->pos - slack, slack);
    w->pos -= slack;
    w->fragments.push_back({w->samples[0].pts, static_cast<uint32_t>(moof_size + mdat_len)});
    w->samples.clear();
    w->fragment_open = false;
    return PV_OK;
}

int open_fragment(pv_segment_writer* w) {
    if (w->fragments.size() >= PV_SEGMENT_MAX_FRAGMENTS) {
        return PV_ERR_SEGMENT_FULL;
    }
    if (free_bytes(w) < w->moof_slot + kBox) {
        return PV_ERR_SEGMENT_FULL;
    }
    w->moof_pos = w->pos;
    w->pos += w->moof_slot;
    w->mdat_pos = w->pos;
    w->pos += kBox;
    w->fragment_open = true;
    return PV_OK;
}

bool audio_ok(const pv_segment_audio* a) {
    return a->codec == PV_AUDIO_CODEC_AAC && (a->channels == 1 || a->channels == 2) &&
           a->sample_rate >= 8000 && a->sample_rate <= 96000 && a->config && a->config_len >= 2 &&
           a->config_len <= 64;
}

}  // namespace
}  // namespace pv

extern "C" {

int pv_segment_writer_open_av(uint32_t width, uint32_t height, uint16_t rotation_degrees,
                              const uint8_t* sps, size_t sps_len, const uint8_t* pps, size_t pps_len,
                              const pv_segment_audio* audio, size_t cap, pv_segment_writer** out) {
    int rc = pv::ensure_init();
    if (rc != PV_OK) {
        return rc;
    }
    if (!out) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    *out = nullptr;
    if (!sps || !pps || width == 0 || height == 0 || width > PV_VIDEO_INDEX_MAX_DIMENSION ||
        height > PV_VIDEO_INDEX_MAX_DIMENSION ||
        (rotation_degrees != 0 && rotation_degrees != 90 && rotation_degrees != 180 && rotation_degrees != 270)) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    if (audio && !pv::audio_ok(audio)) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    pv::strip_start_code(sps, sps_len);
    pv::strip_start_code(pps, pps_len);
    if (sps_len < 4 || sps_len > 0xffff || pps_len == 0 || pps_len > 0xffff) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    try {
        std::vector<uint8_t> header = pv::header_boxes(width, height, rotation_degrees, sps, sps_len, pps, pps_len, audio);
        const size_t moof_slot = pv::kBox + pv::kMfhd + pv::kVideoTrafSlot + (audio ? pv::kAudioTrafSlot : 0);
        if (cap < header.size() + pv::kSidxSlot + moof_slot + pv::kBox) {
            return PV_ERR_INVALID_ARGUMENT;
        }
        void* mem = nullptr;
        rc = pv_secure_alloc(cap, &mem);
        if (rc != PV_OK) {
            return rc;
        }
        void* stage = nullptr;
        const size_t stage_cap = audio ? (audio->stage_bytes ? audio->stage_bytes : PV_SEGMENT_AUDIO_STAGE_BYTES) : 0;
        if (audio) {
            rc = pv_secure_alloc(stage_cap, &stage);
            if (rc != PV_OK) {
                pv_secure_free(mem, cap);
                return rc;
            }
        }
        pv_segment_writer* w = new (std::nothrow) pv_segment_writer();
        if (!w) {
            pv_secure_free(mem, cap);
            if (stage) {
                pv_secure_free(stage, stage_cap);
            }
            return PV_ERR_OUT_OF_MEMORY;
        }
        w->buf = static_cast<uint8_t*>(mem);
        w->cap = cap;
        w->finished = false;
        w->fragment_open = false;
        w->moof_slot = moof_slot;
        std::memcpy(w->buf, header.data(), header.size());
        w->sidx_slot = header.size();
        w->fragments_start = w->sidx_slot + pv::kSidxSlot;
        w->pos = w->fragments_start;
        w->moof_pos = 0;
        w->mdat_pos = 0;
        w->last_pts = 0;
        w->sample_total = 0;
        w->has_audio = audio != nullptr;
        w->audio_rate = audio ? audio->sample_rate : 0;
        w->stage = static_cast<uint8_t*>(stage);
        w->stage_cap = stage_cap;
        w->stage_pos = 0;
        w->audio_total = 0;
        w->audio_first_tick = 0;
        w->audio_last_pts = 0;
        w->audio_started = false;
        *out = w;
        return PV_OK;
    } catch (const std::bad_alloc&) {
        return PV_ERR_OUT_OF_MEMORY;
    } catch (...) {
        return PV_ERR_INTERNAL;
    }
}

int pv_segment_writer_open(uint32_t width, uint32_t height, uint16_t rotation_degrees,
                           const uint8_t* sps, size_t sps_len, const uint8_t* pps, size_t pps_len,
                           size_t cap, pv_segment_writer** out) {
    return pv_segment_writer_open_av(width, height, rotation_degrees, sps, sps_len, pps, pps_len, nullptr, cap, out);
}

int pv_segment_writer_add(pv_segment_writer* w, const uint8_t* sample, size_t len,
                          uint64_t pts_us, int key) {
    if (!w || !sample || len == 0) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    if (w->finished) {
        return PV_ERR_SEGMENT_STATE;
    }
    if (!w->fragment_open && !key) {
        return PV_ERR_SEGMENT_STATE;
    }
    if (w->sample_total > 0 && pts_us <= w->last_pts) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    try {
        const size_t need = pv::prefixed_size(sample, len);
        if (need > 0xffffffffu) {
            return PV_ERR_SEGMENT_FULL;
        }
        if (key && w->fragment_open) {
            // The new key frame starts the next fragment; this one ends here.
            if (w->fragments.size() + 1 >= PV_SEGMENT_MAX_FRAGMENTS) {
                return PV_ERR_SEGMENT_FULL;
            }
            if (pv::free_bytes(w) < w->moof_slot + pv::kBox + need) {
                return PV_ERR_SEGMENT_FULL;
            }
            int rc = pv::close_fragment(w, pts_us);
            if (rc != PV_OK) {
                return rc;
            }
        }
        if (!w->fragment_open) {
            int rc = pv::open_fragment(w);
            if (rc != PV_OK) {
                return rc;
            }
        } else if (w->samples.size() >= PV_SEGMENT_MAX_SAMPLES_PER_FRAGMENT) {
            return PV_ERR_SEGMENT_FULL;
        }
        if (pv::free_bytes(w) < need) {
            return PV_ERR_SEGMENT_FULL;
        }
        const size_t written = pv::write_prefixed(w->buf + w->pos, sample, len);
        w->pos += written;
        w->samples.push_back({pts_us, static_cast<uint32_t>(written), key != 0});
        w->last_pts = pts_us;
        w->sample_total++;
        return PV_OK;
    } catch (const std::bad_alloc&) {
        return PV_ERR_OUT_OF_MEMORY;
    } catch (...) {
        return PV_ERR_INTERNAL;
    }
}

int pv_segment_writer_add_audio(pv_segment_writer* w, const uint8_t* sample, size_t len, uint64_t pts_us) {
    if (!w || !sample || len == 0) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    if (w->finished || !w->has_audio || !w->fragment_open) {
        return PV_ERR_SEGMENT_STATE;
    }
    if (w->audio_started && pts_us < w->audio_last_pts) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    if (len > 0xffffffffu || w->audio_sizes.size() >= PV_SEGMENT_MAX_AUDIO_SAMPLES_PER_FRAGMENT ||
        w->stage_cap - w->stage_pos < len || pv::free_bytes(w) < len) {
        return PV_ERR_SEGMENT_FULL;
    }
    try {
        w->audio_sizes.push_back(static_cast<uint32_t>(len));
    } catch (const std::bad_alloc&) {
        return PV_ERR_OUT_OF_MEMORY;
    }
    std::memcpy(w->stage + w->stage_pos, sample, len);
    w->stage_pos += len;
    if (!w->audio_started) {
        // The first frame of the segment sets the track time in samples.
        w->audio_first_tick = (pts_us * w->audio_rate + 500000u) / 1000000u;
        w->audio_started = true;
    }
    w->audio_total++;
    w->audio_last_pts = pts_us;
    return PV_OK;
}

int pv_segment_writer_audio_stats(const pv_segment_writer* w, uint32_t* samples_out, uint64_t* first_tick_out) {
    if (!w) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    if (samples_out) {
        *samples_out = w->audio_total;
    }
    if (first_tick_out) {
        *first_tick_out = w->audio_first_tick;
    }
    return PV_OK;
}

int pv_segment_writer_finish(pv_segment_writer* w, uint64_t end_us) {
    if (!w) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    if (w->finished || !w->fragment_open) {
        return PV_ERR_SEGMENT_STATE;
    }
    try {
        int rc = pv::close_fragment(w, end_us);
        if (rc != PV_OK) {
            return rc;
        }
        const size_t n = w->fragments.size();
        const size_t sidx_size = pv::kSidxHeader + pv::kPerReference * n;
        uint8_t* s = w->buf + w->sidx_slot;
        pv::be32(s, static_cast<uint32_t>(sidx_size));
        std::memcpy(s + 4, "sidx", 4);
        pv::be32(s + 8, 0x01000000u);          // version 1
        pv::be32(s + 12, 1);                    // reference_ID
        pv::be32(s + 16, PV_SEGMENT_TIMESCALE);
        pv::be64(s + 20, w->fragments[0].start_us);
        pv::be64(s + 28, 0);                    // first_offset: the first moof follows
        pv::be16(s + 36, 0);
        pv::be16(s + 38, static_cast<uint16_t>(n));
        uint8_t* ref = s + pv::kSidxHeader;
        for (size_t i = 0; i < n; ++i) {
            const uint64_t next = i + 1 < n ? w->fragments[i + 1].start_us : end_us;
            pv::be32(ref, w->fragments[i].size);
            pv::be32(ref + 4, static_cast<uint32_t>(next - w->fragments[i].start_us));
            pv::be32(ref + 8, pv::kSapFlags);
            ref += pv::kPerReference;
        }
        const size_t slack = pv::kSidxSlot - sidx_size;
        if (slack > 0) {
            std::memmove(s + sidx_size, w->buf + w->fragments_start, w->pos - w->fragments_start);
            sodium_memzero(w->buf + w->pos - slack, slack);
            w->pos -= slack;
            w->fragments_start -= slack;
        }
        w->finished = true;
        return PV_OK;
    } catch (const std::bad_alloc&) {
        return PV_ERR_OUT_OF_MEMORY;
    } catch (...) {
        return PV_ERR_INTERNAL;
    }
}

int pv_segment_writer_data(const pv_segment_writer* w, const uint8_t** data_out, size_t* len_out) {
    if (!w || !data_out || !len_out) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    if (!w->finished) {
        return PV_ERR_SEGMENT_STATE;
    }
    *data_out = w->buf;
    *len_out = w->pos;
    return PV_OK;
}

int pv_segment_writer_stats(const pv_segment_writer* w, size_t* bytes_out, uint32_t* samples_out,
                            uint32_t* fragments_out) {
    if (!w) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    if (bytes_out) {
        *bytes_out = w->pos;
    }
    if (samples_out) {
        *samples_out = w->sample_total;
    }
    if (fragments_out) {
        *fragments_out = static_cast<uint32_t>(w->fragments.size() + (w->fragment_open ? 1 : 0));
    }
    return PV_OK;
}

void pv_segment_writer_close(pv_segment_writer* w) {
    if (!w) {
        return;
    }
    pv_secure_free(w->buf, w->cap);
    if (w->stage) {
        pv_secure_free(w->stage, w->stage_cap);
    }
    delete w;
}

}
