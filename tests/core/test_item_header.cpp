// Item header codec: round trip, byte layout, every field rejection, larger
// header_len with unknown trailing bytes, plaintext without the magic,
// check_len, argument checks.
#include "test_support.h"

#include <cstdio>

static pv_item_header sample() {
    pv_item_header h{};
    h.item_version = PV_ITEM_VERSION;
    h.header_len = PV_ITEM_HEADER_BYTES;
    h.media_type = PV_MEDIA_JPEG;
    h.rotation_degrees = 90;
    h.width = 4000;
    h.height = 3000;
    h.created_unix_ms = 1791849600000ull;
    h.payload_len = 123456789ull;
    return h;
}

static ts::Bytes encode(const pv_item_header& h) {
    ts::Bytes out(PV_ITEM_HEADER_BYTES);
    size_t n = 0;
    CHECK_EQ(pv_item_header_encode(&h, out.data(), out.size(), &n), PV_OK);
    CHECK_EQ(n, static_cast<size_t>(PV_ITEM_HEADER_BYTES));
    return out;
}

static int decode(const ts::Bytes& b, pv_item_header* out) {
    return pv_item_header_decode(b.data(), b.size(), out);
}

static void put16(ts::Bytes& b, size_t off, uint16_t v) {
    b[off] = static_cast<uint8_t>(v & 0xff);
    b[off + 1] = static_cast<uint8_t>(v >> 8);
}

int main() {
    CHECK_EQ(pv_init(), PV_OK);
    pv_item_header h = sample();
    ts::Bytes raw = encode(h);

    // Byte layout from docs/PHASE2_DESIGN.md Section 3.
    CHECK_EQ(std::memcmp(raw.data(), "PVITEM01", 8), 0);
    CHECK(raw[8] == 1 && raw[9] == 0);
    CHECK(raw[10] == 48 && raw[11] == 0);
    CHECK(raw[12] == 1 && raw[13] == 0);
    CHECK(raw[14] == 90 && raw[15] == 0);
    CHECK(raw[16] == 0xa0 && raw[17] == 0x0f && raw[18] == 0 && raw[19] == 0);   // 4000
    CHECK(raw[20] == 0xb8 && raw[21] == 0x0b && raw[22] == 0 && raw[23] == 0);   // 3000
    CHECK(raw[32] == 0x15 && raw[33] == 0xcd && raw[34] == 0x5b && raw[35] == 0x07); // 123456789
    for (size_t i = 40; i < 48; ++i) CHECK_EQ(raw[i], 0);

    // Round trip.
    pv_item_header back{};
    CHECK_EQ(decode(raw, &back), PV_OK);
    CHECK_EQ(back.item_version, h.item_version);
    CHECK_EQ(back.header_len, h.header_len);
    CHECK_EQ(back.media_type, h.media_type);
    CHECK_EQ(back.rotation_degrees, h.rotation_degrees);
    CHECK_EQ(back.width, h.width);
    CHECK_EQ(back.height, h.height);
    CHECK_EQ(back.created_unix_ms, h.created_unix_ms);
    CHECK_EQ(back.payload_len, h.payload_len);

    // Every rotation value and media type round trips; reserved is ignored.
    for (int r : {0, 90, 180, 270}) {
        for (int t : {PV_MEDIA_JPEG, PV_MEDIA_VIDEO_SEGMENT, PV_MEDIA_VIDEO_INDEX}) {
            pv_item_header x = sample();
            x.rotation_degrees = static_cast<uint16_t>(r);
            x.media_type = static_cast<uint16_t>(t);
            ts::Bytes e = encode(x);
            e[40] = 0xff; e[47] = 0x01;
            CHECK_EQ(decode(e, &back), PV_OK);
            CHECK_EQ(back.rotation_degrees, r);
            CHECK_EQ(back.media_type, t);
        }
    }

    // Decode with extra bytes after the header is fine (len > header_len).
    {
        ts::Bytes e = raw;
        e.resize(1000, 0xee);
        CHECK_EQ(decode(e, &back), PV_OK);
        CHECK_EQ(back.header_len, 48);
    }

    // Larger header_len with unknown trailing bytes is accepted when present.
    {
        ts::Bytes e = raw;
        put16(e, 10, 64);
        e.resize(64, 0xab);
        CHECK_EQ(decode(e, &back), PV_OK);
        CHECK_EQ(back.header_len, 64);
        // ... and rejected when the buffer is shorter than header_len.
        ts::Bytes s = e;
        s.resize(60);
        CHECK_EQ(decode(s, &back), PV_ERR_ITEM_MALFORMED);
        ts::Bytes big = raw;
        put16(big, 10, 4096);
        big.resize(4096, 0);
        CHECK_EQ(decode(big, &back), PV_OK);
        CHECK_EQ(back.header_len, 4096);
    }

    // Rejections, one field at a time.
    {
        ts::Bytes e = raw; e[0] = 'X';
        CHECK_EQ(decode(e, &back), PV_ERR_NOT_ITEM);
        e = raw; e[7] = '2';
        CHECK_EQ(decode(e, &back), PV_ERR_NOT_ITEM);
        e = raw; put16(e, 8, 2);
        CHECK_EQ(decode(e, &back), PV_ERR_ITEM_UNSUPPORTED);
        e = raw; put16(e, 8, 0);
        CHECK_EQ(decode(e, &back), PV_ERR_ITEM_UNSUPPORTED);
        e = raw; put16(e, 12, 0);
        CHECK_EQ(decode(e, &back), PV_ERR_ITEM_UNSUPPORTED);
        e = raw; put16(e, 12, 4);
        CHECK_EQ(decode(e, &back), PV_ERR_ITEM_UNSUPPORTED);
        e = raw; put16(e, 12, 0xffff);
        CHECK_EQ(decode(e, &back), PV_ERR_ITEM_UNSUPPORTED);
        e = raw; put16(e, 14, 45);
        CHECK_EQ(decode(e, &back), PV_ERR_ITEM_MALFORMED);
        e = raw; put16(e, 14, 360);
        CHECK_EQ(decode(e, &back), PV_ERR_ITEM_MALFORMED);
        e = raw; put16(e, 14, 1);
        CHECK_EQ(decode(e, &back), PV_ERR_ITEM_MALFORMED);
        e = raw; put16(e, 10, 47);
        CHECK_EQ(decode(e, &back), PV_ERR_ITEM_MALFORMED);
        e = raw; put16(e, 10, 0);
        CHECK_EQ(decode(e, &back), PV_ERR_ITEM_MALFORMED);
        e = raw; put16(e, 10, 4097); e.resize(5000, 0);
        CHECK_EQ(decode(e, &back), PV_ERR_ITEM_MALFORMED);
        // Check order: version before header_len before media type before rotation.
        e = raw; put16(e, 8, 9); put16(e, 10, 1); put16(e, 12, 9); put16(e, 14, 9);
        CHECK_EQ(decode(e, &back), PV_ERR_ITEM_UNSUPPORTED);
        e = raw; put16(e, 10, 1); put16(e, 12, 9); put16(e, 14, 9);
        CHECK_EQ(decode(e, &back), PV_ERR_ITEM_MALFORMED);
        e = raw; put16(e, 12, 9); put16(e, 14, 9);
        CHECK_EQ(decode(e, &back), PV_ERR_ITEM_UNSUPPORTED);
        // A failed decode leaves out zeroed.
        back = sample();
        CHECK_EQ(decode(e, &back), PV_ERR_ITEM_UNSUPPORTED);
        CHECK_EQ(back.width, 0u);
    }

    // Short buffers: fewer than 8 bytes cannot be classified; 8 to 47 bytes
    // with the magic are malformed.
    {
        ts::Bytes e = raw;
        e.resize(7);
        CHECK_EQ(decode(e, &back), PV_ERR_NOT_ITEM);
        e = raw; e.resize(8);
        CHECK_EQ(decode(e, &back), PV_ERR_ITEM_MALFORMED);
        e = raw; e.resize(47);
        CHECK_EQ(decode(e, &back), PV_ERR_ITEM_MALFORMED);
        ts::Bytes empty;
        CHECK_EQ(pv_item_header_decode(raw.data(), 0, &back), PV_ERR_NOT_ITEM);
    }

    // A plaintext without the magic (Phase 1 deterministic data) is an unknown item.
    {
        ts::Bytes det = ts::det_bytes(100);
        CHECK_EQ(decode(det, &back), PV_ERR_NOT_ITEM);
    }

    // Encode rejects what decode rejects, plus header_len other than 48.
    {
        uint8_t out[PV_ITEM_HEADER_BYTES];
        size_t n = 0;
        pv_item_header x = sample(); x.item_version = 2;
        CHECK_EQ(pv_item_header_encode(&x, out, sizeof(out), &n), PV_ERR_ITEM_UNSUPPORTED);
        x = sample(); x.media_type = 0;
        CHECK_EQ(pv_item_header_encode(&x, out, sizeof(out), &n), PV_ERR_ITEM_UNSUPPORTED);
        x = sample(); x.rotation_degrees = 91;
        CHECK_EQ(pv_item_header_encode(&x, out, sizeof(out), &n), PV_ERR_ITEM_MALFORMED);
        x = sample(); x.header_len = 64;
        CHECK_EQ(pv_item_header_encode(&x, out, sizeof(out), &n), PV_ERR_ITEM_MALFORMED);
        x = sample(); x.header_len = 0;
        CHECK_EQ(pv_item_header_encode(&x, out, sizeof(out), &n), PV_ERR_ITEM_MALFORMED);
        x = sample();
        CHECK_EQ(pv_item_header_encode(&x, out, 47, &n), PV_ERR_INVALID_ARGUMENT);
        CHECK_EQ(pv_item_header_encode(nullptr, out, sizeof(out), &n), PV_ERR_INVALID_ARGUMENT);
        CHECK_EQ(pv_item_header_encode(&x, nullptr, sizeof(out), &n), PV_ERR_INVALID_ARGUMENT);
        CHECK_EQ(pv_item_header_encode(&x, out, sizeof(out), nullptr), PV_OK);
        CHECK_EQ(pv_item_header_decode(nullptr, 48, &back), PV_ERR_INVALID_ARGUMENT);
        CHECK_EQ(pv_item_header_decode(out, 48, nullptr), PV_ERR_INVALID_ARGUMENT);
        CHECK_EQ(pv_item_header_check_len(nullptr, 0), PV_ERR_INVALID_ARGUMENT);
    }

    // check_len.
    {
        pv_item_header x = sample();
        CHECK_EQ(pv_item_header_check_len(&x, 48 + 123456789ull), PV_OK);
        CHECK_EQ(pv_item_header_check_len(&x, 123456789ull), PV_ERR_ITEM_MALFORMED);
        CHECK_EQ(pv_item_header_check_len(&x, 48 + 123456789ull + 1), PV_ERR_ITEM_MALFORMED);
        x.payload_len = 0;
        CHECK_EQ(pv_item_header_check_len(&x, 48), PV_OK);
        x.header_len = 100;
        CHECK_EQ(pv_item_header_check_len(&x, 100), PV_OK);
        x.payload_len = UINT64_MAX;
        CHECK_EQ(pv_item_header_check_len(&x, 99), PV_ERR_ITEM_MALFORMED);
    }

    // Error names.
    CHECK_EQ(std::string(pv_error_name(PV_ERR_ITEM_MALFORMED)), "PV_ERR_ITEM_MALFORMED");
    CHECK_EQ(std::string(pv_error_name(PV_ERR_ITEM_UNSUPPORTED)), "PV_ERR_ITEM_UNSUPPORTED");
    CHECK_EQ(std::string(pv_error_name(PV_ERR_NOT_ITEM)), "PV_ERR_NOT_ITEM");
    std::printf("item header ok\n");
    return 0;
}
