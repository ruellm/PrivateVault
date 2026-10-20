// Video index v1 (docs/PHASE3C_DESIGN.md Section 4): size, encode, decode,
// decode_header, and locate; every header and segment rule; both bounds at
// their edges; truncation at every length; the seek rule.
#include "test_support.h"

#include <cstdio>

namespace {

struct Index {
    pv_video_index_header h{};
    std::vector<pv_video_segment> segs;
    std::vector<uint32_t> keys;
};

// A clip of `count` segments of 3 s, three keys each at 0, 1, 2 s, 1 MB a
// segment, 30 fps.
Index make(uint32_t count, uint32_t keys_per_segment = 3) {
    Index x;
    x.h.version = PV_VIDEO_INDEX_VERSION;
    x.h.header_len = PV_VIDEO_INDEX_HEADER_BYTES;
    x.h.video_codec = PV_VIDEO_CODEC_H264;
    x.h.rotation_degrees = 90;
    x.h.width = 1600;
    x.h.height = 1200;
    x.h.frame_rate_millihz = 30000;
    x.h.segment_count = count;
    x.h.created_unix_ms = 1791849600000ull;
    const uint64_t dur = 3000000;
    for (uint32_t i = 0; i < count; ++i) {
        pv_video_segment s{};
        for (int b = 0; b < PV_OBJECT_ID_BYTES; ++b) s.object_id[b] = static_cast<uint8_t>(i + b);
        s.start_us = dur * i;
        s.duration_us = dur;
        s.byte_len = 1000000 + i;
        s.key_count = keys_per_segment;
        x.segs.push_back(s);
        for (uint32_t k = 0; k < keys_per_segment; ++k) {
            x.keys.push_back(static_cast<uint32_t>(k * (dur / keys_per_segment)));
        }
    }
    x.h.total_duration_us = dur * count;
    return x;
}

ts::Bytes encode(const Index& x, int expect = PV_OK) {
    size_t size = 0;
    int rc = pv_video_index_size(&x.h, x.segs.data(), &size);
    if (expect == PV_OK) CHECK_EQ(rc, PV_OK);
    ts::Bytes out(rc == PV_OK ? size : 64, 0);
    size_t len = 0;
    rc = pv_video_index_encode(&x.h, x.segs.data(), x.keys.data(), x.keys.size(), out.data(), out.size(), &len);
    CHECK_EQ(rc, expect);
    if (rc == PV_OK) {
        CHECK_EQ(len, size);
        out.resize(len);
    }
    return out;
}

int decode(const ts::Bytes& b, Index& out) {
    pv_video_index_header h{};
    size_t total = 0;
    int rc = pv_video_index_decode_header(b.data(), b.size(), &h, &total);
    if (rc != PV_OK) return rc;
    out.segs.assign(h.segment_count, pv_video_segment{});
    out.keys.assign(total, 0);
    size_t keys_len = 0;
    rc = pv_video_index_decode(b.data(), b.size(), &out.h, out.segs.data(), out.segs.size(), out.keys.data(),
                               out.keys.size(), &keys_len);
    if (rc == PV_OK) CHECK_EQ(keys_len, total);
    return rc;
}

void round_trips() {
    for (uint32_t count : {1u, 3u, 1000u}) {
        for (uint32_t keys : {1u, 3u, 7u}) {
            Index x = make(count, keys);
            ts::Bytes b = encode(x);
            CHECK_EQ(b.size(), 64u + count * (48u + 4u * keys));
            Index y;
            CHECK_EQ(decode(b, y), PV_OK);
            CHECK_EQ(std::memcmp(&x.h, &y.h, sizeof(x.h)), 0);
            CHECK_EQ(y.segs.size(), x.segs.size());
            for (size_t i = 0; i < x.segs.size(); ++i) {
                CHECK_EQ(std::memcmp(&x.segs[i], &y.segs[i], sizeof(pv_video_segment)), 0);
            }
            CHECK(y.keys == x.keys);
        }
    }
    // Header words land where the layout says.
    Index x = make(1);
    ts::Bytes b = encode(x);
    CHECK_EQ(std::memcmp(b.data(), "PVVINDEX", 8), 0);
    CHECK_EQ(b[8], 1); CHECK_EQ(b[10], 64); CHECK_EQ(b[12], 1); CHECK_EQ(b[14], 90);
    CHECK_EQ(b[16], 0x40); CHECK_EQ(b[17], 0x06);   // 1600
    CHECK_EQ(b[28], 1);
    // A header longer than 64 bytes with extra bytes is accepted on read.
    ts::Bytes longer(b.begin(), b.begin() + 64);
    longer.insert(longer.end(), 8, 0xee);
    longer.insert(longer.end(), b.begin() + 64, b.end());
    longer[10] = 72;
    Index y;
    CHECK_EQ(decode(longer, y), PV_OK);
    CHECK_EQ(y.h.header_len, 72);
    // The writer refuses a header_len other than 64.
    Index z = make(1);
    z.h.header_len = 72;
    encode(z, PV_ERR_INDEX_MALFORMED);
}

void header_rules() {
    // Version, codecs, rotation, dimensions, counts, audio fields, total duration.
    { Index x = make(2); x.h.version = 2; encode(x, PV_ERR_INDEX_UNSUPPORTED); }
    { Index x = make(2); x.h.video_codec = 3; encode(x, PV_ERR_INDEX_UNSUPPORTED); }
    { Index x = make(2); x.h.video_codec = PV_VIDEO_CODEC_H265; encode(x); }
    { Index x = make(2); x.h.audio_codec = 2; encode(x, PV_ERR_INDEX_UNSUPPORTED); }
    { Index x = make(2); x.h.audio_codec = PV_AUDIO_CODEC_AAC; x.h.audio_channels = 2; x.h.audio_sample_rate = 48000; encode(x); }
    { Index x = make(2); x.h.audio_channels = 2; encode(x, PV_ERR_INDEX_MALFORMED); }
    { Index x = make(2); x.h.rotation_degrees = 45; encode(x, PV_ERR_INDEX_MALFORMED); }
    { Index x = make(2); x.h.width = 0; encode(x, PV_ERR_INDEX_MALFORMED); }
    { Index x = make(2); x.h.width = 16385; encode(x, PV_ERR_INDEX_MALFORMED); }
    { Index x = make(2); x.h.height = 16385; encode(x, PV_ERR_INDEX_MALFORMED); }
    { Index x = make(2); x.h.segment_count = 0; encode(x, PV_ERR_INDEX_MALFORMED); }
    { Index x = make(2); x.h.total_duration_us += 1; encode(x, PV_ERR_INDEX_MALFORMED); }
    { Index x = make(2); x.h.frame_rate_millihz = 0; encode(x); }
    // The same rules on read: patch the encoded header.
    Index good = make(2);
    ts::Bytes b = encode(good);
    Index y;
    auto patched = [&](size_t off, uint8_t value) { ts::Bytes c = b; c[off] = value; return c; };
    CHECK_EQ(decode(patched(8, 2), y), PV_ERR_INDEX_UNSUPPORTED);
    CHECK_EQ(decode(patched(12, 3), y), PV_ERR_INDEX_UNSUPPORTED);
    CHECK_EQ(decode(patched(48, 2), y), PV_ERR_INDEX_UNSUPPORTED);
    CHECK_EQ(decode(patched(14, 45), y), PV_ERR_INDEX_MALFORMED);
    ts::Bytes w = b; w[16] = 0; w[17] = 0; CHECK_EQ(decode(w, y), PV_ERR_INDEX_MALFORMED);
    CHECK_EQ(decode(patched(10, 63), y), PV_ERR_INDEX_MALFORMED);
    ts::Bytes hl = b; hl[10] = 0x01; hl[11] = 0x10; CHECK_EQ(decode(hl, y), PV_ERR_INDEX_MALFORMED);   // 4097
    ts::Bytes hl2 = b; hl2[10] = 0x00; hl2[11] = 0x10; CHECK_EQ(decode(hl2, y), PV_ERR_INDEX_MALFORMED);  // 4096, past the buffer
    CHECK_EQ(decode(patched(28, 0), y), PV_ERR_INDEX_MALFORMED);
    CHECK_EQ(decode(patched(32, 1), y), PV_ERR_INDEX_MALFORMED);   // total duration
    CHECK_EQ(decode(patched(50, 2), y), PV_ERR_INDEX_MALFORMED);   // channels without audio
    // Magic absent: a classification.
    ts::Bytes nm = b; nm[0] = 'X';
    CHECK_EQ(decode(nm, y), PV_ERR_NOT_INDEX);
    ts::Bytes tiny(b.begin(), b.begin() + 7);
    CHECK_EQ(decode(tiny, y), PV_ERR_NOT_INDEX);
    // Reserved and flags are ignored on read.
    ts::Bytes r = b; r[56] = 0xff; r[64 + 44] = 0xff;
    CHECK_EQ(decode(r, y), PV_OK);
}

void segment_rules() {
    { Index x = make(3); x.segs[1].start_us = x.segs[0].start_us; encode(x, PV_ERR_INDEX_MALFORMED); }
    { Index x = make(3); x.segs[0].start_us = 10; encode(x, PV_ERR_INDEX_MALFORMED); }
    { Index x = make(3); x.segs[1].duration_us = 0; encode(x, PV_ERR_INDEX_MALFORMED); }
    // Overlap within a frame period (33 333 us at 30 fps) is accepted, beyond it refused.
    { Index x = make(3); x.segs[0].duration_us += 30000; x.segs[0].key_count = 3; encode(x); }
    { Index x = make(3); x.segs[0].duration_us += 40000; encode(x, PV_ERR_INDEX_MALFORMED); }
    { Index x = make(3); x.h.frame_rate_millihz = 0; x.segs[0].duration_us += 1; encode(x, PV_ERR_INDEX_MALFORMED); }
    { Index x = make(3); x.segs[2].byte_len = 0; encode(x, PV_ERR_INDEX_MALFORMED); }
    { Index x = make(3); x.segs[2].byte_len = PV_VIDEO_INDEX_MAX_SEGMENT_BYTES + 1; encode(x, PV_ERR_INDEX_MALFORMED); }
    { Index x = make(3); x.segs[2].byte_len = PV_VIDEO_INDEX_MAX_SEGMENT_BYTES; encode(x); }
    { Index x = make(3); x.keys[3] = 5; encode(x, PV_ERR_INDEX_MALFORMED); }        // first key of segment 1 not 0
    { Index x = make(3); x.keys[4] = x.keys[5]; encode(x, PV_ERR_INDEX_MALFORMED); } // not increasing
    { Index x = make(3); x.keys[5] = 3000000; encode(x, PV_ERR_INDEX_MALFORMED); }   // at the duration
    { Index x = make(3); x.keys[5] = 2999999; encode(x); }
    { Index x = make(3); x.segs[1].key_count = 0; encode(x, PV_ERR_INDEX_MALFORMED); }
    // Key array length must match the records.
    { Index x = make(3); x.keys.pop_back(); size_t len = 0; ts::Bytes out(4096);
      CHECK_EQ(pv_video_index_encode(&x.h, x.segs.data(), x.keys.data(), x.keys.size(), out.data(), out.size(), &len), PV_ERR_INVALID_ARGUMENT); }
    { Index x = make(3); x.keys.push_back(7); size_t len = 0; ts::Bytes out(4096);
      CHECK_EQ(pv_video_index_encode(&x.h, x.segs.data(), x.keys.data(), x.keys.size(), out.data(), out.size(), &len), PV_ERR_INVALID_ARGUMENT); }
    // The same on read, by patching the encoded three-segment index.
    Index good = make(3);
    ts::Bytes b = encode(good);
    Index y;
    const size_t rec1 = 64 + 48 + 12;   // second record
    ts::Bytes c = b; c[rec1 + 16] = 0; c[rec1 + 17] = 0; c[rec1 + 18] = 0; c[rec1 + 19] = 0;   // start of segment 1 = low bytes zeroed
    CHECK_EQ(decode(c, y), PV_ERR_INDEX_MALFORMED);
    c = b; std::memset(c.data() + rec1 + 24, 0, 8);   // duration 0
    CHECK_EQ(decode(c, y), PV_ERR_INDEX_MALFORMED);
    c = b; std::memset(c.data() + rec1 + 32, 0, 8);   // byte_len 0
    CHECK_EQ(decode(c, y), PV_ERR_INDEX_MALFORMED);
    c = b; c[rec1 + 40] = 0; c[rec1 + 41] = 0;         // key_count 0
    CHECK_EQ(decode(c, y), PV_ERR_INDEX_MALFORMED);
    c = b; c[rec1 + 40] = 0x01; c[rec1 + 41] = 0x10;   // key_count 4097
    CHECK_EQ(decode(c, y), PV_ERR_INDEX_MALFORMED);
    c = b; c[rec1 + 48] = 5;                           // first key offset 5
    CHECK_EQ(decode(c, y), PV_ERR_INDEX_MALFORMED);
    // Truncation at every length and one trailing byte. An empty buffer with
    // a real pointer is no index; a null pointer is an argument error.
    {
        pv_video_index_header h0{};
        size_t kt0 = 0;
        CHECK_EQ(pv_video_index_decode_header(b.data(), 0, &h0, &kt0), PV_ERR_NOT_INDEX);
        CHECK_EQ(pv_video_index_decode_header(nullptr, 0, &h0, &kt0), PV_ERR_INVALID_ARGUMENT);
    }
    for (size_t n = 1; n < b.size(); ++n) {
        ts::Bytes cut(b.begin(), b.begin() + static_cast<ptrdiff_t>(n));
        int rc = decode(cut, y);
        if (rc != PV_ERR_INDEX_MALFORMED && rc != PV_ERR_NOT_INDEX) {
            std::fprintf(stderr, "cut at %zu of %zu: %s\n", n, b.size(), pv_error_name(rc));
        }
        CHECK(rc == PV_ERR_INDEX_MALFORMED || rc == PV_ERR_NOT_INDEX);
    }
    ts::Bytes more = b;
    more.push_back(0);
    CHECK_EQ(decode(more, y), PV_ERR_INDEX_MALFORMED);
    // Too small output arrays.
    pv_video_index_header h{};
    pv_video_segment one{};
    uint32_t few[2];
    size_t kl = 0;
    CHECK_EQ(pv_video_index_decode(b.data(), b.size(), &h, &one, 1, few, 2, &kl), PV_ERR_BUFFER_TOO_SMALL);
    std::vector<pv_video_segment> three(3);
    CHECK_EQ(pv_video_index_decode(b.data(), b.size(), &h, three.data(), 3, few, 2, &kl), PV_ERR_BUFFER_TOO_SMALL);
    // Encode into a short buffer writes nothing.
    ts::Bytes small(10, 0xaa);
    size_t len = 0;
    CHECK_EQ(pv_video_index_encode(&good.h, good.segs.data(), good.keys.data(), good.keys.size(), small.data(), small.size(), &len), PV_ERR_BUFFER_TOO_SMALL);
    CHECK(small == ts::Bytes(10, 0xaa));
}

// Both bounds at their edges: keys per segment, segments per index, bytes per index.
void bounds() {
    { Index x = make(1, PV_VIDEO_INDEX_MAX_KEYS); ts::Bytes b = encode(x); Index y; CHECK_EQ(decode(b, y), PV_OK); CHECK_EQ(y.keys.size(), 4096u); }
    { Index x = make(1, PV_VIDEO_INDEX_MAX_KEYS + 1); encode(x, PV_ERR_INDEX_MALFORMED); }
    { Index x = make(PV_VIDEO_INDEX_MAX_SEGMENTS, 1); ts::Bytes b = encode(x); CHECK_EQ(b.size(), 64u + 16384u * 52u); Index y; CHECK_EQ(decode(b, y), PV_OK); CHECK_EQ(y.segs.size(), 16384u); }
    { Index x = make(PV_VIDEO_INDEX_MAX_SEGMENTS + 1, 1); encode(x, PV_ERR_INDEX_MALFORMED); }
    // 16384 segments with 128 keys each are 9 175 104 bytes, above the 8 MiB limit.
    {
        Index x = make(PV_VIDEO_INDEX_MAX_SEGMENTS, 128);
        size_t size = 0;
        CHECK_EQ(pv_video_index_size(&x.h, x.segs.data(), &size), PV_ERR_INDEX_MALFORMED);
        encode(x, PV_ERR_INDEX_MALFORMED);
        ts::Bytes big(PV_VIDEO_INDEX_MAX_BYTES + 1, 0);
        std::memcpy(big.data(), "PVVINDEX", 8);
        Index y;
        CHECK_EQ(decode(big, y), PV_ERR_INDEX_MALFORMED);
    }
    // The 4096 bound is per segment: three segments with 4096 keys each are fine.
    { Index x = make(3, PV_VIDEO_INDEX_MAX_KEYS); ts::Bytes b = encode(x); Index y; CHECK_EQ(decode(b, y), PV_OK); CHECK_EQ(y.keys.size(), 3u * 4096u); }
}

void locate() {
    Index x = make(3);   // segments at 0, 3, 6 s; keys at 0, 1, 2 s in each
    uint32_t seg = 0, key = 0;
    uint64_t t = 0;
    auto at = [&](uint64_t time_us) {
        CHECK_EQ(pv_video_index_locate(&x.h, x.segs.data(), x.keys.data(), time_us, &seg, &key, &t), PV_OK);
    };
    at(0); CHECK_EQ(seg, 0u); CHECK_EQ(key, 0u); CHECK_EQ(t, 0u);
    at(999999); CHECK_EQ(seg, 0u); CHECK_EQ(key, 0u);
    at(1000000); CHECK_EQ(seg, 0u); CHECK_EQ(key, 1u); CHECK_EQ(t, 1000000u);
    at(2999999); CHECK_EQ(seg, 0u); CHECK_EQ(key, 2u); CHECK_EQ(t, 2000000u);
    at(3000000); CHECK_EQ(seg, 1u); CHECK_EQ(key, 0u); CHECK_EQ(t, 3000000u);
    at(4500000); CHECK_EQ(seg, 1u); CHECK_EQ(key, 1u); CHECK_EQ(t, 4000000u);
    at(6000000); CHECK_EQ(seg, 2u); CHECK_EQ(key, 0u); CHECK_EQ(t, 6000000u);
    at(8999999); CHECK_EQ(seg, 2u); CHECK_EQ(key, 2u); CHECK_EQ(t, 8000000u);
    at(9000000); CHECK_EQ(seg, 2u); CHECK_EQ(key, 2u); CHECK_EQ(t, 8000000u);   // at the end
    at(UINT64_MAX); CHECK_EQ(seg, 2u); CHECK_EQ(key, 2u);                        // past the end
    // Uneven keys: a decoded index with 1, 3, 2 keys.
    Index u = make(3, 1);
    u.segs[1].key_count = 3; u.segs[2].key_count = 2;
    u.keys = {0, 0, 1000000, 2500000, 0, 1200000};
    ts::Bytes b = encode(u);
    Index y;
    CHECK_EQ(decode(b, y), PV_OK);
    CHECK_EQ(pv_video_index_locate(&y.h, y.segs.data(), y.keys.data(), 5600000, &seg, &key, &t), PV_OK);
    CHECK_EQ(seg, 1u); CHECK_EQ(key, 2u); CHECK_EQ(t, 5500000u);
    CHECK_EQ(pv_video_index_locate(&y.h, y.segs.data(), y.keys.data(), 7000000, &seg, &key, &t), PV_OK);
    CHECK_EQ(seg, 2u); CHECK_EQ(key, 0u); CHECK_EQ(t, 6000000u);
    CHECK_EQ(pv_video_index_locate(&y.h, y.segs.data(), y.keys.data(), 7300000, &seg, &key, &t), PV_OK);
    CHECK_EQ(seg, 2u); CHECK_EQ(key, 1u); CHECK_EQ(t, 7200000u);
    // Arguments.
    CHECK_EQ(pv_video_index_locate(nullptr, x.segs.data(), x.keys.data(), 0, &seg, &key, &t), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_video_index_locate(&x.h, x.segs.data(), x.keys.data(), 0, nullptr, &key, &t), PV_ERR_INVALID_ARGUMENT);
}

void arguments() {
    Index x = make(1);
    size_t size = 0, len = 0;
    uint8_t out[128];
    pv_video_index_header h{};
    pv_video_segment s{};
    uint32_t k[4];
    CHECK_EQ(pv_video_index_size(nullptr, x.segs.data(), &size), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_video_index_size(&x.h, nullptr, &size), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_video_index_encode(&x.h, x.segs.data(), nullptr, 0, out, sizeof(out), &len), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_video_index_encode(&x.h, x.segs.data(), x.keys.data(), x.keys.size(), nullptr, 0, &len), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_video_index_decode_header(nullptr, 0, &h, &size), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_video_index_decode(out, 0, &h, &s, 1, k, 4, nullptr), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(std::string(pv_error_name(PV_ERR_INDEX_MALFORMED)), "PV_ERR_INDEX_MALFORMED");
    CHECK_EQ(std::string(pv_error_name(PV_ERR_INDEX_UNSUPPORTED)), "PV_ERR_INDEX_UNSUPPORTED");
    CHECK_EQ(std::string(pv_error_name(PV_ERR_NOT_INDEX)), "PV_ERR_NOT_INDEX");
    // The Phase 2 prototype magic is not an index.
    ts::Bytes proto(80, 0);
    std::memcpy(proto.data(), "PVVIDX01", 8);
    Index y;
    CHECK_EQ(decode(proto, y), PV_ERR_NOT_INDEX);
}

}  // namespace

int main() {
    CHECK_EQ(pv_init(), PV_OK);
    round_trips();
    header_rules();
    segment_rules();
    bounds();
    locate();
    arguments();
    std::printf("video index ok\n");
    return 0;
}
