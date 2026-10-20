// Video index codec, format version 1. Layout and rules in pv.h and in
// docs/PHASE3C_DESIGN.md Section 4. Every field is checked on both the write
// and the read side, so a writer cannot produce an index its reader rejects.
#include "common.h"

namespace pv {
namespace {

constexpr uint8_t kIndexMagic[8] = {'P', 'V', 'V', 'I', 'N', 'D', 'E', 'X'};

constexpr size_t kOffVersion = 8;
constexpr size_t kOffHeaderLen = 10;
constexpr size_t kOffVideoCodec = 12;
constexpr size_t kOffRotation = 14;
constexpr size_t kOffWidth = 16;
constexpr size_t kOffHeight = 20;
constexpr size_t kOffFrameRate = 24;
constexpr size_t kOffSegmentCount = 28;
constexpr size_t kOffTotalDuration = 32;
constexpr size_t kOffCreated = 40;
constexpr size_t kOffAudioCodec = 48;
constexpr size_t kOffAudioChannels = 50;
constexpr size_t kOffAudioRate = 52;
constexpr size_t kOffReserved = 56;
static_assert(kOffReserved + 8 == PV_VIDEO_INDEX_HEADER_BYTES, "index header layout");

constexpr size_t kSegId = 0;
constexpr size_t kSegStart = 16;
constexpr size_t kSegDuration = 24;
constexpr size_t kSegByteLen = 32;
constexpr size_t kSegKeyCount = 40;
constexpr size_t kSegFlags = 44;
static_assert(kSegFlags + 4 == PV_VIDEO_INDEX_SEGMENT_BYTES, "index record layout");

bool rotation_ok(uint16_t r) {
    return r == 0 || r == 90 || r == 180 || r == 270;
}

// One frame period in microseconds from a millihertz rate; 0 when unknown.
uint64_t frame_period_us(uint32_t millihz) {
    return millihz == 0 ? 0 : 1000000000ull / millihz;
}

int check_header(const pv_video_index_header& h) {
    if (h.version != PV_VIDEO_INDEX_VERSION) {
        return PV_ERR_INDEX_UNSUPPORTED;
    }
    if (h.header_len < PV_VIDEO_INDEX_HEADER_BYTES || h.header_len > PV_VIDEO_INDEX_HEADER_MAX_BYTES) {
        return PV_ERR_INDEX_MALFORMED;
    }
    if (h.video_codec != PV_VIDEO_CODEC_H264 && h.video_codec != PV_VIDEO_CODEC_H265) {
        return PV_ERR_INDEX_UNSUPPORTED;
    }
    if (h.audio_codec != PV_AUDIO_CODEC_NONE && h.audio_codec != PV_AUDIO_CODEC_AAC) {
        return PV_ERR_INDEX_UNSUPPORTED;
    }
    if (!rotation_ok(h.rotation_degrees) || h.width == 0 || h.width > PV_VIDEO_INDEX_MAX_DIMENSION ||
        h.height == 0 || h.height > PV_VIDEO_INDEX_MAX_DIMENSION || h.segment_count == 0 ||
        h.segment_count > PV_VIDEO_INDEX_MAX_SEGMENTS) {
        return PV_ERR_INDEX_MALFORMED;
    }
    if (h.audio_codec == PV_AUDIO_CODEC_NONE && (h.audio_channels != 0 || h.audio_sample_rate != 0)) {
        return PV_ERR_INDEX_MALFORMED;
    }
    return PV_OK;
}

// Checks one record against the previous one and its keys. prev_end is the
// end of the previous segment, or 0 for the first.
int check_segment(const pv_video_segment& s, size_t index, uint64_t prev_start, uint64_t prev_end,
                  uint64_t period_us, const uint32_t* keys) {
    if (index == 0) {
        if (s.start_us != 0) {
            return PV_ERR_INDEX_MALFORMED;
        }
    } else if (s.start_us <= prev_start || prev_end > s.start_us + period_us || s.start_us + period_us < s.start_us) {
        return PV_ERR_INDEX_MALFORMED;
    }
    if (s.duration_us == 0 || s.start_us + s.duration_us < s.start_us) {
        return PV_ERR_INDEX_MALFORMED;
    }
    if (s.byte_len == 0 || s.byte_len > PV_VIDEO_INDEX_MAX_SEGMENT_BYTES) {
        return PV_ERR_INDEX_MALFORMED;
    }
    if (s.key_count == 0 || s.key_count > PV_VIDEO_INDEX_MAX_KEYS) {
        return PV_ERR_INDEX_MALFORMED;
    }
    if (keys[0] != 0) {
        return PV_ERR_INDEX_MALFORMED;
    }
    for (uint32_t k = 0; k < s.key_count; ++k) {
        if (k > 0 && keys[k] <= keys[k - 1]) {
            return PV_ERR_INDEX_MALFORMED;
        }
        if (keys[k] >= s.duration_us) {
            return PV_ERR_INDEX_MALFORMED;
        }
    }
    return PV_OK;
}

// Size from counts alone, with the bounds the counts must respect.
int size_of(const pv_video_index_header& h, const pv_video_segment* segs, size_t* out) {
    if (h.segment_count == 0 || h.segment_count > PV_VIDEO_INDEX_MAX_SEGMENTS) {
        return PV_ERR_INDEX_MALFORMED;
    }
    uint64_t total = PV_VIDEO_INDEX_HEADER_BYTES;
    for (uint32_t i = 0; i < h.segment_count; ++i) {
        if (segs[i].key_count == 0 || segs[i].key_count > PV_VIDEO_INDEX_MAX_KEYS) {
            return PV_ERR_INDEX_MALFORMED;
        }
        total += PV_VIDEO_INDEX_SEGMENT_BYTES + 4ull * segs[i].key_count;
    }
    if (total > PV_VIDEO_INDEX_MAX_BYTES) {
        return PV_ERR_INDEX_MALFORMED;
    }
    *out = static_cast<size_t>(total);
    return PV_OK;
}

void read_header(const uint8_t* b, pv_video_index_header& h) {
    std::memset(&h, 0, sizeof(h));
    h.version = get_u16(b + kOffVersion);
    h.header_len = get_u16(b + kOffHeaderLen);
    h.video_codec = get_u16(b + kOffVideoCodec);
    h.rotation_degrees = get_u16(b + kOffRotation);
    h.width = get_u32(b + kOffWidth);
    h.height = get_u32(b + kOffHeight);
    h.frame_rate_millihz = get_u32(b + kOffFrameRate);
    h.segment_count = get_u32(b + kOffSegmentCount);
    h.total_duration_us = get_u64(b + kOffTotalDuration);
    h.created_unix_ms = get_u64(b + kOffCreated);
    h.audio_codec = get_u16(b + kOffAudioCodec);
    h.audio_channels = get_u16(b + kOffAudioChannels);
    h.audio_sample_rate = get_u32(b + kOffAudioRate);
}

void read_segment(const uint8_t* b, pv_video_segment& s) {
    std::memcpy(s.object_id, b + kSegId, PV_OBJECT_ID_BYTES);
    s.start_us = get_u64(b + kSegStart);
    s.duration_us = get_u64(b + kSegDuration);
    s.byte_len = get_u64(b + kSegByteLen);
    s.key_count = get_u32(b + kSegKeyCount);
    s.flags = get_u32(b + kSegFlags);
}

// Walks and checks the whole index. When segs_out is set the records and
// keys are copied (the caller has checked the capacities). keys_total gets
// the number of key offsets.
int walk(const uint8_t* buf, size_t len, pv_video_index_header& h, pv_video_segment* segs_out,
         uint32_t* keys_out, size_t* keys_total) {
    if (len > PV_VIDEO_INDEX_MAX_BYTES) {
        return PV_ERR_INDEX_MALFORMED;
    }
    if (len < sizeof(kIndexMagic) || std::memcmp(buf, kIndexMagic, sizeof(kIndexMagic)) != 0) {
        return PV_ERR_NOT_INDEX;
    }
    if (len < PV_VIDEO_INDEX_HEADER_BYTES) {
        return PV_ERR_INDEX_MALFORMED;
    }
    read_header(buf, h);
    int rc = check_header(h);
    if (rc != PV_OK) {
        return rc;
    }
    size_t pos = h.header_len;
    if (pos > len) {
        return PV_ERR_INDEX_MALFORMED;
    }
    const uint64_t period = frame_period_us(h.frame_rate_millihz);
    uint64_t prev_start = 0;
    uint64_t prev_end = 0;
    size_t total_keys = 0;
    for (uint32_t i = 0; i < h.segment_count; ++i) {
        if (len - pos < PV_VIDEO_INDEX_SEGMENT_BYTES) {
            return PV_ERR_INDEX_MALFORMED;
        }
        pv_video_segment s{};
        read_segment(buf + pos, s);
        pos += PV_VIDEO_INDEX_SEGMENT_BYTES;
        if (s.key_count == 0 || s.key_count > PV_VIDEO_INDEX_MAX_KEYS ||
            (len - pos) / 4 < s.key_count) {
            return PV_ERR_INDEX_MALFORMED;
        }
        std::vector<uint32_t> keys(s.key_count);
        for (uint32_t k = 0; k < s.key_count; ++k) {
            keys[k] = get_u32(buf + pos + 4 * k);
        }
        pos += 4 * static_cast<size_t>(s.key_count);
        rc = check_segment(s, i, prev_start, prev_end, period, keys.data());
        if (rc != PV_OK) {
            return rc;
        }
        prev_start = s.start_us;
        prev_end = s.start_us + s.duration_us;
        if (segs_out) {
            segs_out[i] = s;
            std::memcpy(keys_out + total_keys, keys.data(), 4 * keys.size());
        }
        total_keys += s.key_count;
    }
    if (pos != len) {
        return PV_ERR_INDEX_MALFORMED;
    }
    if (h.total_duration_us != prev_end) {
        return PV_ERR_INDEX_MALFORMED;
    }
    *keys_total = total_keys;
    return PV_OK;
}

}  // namespace
}  // namespace pv

extern "C" {

int pv_video_index_size(const pv_video_index_header* h, const pv_video_segment* segs, size_t* out) {
    if (!h || !segs || !out) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    return pv::size_of(*h, segs, out);
}

int pv_video_index_encode(const pv_video_index_header* h, const pv_video_segment* segs,
                          const uint32_t* keys, size_t keys_len, uint8_t* out, size_t cap,
                          size_t* out_len) {
    if (!h || !segs || !keys || !out) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    int rc = pv::check_header(*h);
    if (rc != PV_OK) {
        return rc;
    }
    if (h->header_len != PV_VIDEO_INDEX_HEADER_BYTES) {
        // Writers emit the fixed 64-byte form only.
        return PV_ERR_INDEX_MALFORMED;
    }
    size_t size = 0;
    rc = pv::size_of(*h, segs, &size);
    if (rc != PV_OK) {
        return rc;
    }
    const uint64_t period = pv::frame_period_us(h->frame_rate_millihz);
    uint64_t prev_start = 0;
    uint64_t prev_end = 0;
    size_t key_pos = 0;
    for (uint32_t i = 0; i < h->segment_count; ++i) {
        if (keys_len - key_pos < segs[i].key_count) {
            return PV_ERR_INVALID_ARGUMENT;
        }
        rc = pv::check_segment(segs[i], i, prev_start, prev_end, period, keys + key_pos);
        if (rc != PV_OK) {
            return rc;
        }
        prev_start = segs[i].start_us;
        prev_end = segs[i].start_us + segs[i].duration_us;
        key_pos += segs[i].key_count;
    }
    if (key_pos != keys_len) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    if (h->total_duration_us != prev_end) {
        return PV_ERR_INDEX_MALFORMED;
    }
    if (cap < size) {
        return PV_ERR_BUFFER_TOO_SMALL;
    }
    std::memset(out, 0, PV_VIDEO_INDEX_HEADER_BYTES);
    std::memcpy(out, pv::kIndexMagic, sizeof(pv::kIndexMagic));
    pv::put_u16(out + pv::kOffVersion, h->version);
    pv::put_u16(out + pv::kOffHeaderLen, h->header_len);
    pv::put_u16(out + pv::kOffVideoCodec, h->video_codec);
    pv::put_u16(out + pv::kOffRotation, h->rotation_degrees);
    pv::put_u32(out + pv::kOffWidth, h->width);
    pv::put_u32(out + pv::kOffHeight, h->height);
    pv::put_u32(out + pv::kOffFrameRate, h->frame_rate_millihz);
    pv::put_u32(out + pv::kOffSegmentCount, h->segment_count);
    pv::put_u64(out + pv::kOffTotalDuration, h->total_duration_us);
    pv::put_u64(out + pv::kOffCreated, h->created_unix_ms);
    pv::put_u16(out + pv::kOffAudioCodec, h->audio_codec);
    pv::put_u16(out + pv::kOffAudioChannels, h->audio_channels);
    pv::put_u32(out + pv::kOffAudioRate, h->audio_sample_rate);
    size_t pos = PV_VIDEO_INDEX_HEADER_BYTES;
    key_pos = 0;
    for (uint32_t i = 0; i < h->segment_count; ++i) {
        uint8_t* r = out + pos;
        std::memcpy(r + pv::kSegId, segs[i].object_id, PV_OBJECT_ID_BYTES);
        pv::put_u64(r + pv::kSegStart, segs[i].start_us);
        pv::put_u64(r + pv::kSegDuration, segs[i].duration_us);
        pv::put_u64(r + pv::kSegByteLen, segs[i].byte_len);
        pv::put_u32(r + pv::kSegKeyCount, segs[i].key_count);
        pv::put_u32(r + pv::kSegFlags, segs[i].flags);
        pos += PV_VIDEO_INDEX_SEGMENT_BYTES;
        for (uint32_t k = 0; k < segs[i].key_count; ++k) {
            pv::put_u32(out + pos, keys[key_pos + k]);
            pos += 4;
        }
        key_pos += segs[i].key_count;
    }
    if (out_len) {
        *out_len = pos;
    }
    return PV_OK;
}

int pv_video_index_decode_header(const uint8_t* buf, size_t len, pv_video_index_header* out,
                                 size_t* keys_total) {
    if (!buf || !out || !keys_total) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    pv_video_index_header h{};
    std::memset(out, 0, sizeof(*out));
    try {
        int rc = pv::walk(buf, len, h, nullptr, nullptr, keys_total);
        if (rc != PV_OK) {
            return rc;
        }
    } catch (const std::bad_alloc&) {
        return PV_ERR_OUT_OF_MEMORY;
    }
    *out = h;
    return PV_OK;
}

int pv_video_index_decode(const uint8_t* buf, size_t len, pv_video_index_header* h_out,
                          pv_video_segment* segs_out, size_t segs_cap, uint32_t* keys_out,
                          size_t keys_cap, size_t* keys_len_out) {
    if (!buf || !h_out || !segs_out || !keys_out || !keys_len_out) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    pv_video_index_header h{};
    size_t total_keys = 0;
    std::memset(h_out, 0, sizeof(*h_out));
    try {
        // First pass checks everything and gives the counts; the second copies.
        int rc = pv::walk(buf, len, h, nullptr, nullptr, &total_keys);
        if (rc != PV_OK) {
            return rc;
        }
        if (segs_cap < h.segment_count || keys_cap < total_keys) {
            return PV_ERR_BUFFER_TOO_SMALL;
        }
        rc = pv::walk(buf, len, h, segs_out, keys_out, &total_keys);
        if (rc != PV_OK) {
            return rc;
        }
    } catch (const std::bad_alloc&) {
        return PV_ERR_OUT_OF_MEMORY;
    }
    *h_out = h;
    *keys_len_out = total_keys;
    return PV_OK;
}

int pv_video_index_locate(const pv_video_index_header* h, const pv_video_segment* segs,
                          const uint32_t* keys, uint64_t time_us, uint32_t* segment_out,
                          uint32_t* key_out, uint64_t* key_time_us_out) {
    if (!h || !segs || !keys || !segment_out || !key_out || !key_time_us_out ||
        h->segment_count == 0 || h->segment_count > PV_VIDEO_INDEX_MAX_SEGMENTS) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    uint32_t seg = 0;
    size_t key_base = 0;
    for (uint32_t i = 1; i < h->segment_count; ++i) {
        if (segs[i].start_us > time_us) {
            break;
        }
        key_base += segs[i - 1].key_count;
        seg = i;
    }
    const pv_video_segment& s = segs[seg];
    if (s.key_count == 0) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    uint32_t key = 0;
    for (uint32_t k = 1; k < s.key_count; ++k) {
        if (s.start_us + keys[key_base + k] > time_us) {
            break;
        }
        key = k;
    }
    *segment_out = seg;
    *key_out = key;
    *key_time_us_out = s.start_us + keys[key_base + key];
    return PV_OK;
}

}
