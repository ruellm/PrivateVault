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
    // The audio traf, when the fragment has one.
    bool has_audio = false;
    uint64_t audio_tfdt = 0;
    std::vector<uint32_t> audio_durations, audio_sizes;
    size_t audio_data_start = 0;
};

// The three boxes of a traf: tfhd for the track, tfdt version 1, the trun
// with the given flags. Returns the trun box.
Box traf_boxes(const ts::Bytes& d, const Box& traf, uint32_t track, uint32_t trun_flags, uint64_t* tfdt) {
    std::vector<Box> kids = children(d, traf.body, traf.end);
    CHECK_EQ(kids.size(), 3u);
    CHECK_EQ(kids[0].type, "tfhd");
    CHECK_EQ(rd32(d, kids[0].body), 0x020000u);
    CHECK_EQ(rd32(d, kids[0].body + 4), track);
    CHECK_EQ(kids[1].type, "tfdt");
    CHECK_EQ(rd32(d, kids[1].body), 0x01000000u);
    *tfdt = rd64(d, kids[1].body + 4);
    CHECK_EQ(kids[2].type, "trun");
    CHECK_EQ(rd32(d, kids[2].body), trun_flags);
    return kids[2];
}

std::vector<Frag> fragments(const ts::Bytes& d) {
    std::vector<Frag> out;
    std::vector<Box> top = children(d, 0, d.size());
    for (size_t i = 0; i < top.size(); ++i) {
        if (top[i].type != "moof") continue;
        CHECK(i + 1 < top.size());
        CHECK_EQ(top[i + 1].type, "mdat");
        Frag f{top[i], top[i + 1], 0, 0, {}, {}, {}, 0};
        std::vector<Box> moof_kids = children(d, f.moof.body, f.moof.end);
        CHECK(moof_kids.size() == 2 || moof_kids.size() == 3);
        CHECK_EQ(moof_kids[0].type, "mfhd");
        CHECK_EQ(rd32(d, moof_kids[0].body + 4), static_cast<uint32_t>(out.size() + 1));
        CHECK_EQ(moof_kids[1].type, "traf");
        Box trun = traf_boxes(d, moof_kids[1], 1, 0x000701u, &f.tfdt);
        f.count = rd32(d, trun.body + 4);
        uint32_t data_offset = rd32(d, trun.body + 8);
        CHECK_EQ(f.moof.start + data_offset, f.mdat.body);
        f.data_start = f.mdat.body;
        size_t row = trun.body + 12;
        for (uint32_t s = 0; s < f.count; ++s) {
            f.durations.push_back(rd32(d, row));
            f.sizes.push_back(rd32(d, row + 4));
            f.flags.push_back(rd32(d, row + 8));
            row += 12;
        }
        CHECK_EQ(row, trun.end);
        size_t total = 0;
        for (uint32_t s : f.sizes) total += s;
        if (moof_kids.size() == 3) {
            CHECK_EQ(moof_kids[2].type, "traf");
            f.has_audio = true;
            Box atrun = traf_boxes(d, moof_kids[2], 2, 0x000301u, &f.audio_tfdt);
            uint32_t n = rd32(d, atrun.body + 4);
            CHECK(n > 0);
            // The audio bytes follow the video bytes.
            CHECK_EQ(f.moof.start + rd32(d, atrun.body + 8), f.mdat.body + total);
            f.audio_data_start = f.mdat.body + total;
            row = atrun.body + 12;
            for (uint32_t s = 0; s < n; ++s) {
                f.audio_durations.push_back(rd32(d, row));
                f.audio_sizes.push_back(rd32(d, row + 4));
                row += 8;
            }
            CHECK_EQ(row, atrun.end);
            for (uint32_t s : f.audio_sizes) total += s;
        }
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

// Phase 3F: the audio track (docs/PHASE3F_DESIGN.md Section 4.3).

const uint8_t kAsc[] = {0x11, 0x90};

pv_segment_audio golden_audio() {
    pv_segment_audio a{};
    a.codec = PV_AUDIO_CODEC_AAC;
    a.channels = 2;
    a.sample_rate = 48000;
    a.avg_bitrate = 128000;
    a.max_bitrate = 128000;
    a.config = kAsc;
    a.config_len = sizeof(kAsc);
    return a;
}

// The golden audio inputs, shared with make_vectors --segment-av: four
// frames of deterministic bytes, timed as an encoder would time them with
// the first frame 500 us into the segment.
struct GoldenAudio {
    std::vector<ts::Bytes> frames;
    std::vector<uint64_t> pts;
};

GoldenAudio golden_audio_inputs() {
    GoldenAudio g;
    const size_t sizes[] = {341, 342, 340, 341};
    const size_t skips[] = {1000, 1400, 1800, 2200};
    for (int i = 0; i < 4; ++i) g.frames.push_back(nozero(sizes[i], skips[i]));
    g.pts = {500, 21833, 43166, 64500};
    return g;
}

pv_segment_writer* open_av(uint16_t rotation = 90, size_t cap = 1 << 20) {
    pv_segment_audio a = golden_audio();
    pv_segment_writer* out = nullptr;
    CHECK_EQ(pv_segment_writer_open_av(1920, 1080, rotation, kSps, sizeof(kSps), kPps, sizeof(kPps), &a, cap, &out), PV_OK);
    CHECK(out != nullptr);
    return out;
}

// The audio boxes of the moov: the second trak, mp4a with esds, the second trex.
void check_audio_header(const ts::Bytes& d) {
    Box mvhd = path(d, {"moov", "mvhd"});
    CHECK_EQ(rd32(d, mvhd.body + 96), 3u);           // next_track_ID
    Box moov = find(d, 0, d.size(), "moov");
    std::vector<Box> traks;
    for (const Box& b : children(d, moov.body, moov.end)) if (b.type == "trak") traks.push_back(b);
    CHECK_EQ(traks.size(), 2u);
    Box tkhd = find(d, traks[1].body, traks[1].end, "tkhd");
    CHECK_EQ(rd32(d, tkhd.body), 3u);
    CHECK_EQ(rd32(d, tkhd.body + 12), 2u);
    CHECK_EQ(rd16(d, tkhd.body + 36), 0x0100);        // volume
    check_matrix(d, tkhd.body + 4 + 20 + 16, 0);
    CHECK_EQ(rd32(d, tkhd.body + 4 + 20 + 16 + 36), 0u);
    Box mdia = find(d, traks[1].body, traks[1].end, "mdia");
    Box mdhd = find(d, mdia.body, mdia.end, "mdhd");
    CHECK_EQ(rd32(d, mdhd.body + 12), 48000u);
    Box hdlr = find(d, mdia.body, mdia.end, "hdlr");
    CHECK_EQ(std::string(reinterpret_cast<const char*>(d.data() + hdlr.body + 8), 4), "soun");
    Box minf = find(d, mdia.body, mdia.end, "minf");
    Box smhd = find(d, minf.body, minf.end, "smhd");
    CHECK_EQ(smhd.end - smhd.body, 8u);
    Box stbl = find(d, minf.body, minf.end, "stbl");
    Box stsd = find(d, stbl.body, stbl.end, "stsd");
    CHECK_EQ(rd32(d, stsd.body + 4), 1u);
    Box mp4a = find(d, stsd.body + 8, stsd.end, "mp4a");
    CHECK_EQ(rd16(d, mp4a.body + 6), 1);              // data_reference_index
    CHECK_EQ(rd16(d, mp4a.body + 16), 2);             // channels
    CHECK_EQ(rd16(d, mp4a.body + 18), 16);            // sample size
    CHECK_EQ(rd32(d, mp4a.body + 24), 48000u << 16);
    Box esds = find(d, mp4a.body + 28, mp4a.end, "esds");
    size_t p = esds.body + 4;
    CHECK_EQ(d[p], 0x03);
    CHECK_EQ(d[p + 1], 3 + 2 + 17 + 3);
    CHECK_EQ(rd16(d, p + 2), 0);
    CHECK_EQ(d[p + 4], 0);
    CHECK_EQ(d[p + 5], 0x04);
    CHECK_EQ(d[p + 6], 17);
    CHECK_EQ(d[p + 7], 0x40);
    CHECK_EQ(d[p + 8], 0x15);
    CHECK_EQ(rd32(d, p + 12), 128000u);               // max bitrate
    CHECK_EQ(rd32(d, p + 16), 128000u);               // avg bitrate
    CHECK_EQ(d[p + 20], 0x05);
    CHECK_EQ(d[p + 21], 2);
    CHECK_EQ(d[p + 22], 0x11);
    CHECK_EQ(d[p + 23], 0x90);
    CHECK_EQ(d[p + 24], 0x06);
    CHECK_EQ(d[p + 25], 1);
    CHECK_EQ(d[p + 26], 0x02);
    CHECK_EQ(p + 27, esds.end);
    for (const char* t : {"stts", "stsc", "stsz", "stco"}) find(d, stbl.body, stbl.end, t);
    Box mvex = path(d, {"moov", "mvex"});
    std::vector<Box> trexes = children(d, mvex.body, mvex.end);
    CHECK_EQ(trexes.size(), 2u);
    CHECK_EQ(rd32(d, trexes[1].body + 4), 2u);
    CHECK_EQ(rd32(d, trexes[1].body + 8), 1u);
}

void audio_fragments() {
    Golden g = golden_inputs();
    GoldenAudio a = golden_audio_inputs();
    pv_segment_writer* w = open_av();
    uint32_t samples = 0;
    uint64_t tick = 0;
    CHECK_EQ(pv_segment_writer_audio_stats(w, &samples, &tick), PV_OK);
    CHECK_EQ(samples, 0u);
    // Fragment 1: the three video samples with the four audio frames between
    // them as they would arrive; 2: a key frame with no audio; 3: a key frame
    // with one audio frame past the end of the segment, kept.
    CHECK_EQ(pv_segment_writer_add(w, g.samples[0].data(), g.samples[0].size(), g.pts[0], 1), PV_OK);
    CHECK_EQ(pv_segment_writer_add_audio(w, a.frames[0].data(), a.frames[0].size(), a.pts[0]), PV_OK);
    CHECK_EQ(pv_segment_writer_add_audio(w, a.frames[1].data(), a.frames[1].size(), a.pts[1]), PV_OK);
    CHECK_EQ(pv_segment_writer_add(w, g.samples[1].data(), g.samples[1].size(), g.pts[1], 0), PV_OK);
    CHECK_EQ(pv_segment_writer_add_audio(w, a.frames[2].data(), a.frames[2].size(), a.pts[2]), PV_OK);
    CHECK_EQ(pv_segment_writer_add(w, g.samples[2].data(), g.samples[2].size(), g.pts[2], 0), PV_OK);
    CHECK_EQ(pv_segment_writer_add_audio(w, a.frames[3].data(), a.frames[3].size(), a.pts[3]), PV_OK);
    CHECK_EQ(pv_segment_writer_add(w, g.samples[0].data(), g.samples[0].size(), 1000000, 1), PV_OK);
    CHECK_EQ(pv_segment_writer_add(w, g.samples[1].data(), g.samples[1].size(), 2000000, 1), PV_OK);
    CHECK_EQ(pv_segment_writer_add_audio(w, a.frames[0].data(), a.frames[0].size(), 2600000), PV_OK);
    CHECK_EQ(pv_segment_writer_audio_stats(w, &samples, &tick), PV_OK);
    CHECK_EQ(samples, 5u);
    CHECK_EQ(tick, 24u);
    CHECK_EQ(pv_segment_writer_finish(w, 2500000), PV_OK);
    CHECK_EQ(pv_segment_writer_add_audio(w, a.frames[0].data(), a.frames[0].size(), 2700000), PV_ERR_SEGMENT_STATE);
    ts::Bytes d = data_of(w);
    check_header(d, 90);
    check_audio_header(d);
    std::vector<Frag> f = fragments(d);
    CHECK_EQ(f.size(), 3u);
    CHECK(f[0].has_audio);
    CHECK_EQ(f[0].audio_tfdt, 24u);
    CHECK(f[0].audio_durations == std::vector<uint32_t>({1024, 1024, 1024, 1024}));
    CHECK_EQ(f[0].audio_sizes.size(), 4u);
    size_t at = f[0].audio_data_start;
    for (size_t i = 0; i < 4; ++i) {
        CHECK_EQ(f[0].audio_sizes[i], a.frames[i].size());
        CHECK(ts::Bytes(d.begin() + static_cast<ptrdiff_t>(at), d.begin() + static_cast<ptrdiff_t>(at + a.frames[i].size())) == a.frames[i]);
        at += a.frames[i].size();
    }
    CHECK_EQ(at, f[0].mdat.end);
    // The video samples still come first, unchanged.
    at = f[0].data_start;
    for (size_t i = 0; i < 3; ++i) {
        CHECK(ts::Bytes(d.begin() + static_cast<ptrdiff_t>(at), d.begin() + static_cast<ptrdiff_t>(at + g.nals[i].size())) == g.nals[i]);
        at += g.nals[i].size();
    }
    CHECK_EQ(at, f[0].audio_data_start);
    CHECK(!f[1].has_audio);
    CHECK(f[2].has_audio);
    CHECK_EQ(f[2].audio_tfdt, 24u + 4 * 1024);     // by count, not by its own time
    CHECK(f[2].audio_durations == std::vector<uint32_t>({1024}));
    check_sidx(d, f, 2500000);
    pv_segment_writer_close(w);

    // The single-track writer refuses audio, and its bytes are the 3E shape.
    w = open();
    CHECK_EQ(pv_segment_writer_add(w, g.samples[0].data(), g.samples[0].size(), 0, 1), PV_OK);
    CHECK_EQ(pv_segment_writer_add_audio(w, a.frames[0].data(), a.frames[0].size(), 500), PV_ERR_SEGMENT_STATE);
    CHECK_EQ(pv_segment_writer_finish(w, 40000), PV_OK);
    d = data_of(w);
    CHECK_EQ(rd32(d, path(d, {"moov", "mvhd"}).body + 96), 2u);
    CHECK(!fragments(d)[0].has_audio);
    pv_segment_writer_close(w);
}

void audio_rules() {
    Golden g = golden_inputs();
    GoldenAudio a = golden_audio_inputs();
    const ts::Bytes& key = g.samples[0];
    const ts::Bytes& fr = a.frames[0];
    // Audio before the first video sample; times not decreasing; equal is fine.
    pv_segment_writer* w = open_av();
    CHECK_EQ(pv_segment_writer_add_audio(w, fr.data(), fr.size(), 0), PV_ERR_SEGMENT_STATE);
    CHECK_EQ(pv_segment_writer_add(w, key.data(), key.size(), 0, 1), PV_OK);
    CHECK_EQ(pv_segment_writer_add_audio(w, fr.data(), fr.size(), 21333), PV_OK);
    CHECK_EQ(pv_segment_writer_add_audio(w, fr.data(), fr.size(), 21332), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_segment_writer_add_audio(w, fr.data(), fr.size(), 21333), PV_OK);
    // The first tick rounds: 21333 us at 48 kHz is 1023.98 samples.
    uint32_t samples = 0;
    uint64_t tick = 0;
    CHECK_EQ(pv_segment_writer_audio_stats(w, &samples, &tick), PV_OK);
    CHECK_EQ(samples, 2u);
    CHECK_EQ(tick, 1024u);
    pv_segment_writer_close(w);

    // The frame bound per fragment; a key frame opens the next and audio goes on.
    w = open_av(90, 4 << 20);
    ts::Bytes tiny(10, 0x33);
    CHECK_EQ(pv_segment_writer_add(w, key.data(), key.size(), 0, 1), PV_OK);
    for (uint32_t i = 0; i < PV_SEGMENT_MAX_AUDIO_SAMPLES_PER_FRAGMENT; ++i) {
        CHECK_EQ(pv_segment_writer_add_audio(w, tiny.data(), tiny.size(), i * 21333ull), PV_OK);
    }
    CHECK_EQ(pv_segment_writer_add_audio(w, tiny.data(), tiny.size(), 512 * 21333ull), PV_ERR_SEGMENT_FULL);
    CHECK_EQ(pv_segment_writer_add(w, key.data(), key.size(), 11000000, 1), PV_OK);
    CHECK_EQ(pv_segment_writer_add_audio(w, tiny.data(), tiny.size(), 512 * 21333ull), PV_OK);
    CHECK_EQ(pv_segment_writer_finish(w, 12000000), PV_OK);
    std::vector<Frag> f = fragments(data_of(w));
    CHECK_EQ(f.size(), 2u);
    CHECK_EQ(f[0].audio_sizes.size(), PV_SEGMENT_MAX_AUDIO_SAMPLES_PER_FRAGMENT);
    CHECK_EQ(f[1].audio_sizes.size(), 1u);
    CHECK_EQ(f[1].audio_tfdt, 512u * 1024u);
    pv_segment_writer_close(w);

    // The stage bound: frames of 60 000 bytes, the ninth does not fit the
    // 512 KiB stage; the segment still finishes.
    w = open_av(90, 4 << 20);
    ts::Bytes big(60000, 0x44);
    CHECK_EQ(pv_segment_writer_add(w, key.data(), key.size(), 0, 1), PV_OK);
    for (uint32_t i = 0; i < 8; ++i) CHECK_EQ(pv_segment_writer_add_audio(w, big.data(), big.size(), i * 21333ull), PV_OK);
    CHECK_EQ(pv_segment_writer_add_audio(w, big.data(), big.size(), 8 * 21333ull), PV_ERR_SEGMENT_FULL);
    CHECK_EQ(pv_segment_writer_finish(w, 1000000), PV_OK);
    f = fragments(data_of(w));
    CHECK_EQ(f[0].audio_sizes.size(), 8u);
    pv_segment_writer_close(w);

    // A smaller stage from the description: two frames of 4000 bytes fit an
    // 8 KiB stage, the third does not.
    pv_segment_audio small = golden_audio();
    small.stage_bytes = 8192;
    CHECK_EQ(pv_segment_writer_open_av(1920, 1080, 90, kSps, sizeof(kSps), kPps, sizeof(kPps), &small, 1 << 20, &w), PV_OK);
    ts::Bytes four(4000, 0x66);
    CHECK_EQ(pv_segment_writer_add(w, key.data(), key.size(), 0, 1), PV_OK);
    CHECK_EQ(pv_segment_writer_add_audio(w, four.data(), four.size(), 0), PV_OK);
    CHECK_EQ(pv_segment_writer_add_audio(w, four.data(), four.size(), 21333), PV_OK);
    CHECK_EQ(pv_segment_writer_add_audio(w, four.data(), four.size(), 42666), PV_ERR_SEGMENT_FULL);
    CHECK_EQ(pv_segment_writer_finish(w, 100000), PV_OK);
    CHECK_EQ(fragments(data_of(w))[0].audio_sizes.size(), 2u);
    pv_segment_writer_close(w);

    // The buffer room: the staged audio counts against the segment buffer,
    // so a video sample that would no longer fit beside it is refused.
    pv_segment_writer* probe = open_av();
    size_t base = 0;
    CHECK_EQ(pv_segment_writer_stats(probe, &base, nullptr, nullptr), PV_OK);
    pv_segment_writer_close(probe);
    const size_t slot = 8 + 16 + (8 + 16 + 20 + 20 + 12 * PV_SEGMENT_MAX_SAMPLES_PER_FRAGMENT) +
                        (8 + 16 + 20 + 20 + 8 * PV_SEGMENT_MAX_AUDIO_SAMPLES_PER_FRAGMENT);
    w = open_av(90, base + slot + 8 + 2400);
    CHECK_EQ(pv_segment_writer_add(w, key.data(), key.size(), 0, 1), PV_OK);    // 328 bytes stored
    ts::Bytes mid(1500, 0x55);
    ts::Bytes vid = cat(start_code(4), cat({0x41}, nozero(595, 0)));         // 600 bytes stored
    CHECK_EQ(pv_segment_writer_add_audio(w, mid.data(), mid.size(), 500), PV_OK);
    CHECK_EQ(pv_segment_writer_add_audio(w, mid.data(), mid.size(), 21833), PV_ERR_SEGMENT_FULL);
    CHECK_EQ(pv_segment_writer_add(w, vid.data(), vid.size(), 33333, 0), PV_ERR_SEGMENT_FULL);
    CHECK_EQ(pv_segment_writer_add(w, g.samples[1].data(), g.samples[1].size(), 33333, 0), PV_OK);
    CHECK_EQ(pv_segment_writer_finish(w, 66667), PV_OK);
    f = fragments(data_of(w));
    CHECK_EQ(f[0].count, 2u);
    CHECK_EQ(f[0].audio_sizes.size(), 1u);
    pv_segment_writer_close(w);

    // Arguments: the audio description.
    pv_segment_writer* out = nullptr;
    pv_segment_audio bad = golden_audio();
    bad.codec = 2;
    CHECK_EQ(pv_segment_writer_open_av(1920, 1080, 90, kSps, sizeof(kSps), kPps, sizeof(kPps), &bad, 1 << 20, &out), PV_ERR_INVALID_ARGUMENT);
    bad = golden_audio();
    bad.channels = 3;
    CHECK_EQ(pv_segment_writer_open_av(1920, 1080, 90, kSps, sizeof(kSps), kPps, sizeof(kPps), &bad, 1 << 20, &out), PV_ERR_INVALID_ARGUMENT);
    bad = golden_audio();
    bad.sample_rate = 1000;
    CHECK_EQ(pv_segment_writer_open_av(1920, 1080, 90, kSps, sizeof(kSps), kPps, sizeof(kPps), &bad, 1 << 20, &out), PV_ERR_INVALID_ARGUMENT);
    bad = golden_audio();
    bad.config = nullptr;
    CHECK_EQ(pv_segment_writer_open_av(1920, 1080, 90, kSps, sizeof(kSps), kPps, sizeof(kPps), &bad, 1 << 20, &out), PV_ERR_INVALID_ARGUMENT);
    bad = golden_audio();
    bad.config_len = 1;
    CHECK_EQ(pv_segment_writer_open_av(1920, 1080, 90, kSps, sizeof(kSps), kPps, sizeof(kPps), &bad, 1 << 20, &out), PV_ERR_INVALID_ARGUMENT);
    CHECK(out == nullptr);
    // A buffer too small for the larger moof slot.
    CHECK_EQ(pv_segment_writer_open_av(1920, 1080, 90, kSps, sizeof(kSps), kPps, sizeof(kPps), &bad, 14400, &out), PV_ERR_INVALID_ARGUMENT);
    w = open_av();
    CHECK_EQ(pv_segment_writer_add_audio(nullptr, fr.data(), fr.size(), 0), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_segment_writer_add_audio(w, nullptr, 1, 0), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_segment_writer_add_audio(w, fr.data(), 0, 0), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_segment_writer_audio_stats(nullptr, nullptr, nullptr), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_segment_writer_audio_stats(w, nullptr, nullptr), PV_OK);
    pv_segment_writer_close(w);
}

// One hour of audio frames timed as the recorder times them (a frame count
// at 48 kHz, in whole microseconds), through writers cut every three
// seconds of 30 fps video: every audio tfdt is the first tick plus 1024
// times the frames before it, every duration is 1024, and the tick clock
// stays within one sample of the microsecond clock.
void audio_one_hour() {
    const uint64_t rate = 48000;
    // The frames whose time lies before the end of the last segment.
    const uint64_t total_frames = 168749;
    const uint64_t video_period = 33333;
    const uint32_t frames_per_segment = 90;   // three one-second GOPs
    const uint32_t segments = 1200;
    ts::Bytes key = {0, 0, 0, 1, 0x65, 0x7f, 0x7f};
    ts::Bytes non = {0, 0, 0, 1, 0x41, 0x7f, 0x7f};
    ts::Bytes aac(16, 0x5a);
    auto audio_pts = [&](uint64_t n) { return (n * 1024 * 1000000000ull / rate) / 1000; };
    auto ticks_of = [&](uint64_t us) { return (us * rate + 500000) / 1000000; };
    uint64_t next_audio = 0;
    uint64_t written = 0;
    for (uint32_t s = 0; s < segments; ++s) {
        const uint64_t start = static_cast<uint64_t>(s) * frames_per_segment * video_period;
        const uint64_t end = static_cast<uint64_t>(s + 1) * frames_per_segment * video_period;
        pv_segment_writer* w = open_av(0, 96 << 10);
        uint64_t first_audio = 0;
        uint64_t count = 0;
        for (uint32_t i = 0; i < frames_per_segment; ++i) {
            const uint64_t pts = start + static_cast<uint64_t>(i) * video_period;
            const ts::Bytes& v = i % 30 == 0 ? key : non;
            CHECK_EQ(pv_segment_writer_add(w, v.data(), v.size(), pts - start, i % 30 == 0), PV_OK);
            // The audio the video has reached, and at the last sample the
            // rest of the segment (the move at the cut).
            const uint64_t until = i + 1 < frames_per_segment ? pts + 1 : end;
            while (audio_pts(next_audio) < until) {
                const uint64_t t = audio_pts(next_audio);
                if (count == 0) first_audio = t;
                CHECK_EQ(pv_segment_writer_add_audio(w, aac.data(), aac.size(), t - start), PV_OK);
                count++;
                next_audio++;
            }
        }
        CHECK_EQ(pv_segment_writer_finish(w, end - start), PV_OK);
        uint32_t samples = 0;
        uint64_t tick = 0;
        CHECK_EQ(pv_segment_writer_audio_stats(w, &samples, &tick), PV_OK);
        CHECK_EQ(samples, count);
        CHECK_EQ(tick, ticks_of(first_audio - start));
        ts::Bytes d = data_of(w);
        std::vector<Frag> f = fragments(d);
        CHECK_EQ(f.size(), 3u);
        uint64_t before = 0;
        for (const Frag& fr : f) {
            CHECK(fr.has_audio);
            CHECK_EQ(fr.audio_tfdt, tick + 1024 * before);
            for (uint32_t dur : fr.audio_durations) CHECK_EQ(dur, 1024u);
            before += fr.audio_sizes.size();
        }
        CHECK_EQ(before, count);
        // The last frame of the segment by ticks against its microsecond time.
        const uint64_t last_us = audio_pts(next_audio - 1) - start;
        const int64_t drift = static_cast<int64_t>(tick + 1024 * (count - 1)) - static_cast<int64_t>(ticks_of(last_us));
        CHECK(drift >= -1 && drift <= 1);
        written += count;
        pv_segment_writer_close(w);
    }
    CHECK_EQ(next_audio, total_frames);
    CHECK_EQ(written, total_frames);
}

// The committed two-track vector: the same inputs give the same bytes, and
// the offsets in segment_av.txt name the boxes.
void golden_av() {
    const std::string dir = PV_FIXTURES_DIR;
    ts::Bytes fixture = ts::read_file(dir + "/segment_av_one_fragment.bin");
    Golden g = golden_inputs();
    GoldenAudio a = golden_audio_inputs();
    pv_segment_writer* w = open_av();
    CHECK_EQ(pv_segment_writer_add(w, g.samples[0].data(), g.samples[0].size(), g.pts[0], 1), PV_OK);
    CHECK_EQ(pv_segment_writer_add_audio(w, a.frames[0].data(), a.frames[0].size(), a.pts[0]), PV_OK);
    CHECK_EQ(pv_segment_writer_add_audio(w, a.frames[1].data(), a.frames[1].size(), a.pts[1]), PV_OK);
    CHECK_EQ(pv_segment_writer_add(w, g.samples[1].data(), g.samples[1].size(), g.pts[1], 0), PV_OK);
    CHECK_EQ(pv_segment_writer_add_audio(w, a.frames[2].data(), a.frames[2].size(), a.pts[2]), PV_OK);
    CHECK_EQ(pv_segment_writer_add(w, g.samples[2].data(), g.samples[2].size(), g.pts[2], 0), PV_OK);
    CHECK_EQ(pv_segment_writer_add_audio(w, a.frames[3].data(), a.frames[3].size(), a.pts[3]), PV_OK);
    CHECK_EQ(pv_segment_writer_finish(w, g.end_us), PV_OK);
    ts::Bytes d = data_of(w);
    pv_segment_writer_close(w);
    CHECK_EQ(d.size(), fixture.size());
    CHECK(d == fixture);
    check_header(fixture, 90);
    check_audio_header(fixture);
    std::vector<Frag> f = fragments(fixture);
    CHECK_EQ(f.size(), 1u);
    CHECK_EQ(f[0].count, 3u);
    CHECK(f[0].has_audio);
    CHECK_EQ(f[0].audio_tfdt, 24u);
    CHECK_EQ(f[0].audio_sizes.size(), 4u);
    check_sidx(fixture, f, g.end_us);
    ts::Bytes text = ts::read_file(dir + "/segment_av.txt");
    std::string t(text.begin(), text.end());
    CHECK(t.find("blake2b " + ts::hex(ts::blake2b(fixture))) != std::string::npos);
    Box moov = find(fixture, 0, fixture.size(), "moov");
    Box sidx = find(fixture, 0, fixture.size(), "sidx");
    std::vector<Box> moof_kids = children(fixture, f[0].moof.body, f[0].moof.end);
    CHECK(t.find("offsets " + std::to_string(moov.end) + " " + std::to_string(sidx.end) + " " +
                 std::to_string(f[0].moof.start) + " " + std::to_string(moof_kids[2].start) + " " +
                 std::to_string(f[0].mdat.start) + " " + std::to_string(f[0].audio_data_start) + " " +
                 std::to_string(fixture.size())) != std::string::npos);
    // The audio frames file holds the four frames back to back.
    ts::Bytes frames = ts::read_file(dir + "/segment_av_audio.bin");
    ts::Bytes all;
    for (const ts::Bytes& fr : a.frames) all.insert(all.end(), fr.begin(), fr.end());
    CHECK(frames == all);
}

}  // namespace

int main() {
    CHECK_EQ(pv_init(), PV_OK);
    three_fragments();
    rotations_and_parameter_sets();
    rules_and_bounds();
    round_trip();
    golden();
    audio_fragments();
    audio_rules();
    audio_one_hour();
    golden_av();
    std::printf("segment writer ok\n");
    return 0;
}
