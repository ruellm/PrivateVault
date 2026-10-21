// Segment writer (docs/PHASE3E_DESIGN.md Section 4.4): the boxes of a
// segment parsed back field by field, the rotation matrices, the parameter
// sets with and without start codes, the length prefixes, every state and
// bound rule, the round trip through a vault object, and the committed golden
// vector compared byte for byte.
#include "test_support.h"

#include <cstdio>

namespace {

struct Box {
    std::string type;
    size_t start;
    size_t body;
    size_t end;
};

uint32_t rd32(const ts::Bytes& d, size_t at) {
    CHECK(at + 4 <= d.size());
    return (static_cast<uint32_t>(d[at]) << 24) | (static_cast<uint32_t>(d[at + 1]) << 16) |
           (static_cast<uint32_t>(d[at + 2]) << 8) | d[at + 3];
}

uint64_t rd64(const ts::Bytes& d, size_t at) {
    return (static_cast<uint64_t>(rd32(d, at)) << 32) | rd32(d, at + 4);
}

uint16_t rd16(const ts::Bytes& d, size_t at) {
    CHECK(at + 2 <= d.size());
    return static_cast<uint16_t>((d[at] << 8) | d[at + 1]);
}

std::vector<Box> children(const ts::Bytes& d, size_t start, size_t end) {
    std::vector<Box> out;
    size_t pos = start;
    while (pos + 8 <= end) {
        uint32_t size = rd32(d, pos);
        std::string type(reinterpret_cast<const char*>(d.data() + pos + 4), 4);
        CHECK(size >= 8);
        CHECK(pos + size <= end);
        out.push_back({type, pos, pos + 8, pos + size});
        pos += size;
    }
    CHECK_EQ(pos, end);
    return out;
}

Box find(const ts::Bytes& d, size_t start, size_t end, const char* type) {
    for (const Box& b : children(d, start, end)) {
        if (b.type == type) return b;
    }
    std::fprintf(stderr, "box %s not found\n", type);
    std::exit(1);
}

Box path(const ts::Bytes& d, std::initializer_list<const char*> types) {
    Box cur{"", 0, 0, d.size()};
    for (const char* t : types) cur = find(d, cur.body, cur.end, t);
    return cur;
}

// Bytes with no zero byte, so no accidental start code.
ts::Bytes nozero(size_t n, size_t skip) {
    ts::Bytes b = ts::det_bytes(n + skip);
    b.erase(b.begin(), b.begin() + static_cast<ptrdiff_t>(skip));
    for (uint8_t& x : b) if (x == 0) x = 0x7f;
    return b;
}

ts::Bytes start_code(int n) {
    return n == 4 ? ts::Bytes{0, 0, 0, 1} : ts::Bytes{0, 0, 1};
}

ts::Bytes cat(const ts::Bytes& a, const ts::Bytes& b) {
    ts::Bytes out = a;
    out.insert(out.end(), b.begin(), b.end());
    return out;
}

ts::Bytes prefixed(const ts::Bytes& nal) {
    ts::Bytes out = {static_cast<uint8_t>(nal.size() >> 24), static_cast<uint8_t>(nal.size() >> 16),
                     static_cast<uint8_t>(nal.size() >> 8), static_cast<uint8_t>(nal.size())};
    out.insert(out.end(), nal.begin(), nal.end());
    return out;
}

// The golden inputs, shared with make_vectors --segment.
const uint8_t kSps[] = {0x67, 0x64, 0x00, 0x28, 0xac, 0xd9, 0x40, 0x78, 0x02, 0x27, 0xe5, 0xc0, 0x44,
                        0x00, 0x00, 0x03, 0x00, 0x04, 0x00, 0x00, 0x03, 0x00, 0xf0, 0x3c, 0x60, 0xc6, 0x58};
const uint8_t kPps[] = {0x68, 0xeb, 0xe3, 0xcb, 0x22, 0xc0};

struct Golden {
    std::vector<ts::Bytes> samples;   // Annex B as given to the writer
    std::vector<ts::Bytes> nals;      // the expected mdat payload per sample
    std::vector<uint64_t> pts;
    uint64_t end_us;
};

Golden golden_inputs() {
    Golden g;
    ts::Bytes idr = cat({0x65}, nozero(299, 0));
    ts::Bytes sei = cat({0x06}, nozero(19, 300));
    ts::Bytes p1 = cat({0x41}, nozero(119, 400));
    ts::Bytes p2 = cat({0x41}, nozero(79, 600));
    g.samples = {cat(cat(start_code(4), idr), cat(start_code(3), sei)), cat(start_code(4), p1), cat(start_code(3), p2)};
    g.nals = {cat(prefixed(idr), prefixed(sei)), prefixed(p1), prefixed(p2)};
    g.pts = {0, 33333, 66667};
    g.end_us = 100000;
    return g;
}

ts::Bytes data_of(pv_segment_writer* w) {
    const uint8_t* p = nullptr;
    size_t n = 0;
    CHECK_EQ(pv_segment_writer_data(w, &p, &n), PV_OK);
    return ts::Bytes(p, p + n);
}

pv_segment_writer* open(uint16_t rotation = 90, size_t cap = 1 << 20, uint32_t w = 1920, uint32_t h = 1080) {
    pv_segment_writer* out = nullptr;
    CHECK_EQ(pv_segment_writer_open(w, h, rotation, kSps, sizeof(kSps), kPps, sizeof(kPps), cap, &out), PV_OK);
    CHECK(out != nullptr);
    return out;
}

void check_matrix(const ts::Bytes& d, size_t at, uint16_t rotation) {
    const uint32_t one = 0x00010000u, neg = 0xffff0000u;
    uint32_t a = rd32(d, at), b = rd32(d, at + 4), c = rd32(d, at + 12), e = rd32(d, at + 16);
    CHECK_EQ(rd32(d, at + 32), 0x40000000u);
    switch (rotation) {
        case 0: CHECK(a == one && b == 0 && c == 0 && e == one); break;
        case 90: CHECK(a == 0 && b == one && c == neg && e == 0); break;
        case 180: CHECK(a == neg && b == 0 && c == 0 && e == neg); break;
        case 270: CHECK(a == 0 && b == neg && c == one && e == 0); break;
    }
}

// Checks the header boxes of a segment at the golden size and rotation.
void check_header(const ts::Bytes& d, uint16_t rotation) {
    std::vector<Box> top = children(d, 0, d.size());
    CHECK(top.size() >= 4);
    CHECK_EQ(top[0].type, "ftyp");
    CHECK_EQ(std::string(reinterpret_cast<const char*>(d.data() + top[0].body), 4), "iso6");
    CHECK_EQ(top[1].type, "moov");
    CHECK_EQ(top[2].type, "sidx");
    CHECK_EQ(top[3].type, "moof");
    Box tkhd = path(d, {"moov", "trak", "tkhd"});
    CHECK_EQ(rd32(d, tkhd.body), 3u);                  // version 0, flags 3
    CHECK_EQ(rd32(d, tkhd.body + 12), 1u);             // track_ID
    check_matrix(d, tkhd.body + 4 + 20 + 16, rotation);
    CHECK_EQ(rd32(d, tkhd.body + 4 + 20 + 16 + 36), 1920u << 16);
    CHECK_EQ(rd32(d, tkhd.body + 4 + 20 + 16 + 40), 1080u << 16);
    Box mdhd = path(d, {"moov", "trak", "mdia", "mdhd"});
    CHECK_EQ(rd32(d, mdhd.body + 12), PV_SEGMENT_TIMESCALE);
    Box hdlr = path(d, {"moov", "trak", "mdia", "hdlr"});
    CHECK_EQ(std::string(reinterpret_cast<const char*>(d.data() + hdlr.body + 8), 4), "vide");
    Box stsd = path(d, {"moov", "trak", "mdia", "minf", "stbl", "stsd"});
    CHECK_EQ(rd32(d, stsd.body + 4), 1u);
    Box avc1 = find(d, stsd.body + 8, stsd.end, "avc1");
    CHECK_EQ(rd16(d, avc1.body + 24), 1920);
    CHECK_EQ(rd16(d, avc1.body + 26), 1080);
    Box avcC = find(d, avc1.body + 78, avc1.end, "avcC");
    CHECK_EQ(d[avcC.body], 1);
    CHECK_EQ(d[avcC.body + 1], kSps[1]);
    CHECK_EQ(d[avcC.body + 2], kSps[2]);
    CHECK_EQ(d[avcC.body + 3], kSps[3]);
    CHECK_EQ(d[avcC.body + 4], 0xff);
    CHECK_EQ(d[avcC.body + 5], 0xe1);
    CHECK_EQ(rd16(d, avcC.body + 6), sizeof(kSps));
    CHECK(std::memcmp(d.data() + avcC.body + 8, kSps, sizeof(kSps)) == 0);
    size_t p = avcC.body + 8 + sizeof(kSps);
    CHECK_EQ(d[p], 1);
    CHECK_EQ(rd16(d, p + 1), sizeof(kPps));
    CHECK(std::memcmp(d.data() + p + 3, kPps, sizeof(kPps)) == 0);
    CHECK_EQ(p + 3 + sizeof(kPps), avcC.end);
    for (const char* t : {"stts", "stsc", "stsz", "stco"}) path(d, {"moov", "trak", "mdia", "minf", "stbl", t});
    Box trex = path(d, {"moov", "mvex", "trex"});
    CHECK_EQ(rd32(d, trex.body + 4), 1u);
    CHECK_EQ(rd32(d, trex.body + 8), 1u);
}

struct Frag {
    Box moof;
    Box mdat;
    uint64_t tfdt;
    uint32_t count;
    std::vector<uint32_t> durations, sizes, flags;
    size_t data_start;
};

std::vector<Frag> fragments(const ts::Bytes& d) {
    std::vector<Frag> out;
    std::vector<Box> top = children(d, 0, d.size());
    for (size_t i = 0; i < top.size(); ++i) {
        if (top[i].type != "moof") continue;
        CHECK(i + 1 < top.size());
        CHECK_EQ(top[i + 1].type, "mdat");
        Frag f{top[i], top[i + 1], 0, 0, {}, {}, {}, 0};
        Box mfhd = find(d, f.moof.body, f.moof.end, "mfhd");
        CHECK_EQ(rd32(d, mfhd.body + 4), static_cast<uint32_t>(out.size() + 1));
        Box traf = find(d, f.moof.body, f.moof.end, "traf");
        std::vector<Box> kids = children(d, traf.body, traf.end);
        CHECK_EQ(kids.size(), 3u);
        CHECK_EQ(kids[0].type, "tfhd");
        CHECK_EQ(rd32(d, kids[0].body), 0x020000u);
        CHECK_EQ(rd32(d, kids[0].body + 4), 1u);
        CHECK_EQ(kids[1].type, "tfdt");
        CHECK_EQ(rd32(d, kids[1].body), 0x01000000u);
        f.tfdt = rd64(d, kids[1].body + 4);
        CHECK_EQ(kids[2].type, "trun");
        CHECK_EQ(rd32(d, kids[2].body), 0x000701u);
        f.count = rd32(d, kids[2].body + 4);
        uint32_t data_offset = rd32(d, kids[2].body + 8);
        CHECK_EQ(f.moof.start + data_offset, f.mdat.body);
        f.data_start = f.mdat.body;
        size_t row = kids[2].body + 12;
        for (uint32_t s = 0; s < f.count; ++s) {
            f.durations.push_back(rd32(d, row));
            f.sizes.push_back(rd32(d, row + 4));
            f.flags.push_back(rd32(d, row + 8));
            row += 12;
        }
        CHECK_EQ(row, kids[2].end);
        size_t total = 0;
        for (uint32_t s : f.sizes) total += s;
        CHECK_EQ(f.mdat.body + total, f.mdat.end);
        out.push_back(f);
    }
    return out;
}

void check_sidx(const ts::Bytes& d, const std::vector<Frag>& frags, uint64_t end_us) {
    Box sidx = find(d, 0, d.size(), "sidx");
    CHECK_EQ(rd32(d, sidx.body), 0x01000000u);
    CHECK_EQ(rd32(d, sidx.body + 4), 1u);
    CHECK_EQ(rd32(d, sidx.body + 8), PV_SEGMENT_TIMESCALE);
    CHECK_EQ(rd64(d, sidx.body + 12), frags[0].tfdt);
    CHECK_EQ(rd64(d, sidx.body + 20), 0u);
    CHECK_EQ(rd16(d, sidx.body + 28), 0);
    CHECK_EQ(rd16(d, sidx.body + 30), frags.size());
    // The first moof follows the sidx at once.
    CHECK_EQ(sidx.end, frags[0].moof.start);
    size_t ref = sidx.body + 32;
    for (size_t i = 0; i < frags.size(); ++i) {
        CHECK_EQ(rd32(d, ref), static_cast<uint32_t>(frags[i].mdat.end - frags[i].moof.start));
        uint64_t next = i + 1 < frags.size() ? frags[i + 1].tfdt : end_us;
        CHECK_EQ(rd32(d, ref + 4), static_cast<uint32_t>(next - frags[i].tfdt));
        CHECK_EQ(rd32(d, ref + 8), 0x90000000u);
        ref += 12;
    }
    CHECK_EQ(ref, sidx.end);
    if (frags.size() > 1) CHECK_EQ(frags[0].mdat.end, frags[1].moof.start);
}

void three_fragments() {
    Golden g = golden_inputs();
    pv_segment_writer* w = open();
    size_t bytes = 0;
    uint32_t samples = 0, frags = 0;
    CHECK_EQ(pv_segment_writer_stats(w, &bytes, &samples, &frags), PV_OK);
    CHECK_EQ(samples, 0u);
    CHECK_EQ(frags, 0u);
    // Fragment 1: the three golden samples; 2: a key frame and one more; 3: one key frame.
    for (size_t i = 0; i < 3; ++i) CHECK_EQ(pv_segment_writer_add(w, g.samples[i].data(), g.samples[i].size(), g.pts[i], i == 0), PV_OK);
    CHECK_EQ(pv_segment_writer_add(w, g.samples[0].data(), g.samples[0].size(), 1000000, 1), PV_OK);
    CHECK_EQ(pv_segment_writer_add(w, g.samples[2].data(), g.samples[2].size(), 1040000, 0), PV_OK);
    CHECK_EQ(pv_segment_writer_add(w, g.samples[1].data(), g.samples[1].size(), 2000000, 1), PV_OK);
    CHECK_EQ(pv_segment_writer_stats(w, &bytes, &samples, &frags), PV_OK);
    CHECK_EQ(samples, 6u);
    CHECK_EQ(frags, 3u);
    const uint8_t* p = nullptr;
    size_t n = 0;
    CHECK_EQ(pv_segment_writer_data(w, &p, &n), PV_ERR_SEGMENT_STATE);
    CHECK_EQ(pv_segment_writer_finish(w, 2500000), PV_OK);
    CHECK_EQ(pv_segment_writer_finish(w, 2500000), PV_ERR_SEGMENT_STATE);
    CHECK_EQ(pv_segment_writer_add(w, g.samples[0].data(), g.samples[0].size(), 3000000, 1), PV_ERR_SEGMENT_STATE);
    ts::Bytes d = data_of(w);
    CHECK_EQ(pv_segment_writer_stats(w, &bytes, &samples, &frags), PV_OK);
    CHECK_EQ(bytes, d.size());
    check_header(d, 90);
    std::vector<Frag> f = fragments(d);
    CHECK_EQ(f.size(), 3u);
    CHECK_EQ(f[0].tfdt, 0u);
    CHECK_EQ(f[0].count, 3u);
    CHECK(f[0].durations == std::vector<uint32_t>({33333, 33334, 933333}));
    CHECK(f[0].flags == std::vector<uint32_t>({0x02000000u, 0x01010000u, 0x01010000u}));
    size_t at = f[0].data_start;
    for (size_t i = 0; i < 3; ++i) {
        CHECK_EQ(f[0].sizes[i], g.nals[i].size());
        CHECK(ts::Bytes(d.begin() + static_cast<ptrdiff_t>(at), d.begin() + static_cast<ptrdiff_t>(at + g.nals[i].size())) == g.nals[i]);
        at += g.nals[i].size();
    }
    CHECK_EQ(f[1].tfdt, 1000000u);
    CHECK_EQ(f[1].count, 2u);
    CHECK(f[1].durations == std::vector<uint32_t>({40000, 960000}));
    CHECK_EQ(f[2].tfdt, 2000000u);
    CHECK_EQ(f[2].count, 1u);
    CHECK(f[2].durations == std::vector<uint32_t>({500000}));
    CHECK_EQ(f[2].flags[0], 0x02000000u);
    CHECK_EQ(f[2].sizes[0], g.nals[1].size());
    check_sidx(d, f, 2500000);
    CHECK_EQ(f[2].mdat.end, d.size());
    pv_segment_writer_close(w);
}

void rotations_and_parameter_sets() {
    const uint16_t rotations[] = {0, 90, 180, 270};
    for (uint16_t r : rotations) {
        pv_segment_writer* w = open(r);
        Golden g = golden_inputs();
        CHECK_EQ(pv_segment_writer_add(w, g.samples[0].data(), g.samples[0].size(), 0, 1), PV_OK);
        CHECK_EQ(pv_segment_writer_finish(w, 40000), PV_OK);
        ts::Bytes d = data_of(w);
        check_header(d, r);
        pv_segment_writer_close(w);
    }
    // Parameter sets with start codes give the same bytes as raw ones.
    ts::Bytes sps4 = cat(start_code(4), ts::Bytes(kSps, kSps + sizeof(kSps)));
    ts::Bytes pps3 = cat(start_code(3), ts::Bytes(kPps, kPps + sizeof(kPps)));
    pv_segment_writer* a = nullptr;
    CHECK_EQ(pv_segment_writer_open(1920, 1080, 90, sps4.data(), sps4.size(), pps3.data(), pps3.size(), 1 << 20, &a), PV_OK);
    pv_segment_writer* b = open();
    Golden g = golden_inputs();
    for (pv_segment_writer* w : {a, b}) {
        CHECK_EQ(pv_segment_writer_add(w, g.samples[0].data(), g.samples[0].size(), 0, 1), PV_OK);
        CHECK_EQ(pv_segment_writer_finish(w, 40000), PV_OK);
    }
    CHECK(data_of(a) == data_of(b));
    pv_segment_writer_close(a);
    pv_segment_writer_close(b);
    // A sample without any start code is stored as one NAL unit.
    pv_segment_writer* w = open();
    ts::Bytes raw = cat({0x65}, nozero(50, 0));
    CHECK_EQ(pv_segment_writer_add(w, raw.data(), raw.size(), 0, 1), PV_OK);
    CHECK_EQ(pv_segment_writer_finish(w, 40000), PV_OK);
    ts::Bytes d = data_of(w);
    std::vector<Frag> f = fragments(d);
    CHECK_EQ(f[0].sizes[0], raw.size() + 4);
    CHECK(ts::Bytes(d.begin() + static_cast<ptrdiff_t>(f[0].data_start), d.end()) == prefixed(raw));
    pv_segment_writer_close(w);
}

void rules_and_bounds() {
    Golden g = golden_inputs();
    const ts::Bytes& key = g.samples[0];
    const ts::Bytes& non = g.samples[1];
    // The first sample must be a key frame; finish needs a sample.
    pv_segment_writer* w = open();
    CHECK_EQ(pv_segment_writer_finish(w, 1000), PV_ERR_SEGMENT_STATE);
    CHECK_EQ(pv_segment_writer_add(w, non.data(), non.size(), 0, 0), PV_ERR_SEGMENT_STATE);
    CHECK_EQ(pv_segment_writer_add(w, key.data(), key.size(), 0, 1), PV_OK);
    // Times increase; the end lies past the last sample.
    CHECK_EQ(pv_segment_writer_add(w, non.data(), non.size(), 0, 0), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_segment_writer_add(w, non.data(), non.size(), 33333, 0), PV_OK);
    CHECK_EQ(pv_segment_writer_add(w, non.data(), non.size(), 33333, 0), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_segment_writer_finish(w, 33333), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_segment_writer_finish(w, 33334), PV_OK);
    CHECK_EQ(fragments(data_of(w))[0].durations[1], 1u);
    pv_segment_writer_close(w);
    // Close without finish.
    w = open();
    CHECK_EQ(pv_segment_writer_add(w, key.data(), key.size(), 0, 1), PV_OK);
    pv_segment_writer_close(w);
    pv_segment_writer_close(nullptr);

    // A sample larger than the room left: FULL, the segment still finishes
    // with the samples before it.
    w = open(90, 32768);
    CHECK_EQ(pv_segment_writer_add(w, key.data(), key.size(), 0, 1), PV_OK);
    ts::Bytes big = cat(start_code(4), cat({0x41}, nozero(40000, 0)));
    CHECK_EQ(pv_segment_writer_add(w, big.data(), big.size(), 33333, 0), PV_ERR_SEGMENT_FULL);
    CHECK_EQ(pv_segment_writer_add(w, non.data(), non.size(), 33333, 0), PV_OK);
    CHECK_EQ(pv_segment_writer_finish(w, 66667), PV_OK);
    std::vector<Frag> f = fragments(data_of(w));
    CHECK_EQ(f.size(), 1u);
    CHECK_EQ(f[0].count, 2u);
    pv_segment_writer_close(w);
    // A key frame that would start a fragment with no room for its slot: FULL
    // (the buffer holds the header, the sidx slot, one moof slot, and a few
    // hundred bytes of samples).
    w = open(90, 14400);
    CHECK_EQ(pv_segment_writer_add(w, key.data(), key.size(), 0, 1), PV_OK);
    CHECK_EQ(pv_segment_writer_add(w, key.data(), key.size(), 1000000, 1), PV_ERR_SEGMENT_FULL);
    CHECK_EQ(pv_segment_writer_finish(w, 1000000), PV_OK);
    CHECK_EQ(fragments(data_of(w)).size(), 1u);
    pv_segment_writer_close(w);

    // The sample bound per fragment.
    w = open(90, 4 << 20);
    ts::Bytes tiny = cat(start_code(4), {0x41, 0x7f, 0x7f});
    CHECK_EQ(pv_segment_writer_add(w, key.data(), key.size(), 0, 1), PV_OK);
    for (uint32_t i = 1; i < PV_SEGMENT_MAX_SAMPLES_PER_FRAGMENT; ++i) {
        CHECK_EQ(pv_segment_writer_add(w, tiny.data(), tiny.size(), i * 100, 0), PV_OK);
    }
    CHECK_EQ(pv_segment_writer_add(w, tiny.data(), tiny.size(), 200000, 0), PV_ERR_SEGMENT_FULL);
    // A key frame opens the next fragment and goes on.
    CHECK_EQ(pv_segment_writer_add(w, key.data(), key.size(), 200000, 1), PV_OK);
    CHECK_EQ(pv_segment_writer_finish(w, 300000), PV_OK);
    f = fragments(data_of(w));
    CHECK_EQ(f.size(), 2u);
    CHECK_EQ(f[0].count, PV_SEGMENT_MAX_SAMPLES_PER_FRAGMENT);
    CHECK_EQ(f[0].durations.back(), 200000u - (PV_SEGMENT_MAX_SAMPLES_PER_FRAGMENT - 1) * 100);
    CHECK_EQ(f[1].count, 1u);
    pv_segment_writer_close(w);

    // The fragment bound per segment.
    w = open(90, 4 << 20);
    for (uint32_t i = 0; i < PV_SEGMENT_MAX_FRAGMENTS; ++i) {
        CHECK_EQ(pv_segment_writer_add(w, key.data(), key.size(), i * 1000000ull, 1), PV_OK);
    }
    CHECK_EQ(pv_segment_writer_add(w, key.data(), key.size(), PV_SEGMENT_MAX_FRAGMENTS * 1000000ull, 1), PV_ERR_SEGMENT_FULL);
    CHECK_EQ(pv_segment_writer_add(w, non.data(), non.size(), PV_SEGMENT_MAX_FRAGMENTS * 1000000ull, 0), PV_OK);
    CHECK_EQ(pv_segment_writer_finish(w, (PV_SEGMENT_MAX_FRAGMENTS + 1) * 1000000ull), PV_OK);
    ts::Bytes d = data_of(w);
    f = fragments(d);
    CHECK_EQ(f.size(), PV_SEGMENT_MAX_FRAGMENTS);
    check_sidx(d, f, (PV_SEGMENT_MAX_FRAGMENTS + 1) * 1000000ull);
    pv_segment_writer_close(w);

    // Arguments.
    pv_segment_writer* out = nullptr;
    CHECK_EQ(pv_segment_writer_open(1920, 1080, 90, kSps, sizeof(kSps), kPps, sizeof(kPps), 1 << 20, nullptr), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_segment_writer_open(1920, 1080, 45, kSps, sizeof(kSps), kPps, sizeof(kPps), 1 << 20, &out), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_segment_writer_open(0, 1080, 90, kSps, sizeof(kSps), kPps, sizeof(kPps), 1 << 20, &out), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_segment_writer_open(1920, 1080, 90, nullptr, 0, kPps, sizeof(kPps), 1 << 20, &out), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_segment_writer_open(1920, 1080, 90, kSps, 3, kPps, sizeof(kPps), 1 << 20, &out), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_segment_writer_open(1920, 1080, 90, kSps, sizeof(kSps), kPps, 0, 1 << 20, &out), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_segment_writer_open(1920, 1080, 90, kSps, sizeof(kSps), kPps, sizeof(kPps), 4096, &out), PV_ERR_INVALID_ARGUMENT);
    CHECK(out == nullptr);
    w = open();
    CHECK_EQ(pv_segment_writer_add(nullptr, key.data(), key.size(), 0, 1), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_segment_writer_add(w, nullptr, 1, 0, 1), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_segment_writer_add(w, key.data(), 0, 0, 1), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_segment_writer_finish(nullptr, 1), PV_ERR_INVALID_ARGUMENT);
    const uint8_t* p = nullptr;
    size_t n = 0;
    CHECK_EQ(pv_segment_writer_data(nullptr, &p, &n), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_segment_writer_data(w, nullptr, &n), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_segment_writer_stats(nullptr, nullptr, nullptr, nullptr), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_segment_writer_stats(w, nullptr, nullptr, nullptr), PV_OK);
    pv_segment_writer_close(w);
    CHECK_EQ(std::string(pv_error_name(PV_ERR_SEGMENT_FULL)), "PV_ERR_SEGMENT_FULL");
    CHECK_EQ(std::string(pv_error_name(PV_ERR_SEGMENT_STATE)), "PV_ERR_SEGMENT_STATE");
}

// The segment as an item object: encrypted from the writer buffer through
// the memory source, read back whole and through the header peek.
void round_trip() {
    const std::string vdir = "segment_vault";
    ts::remove_tree(vdir);
    CHECK_EQ(pv_vault_create(vdir.c_str()), PV_OK);
    pv_vault* v = nullptr;
    CHECK_EQ(pv_vault_open(vdir.c_str(), ts::test_vmk(), PV_VMK_BYTES, &v), PV_OK);
    Golden g = golden_inputs();
    pv_segment_writer* w = open();
    for (size_t i = 0; i < 3; ++i) CHECK_EQ(pv_segment_writer_add(w, g.samples[i].data(), g.samples[i].size(), g.pts[i], i == 0), PV_OK);
    CHECK_EQ(pv_segment_writer_finish(w, g.end_us), PV_OK);
    const uint8_t* data = nullptr;
    size_t len = 0;
    CHECK_EQ(pv_segment_writer_data(w, &data, &len), PV_OK);
    pv_item_header h{};
    h.item_version = PV_ITEM_VERSION;
    h.header_len = PV_ITEM_HEADER_BYTES;
    h.media_type = PV_MEDIA_VIDEO_SEGMENT;
    h.rotation_degrees = 90;
    h.width = 1920;
    h.height = 1080;
    h.created_unix_ms = 1791849600000ull;
    h.payload_len = len;
    uint8_t raw[PV_ITEM_HEADER_BYTES];
    size_t raw_len = 0;
    CHECK_EQ(pv_item_header_encode(&h, raw, sizeof(raw), &raw_len), PV_OK);
    const uint8_t* parts[] = {raw, data};
    const size_t lens[] = {raw_len, len};
    pv_source src{};
    CHECK_EQ(pv_source_memory_new(parts, lens, 2, &src), PV_OK);
    uint8_t id[PV_OBJECT_ID_BYTES];
    CHECK_EQ(pv_vault_encrypt(v, &src, 0, id), PV_OK);
    pv_source_memory_free(&src);
    ts::MemSink sink;
    pv_sink k = sink.as_sink();
    uint64_t plain_len = 0;
    CHECK_EQ(pv_vault_decrypt(v, id, &k, &plain_len), PV_OK);
    CHECK_EQ(plain_len, raw_len + len);
    CHECK(std::memcmp(sink.data.data(), raw, raw_len) == 0);
    CHECK(std::memcmp(sink.data.data() + raw_len, data, len) == 0);
    pv_item_header peek{};
    uint64_t total = 0;
    CHECK_EQ(pv_object_item_header(v, id, &peek, &total), PV_OK);
    CHECK_EQ(peek.media_type, PV_MEDIA_VIDEO_SEGMENT);
    CHECK_EQ(peek.payload_len, len);
    pv_segment_writer_close(w);
    pv_vault_close(v);
    ts::remove_tree(vdir);
}

// The committed vector: the same inputs give the same bytes, and the
// offsets in segment.txt name the boxes.
void golden() {
    const std::string dir = PV_FIXTURES_DIR;
    ts::Bytes fixture = ts::read_file(dir + "/segment_one_fragment.bin");
    Golden g = golden_inputs();
    pv_segment_writer* w = open();
    for (size_t i = 0; i < 3; ++i) CHECK_EQ(pv_segment_writer_add(w, g.samples[i].data(), g.samples[i].size(), g.pts[i], i == 0), PV_OK);
    CHECK_EQ(pv_segment_writer_finish(w, g.end_us), PV_OK);
    ts::Bytes d = data_of(w);
    pv_segment_writer_close(w);
    CHECK_EQ(d.size(), fixture.size());
    CHECK(d == fixture);
    check_header(fixture, 90);
    std::vector<Frag> f = fragments(fixture);
    CHECK_EQ(f.size(), 1u);
    CHECK_EQ(f[0].count, 3u);
    CHECK(f[0].durations == std::vector<uint32_t>({33333, 33334, 33333}));
    check_sidx(fixture, f, g.end_us);
    // segment.txt: the hash and the offsets.
    ts::Bytes text = ts::read_file(dir + "/segment.txt");
    std::string t(text.begin(), text.end());
    std::string hash = ts::hex(ts::blake2b(fixture));
    CHECK(t.find("blake2b " + hash) != std::string::npos);
    Box moov = find(fixture, 0, fixture.size(), "moov");
    Box sidx = find(fixture, 0, fixture.size(), "sidx");
    CHECK(t.find("offsets " + std::to_string(moov.end) + " " + std::to_string(sidx.end) + " " +
                 std::to_string(f[0].moof.start) + " " + std::to_string(f[0].mdat.start) + " " +
                 std::to_string(fixture.size())) != std::string::npos);
}

}  // namespace

int main() {
    CHECK_EQ(pv_init(), PV_OK);
    three_fragments();
    rotations_and_parameter_sets();
    rules_and_bounds();
    round_trip();
    golden();
    std::printf("segment writer ok\n");
    return 0;
}
