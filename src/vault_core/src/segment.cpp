// Segment writer: one H.264 track as a fragmented MP4 inside a guarded
// buffer. Layout and rules in pv.h and docs/PHASE3E_DESIGN.md Section 4.4.
// The moov goes in first, then a slot for the sidx. Each fragment starts
// with a slot for its moof; the mdat follows and grows as samples arrive.
// When the fragment closes the real moof is written into the slot and the
// mdat moved back to follow it. At finish the sidx is written into its slot
// and the fragments moved back once. No sample is held anywhere else.
#include "common.h"

struct pv_segment_writer {
    uint8_t* buf;
    size_t cap;
    size_t pos;
    bool finished;
    bool fragment_open;
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
constexpr size_t kSidxHeader = 40;
constexpr size_t kPerReference = 12;
constexpr size_t kMoofSlot = kBox + kMfhd + kBox + kTfhd + kTfdt + kTrunHeader + kPerSample * PV_SEGMENT_MAX_SAMPLES_PER_FRAGMENT;
constexpr size_t kSidxSlot = kSidxHeader + kPerReference * PV_SEGMENT_MAX_FRAGMENTS;
constexpr uint32_t kKeyFlags = 0x02000000u;
constexpr uint32_t kNonKeyFlags = 0x01010000u;
constexpr uint32_t kSapFlags = 0x90000000u;

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

std::vector<uint8_t> header_boxes(uint32_t width, uint32_t height, uint16_t rotation,
                                  const uint8_t* sps, size_t sps_len, const uint8_t* pps, size_t pps_len) {
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
    b.u32(2);               // next_track_ID
    b.close(mvhd);

    size_t trak = b.open("trak");
    size_t tkhd = b.open_full("tkhd", 0, 3);
    b.u32(0);
    b.u32(0);
    b.u32(1);               // track_ID
    b.u32(0);               // reserved
    b.u32(0);               // duration
    b.zeros(8);
    b.u16(0);               // layer
    b.u16(0);               // alternate_group
    b.u16(0);               // volume
    b.u16(0);               // reserved
    matrix(b, rotation);
    b.u32(width << 16);
    b.u32(height << 16);
    b.close(tkhd);

    size_t mdia = b.open("mdia");
    size_t mdhd = b.open_full("mdhd", 0, 0);
    b.u32(0);
    b.u32(0);
    b.u32(PV_SEGMENT_TIMESCALE);
    b.u32(0);
    b.u16(0x55c4);          // language: undetermined
    b.u16(0);
    b.close(mdhd);
    size_t hdlr = b.open_full("hdlr", 0, 0);
    b.u32(0);
    b.type("vide");
    b.zeros(12);
    const char name[] = "VideoHandler";
    b.bytes(reinterpret_cast<const uint8_t*>(name), sizeof(name));
    b.close(hdlr);

    size_t minf = b.open("minf");
    size_t vmhd = b.open_full("vmhd", 0, 1);
    b.zeros(8);
    b.close(vmhd);
    size_t dinf = b.open("dinf");
    size_t dref = b.open_full("dref", 0, 0);
    b.u32(1);
    size_t url = b.open_full("url ", 0, 1);
    b.close(url);
    b.close(dref);
    b.close(dinf);

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
    b.close(stbl);
    b.close(minf);
    b.close(mdia);
    b.close(trak);

    size_t mvex = b.open("mvex");
    size_t trex = b.open_full("trex", 0, 0);
    b.u32(1);               // track_ID
    b.u32(1);               // default_sample_description_index
    b.u32(0);
    b.u32(0);
    b.u32(0);
    b.close(trex);
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

// Writes the moof of the open fragment into its slot and moves the mdat up
// behind it. next_start_us ends the last sample.
int close_fragment(pv_segment_writer* w, uint64_t next_start_us) {
    const size_t n = w->samples.size();
    if (n == 0) {
        return PV_ERR_SEGMENT_STATE;
    }
    const uint64_t last_pts = w->samples[n - 1].pts;
    if (next_start_us <= last_pts) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    const size_t trun_size = kTrunHeader + kPerSample * n;
    const size_t traf_size = kBox + kTfhd + kTfdt + trun_size;
    const size_t moof_size = kBox + kMfhd + traf_size;
    uint8_t* m = w->buf + w->moof_pos;
    be32(m, static_cast<uint32_t>(moof_size));
    std::memcpy(m + 4, "moof", 4);
    uint8_t* mfhd = m + kBox;
    be32(mfhd, static_cast<uint32_t>(kMfhd));
    std::memcpy(mfhd + 4, "mfhd", 4);
    be32(mfhd + 8, 0);
    be32(mfhd + 12, static_cast<uint32_t>(w->fragments.size() + 1));
    uint8_t* traf = mfhd + kMfhd;
    be32(traf, static_cast<uint32_t>(traf_size));
    std::memcpy(traf + 4, "traf", 4);
    uint8_t* tfhd = traf + kBox;
    be32(tfhd, static_cast<uint32_t>(kTfhd));
    std::memcpy(tfhd + 4, "tfhd", 4);
    be32(tfhd + 8, 0x020000u);      // default-base-is-moof
    be32(tfhd + 12, 1);             // track_ID
    uint8_t* tfdt = tfhd + kTfhd;
    be32(tfdt, static_cast<uint32_t>(kTfdt));
    std::memcpy(tfdt + 4, "tfdt", 4);
    be32(tfdt + 8, 0x01000000u);    // version 1
    be64(tfdt + 12, w->samples[0].pts);
    uint8_t* trun = tfdt + kTfdt;
    be32(trun, static_cast<uint32_t>(trun_size));
    std::memcpy(trun + 4, "trun", 4);
    be32(trun + 8, 0x000701u);      // data offset, duration, size, flags per sample
    be32(trun + 12, static_cast<uint32_t>(n));
    be32(trun + 16, static_cast<uint32_t>(moof_size + kBox));
    uint8_t* row = trun + kTrunHeader;
    for (size_t i = 0; i < n; ++i) {
        const uint64_t next = i + 1 < n ? w->samples[i + 1].pts : next_start_us;
        be32(row, static_cast<uint32_t>(next - w->samples[i].pts));
        be32(row + 4, w->samples[i].size);
        be32(row + 8, w->samples[i].key ? kKeyFlags : kNonKeyFlags);
        row += kPerSample;
    }
    // The mdat sits at the end of the slot; move it up behind the moof.
    const size_t mdat_len = w->pos - w->mdat_pos;
    uint8_t* mdat_dst = m + moof_size;
    std::memmove(mdat_dst, w->buf + w->mdat_pos, mdat_len);
    be32(mdat_dst, static_cast<uint32_t>(mdat_len));
    std::memcpy(mdat_dst + 4, "mdat", 4);
    const size_t slack = kMoofSlot - moof_size;
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
    if (w->cap - w->pos < kMoofSlot + kBox) {
        return PV_ERR_SEGMENT_FULL;
    }
    w->moof_pos = w->pos;
    w->pos += kMoofSlot;
    w->mdat_pos = w->pos;
    w->pos += kBox;
    w->fragment_open = true;
    return PV_OK;
}

}  // namespace
}  // namespace pv

extern "C" {

int pv_segment_writer_open(uint32_t width, uint32_t height, uint16_t rotation_degrees,
                           const uint8_t* sps, size_t sps_len, const uint8_t* pps, size_t pps_len,
                           size_t cap, pv_segment_writer** out) {
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
    pv::strip_start_code(sps, sps_len);
    pv::strip_start_code(pps, pps_len);
    if (sps_len < 4 || sps_len > 0xffff || pps_len == 0 || pps_len > 0xffff) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    try {
        std::vector<uint8_t> header = pv::header_boxes(width, height, rotation_degrees, sps, sps_len, pps, pps_len);
        if (cap < header.size() + pv::kSidxSlot + pv::kMoofSlot + pv::kBox) {
            return PV_ERR_INVALID_ARGUMENT;
        }
        void* mem = nullptr;
        rc = pv_secure_alloc(cap, &mem);
        if (rc != PV_OK) {
            return rc;
        }
        pv_segment_writer* w = new (std::nothrow) pv_segment_writer();
        if (!w) {
            pv_secure_free(mem, cap);
            return PV_ERR_OUT_OF_MEMORY;
        }
        w->buf = static_cast<uint8_t*>(mem);
        w->cap = cap;
        w->finished = false;
        w->fragment_open = false;
        std::memcpy(w->buf, header.data(), header.size());
        w->sidx_slot = header.size();
        w->fragments_start = w->sidx_slot + pv::kSidxSlot;
        w->pos = w->fragments_start;
        w->moof_pos = 0;
        w->mdat_pos = 0;
        w->last_pts = 0;
        w->sample_total = 0;
        *out = w;
        return PV_OK;
    } catch (const std::bad_alloc&) {
        return PV_ERR_OUT_OF_MEMORY;
    } catch (...) {
        return PV_ERR_INTERNAL;
    }
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
            if (w->cap - w->pos < pv::kMoofSlot + pv::kBox + need) {
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
        if (w->cap - w->pos < need) {
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
    delete w;
}

}
