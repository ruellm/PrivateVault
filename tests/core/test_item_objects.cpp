// Item-wrapped objects end to end: header plus a synthetic payload through the
// memory source into an object, decrypted through the item sink; the four
// item-sink paths (valid item, no magic, bad or truncated header_len, decrypt
// failure then abort); tamper and truncation fail before any header parse.
#include "test_support.h"

#include <cstdio>

namespace {

// Payload sink that records what it received and whether it was aborted.
struct RecSink {
    ts::Bytes data;
    int aborts = 0;
    pv_sink as_sink() { return pv_sink{this, &RecSink::write}; }
    static int write(void* ctx, const uint8_t* buf, size_t n) {
        RecSink* s = static_cast<RecSink*>(ctx);
        CHECK_EQ(s->aborts, 0);   // nothing arrives after an abort
        s->data.insert(s->data.end(), buf, buf + n);
        return 0;
    }
    static void abort(pv_sink* s) { static_cast<RecSink*>(s->ctx)->aborts += 1; }
};

pv_item_header header_for(uint64_t payload_len, uint16_t type = PV_MEDIA_JPEG,
                          uint16_t rotation = 90) {
    pv_item_header h{};
    h.item_version = PV_ITEM_VERSION;
    h.header_len = PV_ITEM_HEADER_BYTES;
    h.media_type = type;
    h.rotation_degrees = rotation;
    h.width = 1280;
    h.height = 960;
    h.created_unix_ms = 1791849600000ull;
    h.payload_len = payload_len;
    return h;
}

// Encrypts [header, payload] through the memory source.
ts::Bytes encrypt_item(const pv_item_header& h, const ts::Bytes& payload, uint32_t chunk) {
    uint8_t raw[PV_ITEM_HEADER_BYTES];
    size_t n = 0;
    CHECK_EQ(pv_item_header_encode(&h, raw, sizeof(raw), &n), PV_OK);
    const uint8_t* parts[] = {raw, payload.data()};
    size_t lens[] = {n, payload.size()};
    pv_source src{};
    CHECK_EQ(pv_source_memory_new(parts, lens, 2, &src), PV_OK);
    ts::MemSink sink;
    pv_sink k = sink.as_sink();
    CHECK_EQ(pv_object_encrypt(ts::test_vmk(), PV_VMK_BYTES, &src, &k, chunk, nullptr), PV_OK);
    pv_source_memory_free(&src);
    return sink.data;
}

// Decrypts an object through the item sink the way the app does. Returns the
// decrypt status; item_rc gets the finish or abort outcome.
int decrypt_item(const ts::Bytes& cipher, RecSink& payload, pv_item_header& h, int* item_rc,
                 bool short_reads = false, const uint8_t* vmk = ts::test_vmk()) {
    pv_sink p = payload.as_sink();
    pv_sink item{};
    CHECK_EQ(pv_sink_item_new(&p, &RecSink::abort, &item), PV_OK);
    ts::MemSource src{&cipher};
    src.cycle_short = short_reads;
    pv_source s = src.as_source();
    uint64_t len = 0;
    int rc = pv_object_decrypt(vmk, PV_VMK_BYTES, &s, &item, nullptr, &len);
    if (rc != PV_OK) {
        pv_sink_item_abort(&item);
        *item_rc = -1;
        return rc;
    }
    *item_rc = pv_sink_item_finish(&item, len, &h);
    CHECK(item.ctx == nullptr && item.write == nullptr);
    return rc;
}

}  // namespace

int main() {
    CHECK_EQ(pv_init(), PV_OK);
    const uint32_t c = PV_MIN_CHUNK_SIZE;

    // Path 1: valid items of several sizes, normal and short reads.
    for (uint64_t plen : {0ull, 1ull, 100ull, static_cast<uint64_t>(c - 48),
                          static_cast<uint64_t>(c - 47), static_cast<uint64_t>(3 * c + 100),
                          200000ull}) {
        ts::Bytes payload = ts::det_bytes(plen);
        pv_item_header h = header_for(plen);
        ts::Bytes cipher = encrypt_item(h, payload, c);
        CHECK_EQ(cipher.size(), PV_HEADER_BYTES + 48 + plen + 17 * ((48 + plen) / c + 1));
        for (bool short_reads : {false, true}) {
            RecSink rec;
            pv_item_header back{};
            int item_rc = 0;
            CHECK_EQ(decrypt_item(cipher, rec, back, &item_rc, short_reads), PV_OK);
            CHECK_EQ(item_rc, PV_OK);
            CHECK_EQ(rec.aborts, 0);
            CHECK(rec.data == payload);
            CHECK(ts::blake2b(rec.data) == ts::blake2b(payload));
            CHECK_EQ(back.media_type, PV_MEDIA_JPEG);
            CHECK_EQ(back.rotation_degrees, 90);
            CHECK_EQ(back.width, 1280u);
            CHECK_EQ(back.height, 960u);
            CHECK_EQ(back.created_unix_ms, 1791849600000ull);
            CHECK_EQ(back.payload_len, plen);
            CHECK_EQ(back.header_len, 48);
        }
    }

    // The same through a vault directory, with pv_vault_decrypt and a BLAKE2b
    // payload sink (the Android verify path).
    {
        ts::remove_tree("item_vault");
        CHECK_EQ(pv_vault_create("item_vault"), PV_OK);
        pv_vault* v = nullptr;
        CHECK_EQ(pv_vault_open("item_vault", ts::test_vmk(), PV_VMK_BYTES, &v), PV_OK);
        ts::Bytes payload = ts::det_bytes(150000);
        pv_item_header h = header_for(payload.size(), PV_MEDIA_VIDEO_SEGMENT, 270);
        uint8_t raw[PV_ITEM_HEADER_BYTES];
        size_t n = 0;
        CHECK_EQ(pv_item_header_encode(&h, raw, sizeof(raw), &n), PV_OK);
        const uint8_t* parts[] = {raw, payload.data()};
        size_t lens[] = {n, payload.size()};
        pv_source src{};
        CHECK_EQ(pv_source_memory_new(parts, lens, 2, &src), PV_OK);
        uint8_t id[PV_OBJECT_ID_BYTES];
        CHECK_EQ(pv_vault_encrypt(v, &src, 0, id), PV_OK);
        pv_source_memory_free(&src);

        pv_sink hash{};
        CHECK_EQ(pv_sink_blake2b_new(&hash), PV_OK);
        pv_sink item{};
        CHECK_EQ(pv_sink_item_new(&hash, pv_sink_blake2b_abort, &item), PV_OK);
        uint64_t len = 0;
        CHECK_EQ(pv_vault_decrypt(v, id, &item, &len), PV_OK);
        CHECK_EQ(len, 48u + payload.size());
        pv_item_header back{};
        CHECK_EQ(pv_sink_item_finish(&item, len, &back), PV_OK);
        CHECK_EQ(back.media_type, PV_MEDIA_VIDEO_SEGMENT);
        CHECK_EQ(back.rotation_degrees, 270);
        ts::Bytes digest(PV_HASH_BYTES);
        CHECK_EQ(pv_sink_blake2b_finish(&hash, digest.data(), digest.size()), PV_OK);
        CHECK(digest == ts::blake2b(payload));

        // Wrong key through the vault: abort path with the real BLAKE2b sink.
        pv_vault* other = nullptr;
        CHECK_EQ(pv_vault_open("item_vault", ts::other_vmk().data(), PV_VMK_BYTES, &other), PV_OK);
        CHECK_EQ(pv_sink_blake2b_new(&hash), PV_OK);
        CHECK_EQ(pv_sink_item_new(&hash, pv_sink_blake2b_abort, &item), PV_OK);
        CHECK_EQ(pv_vault_decrypt(other, id, &item, &len), PV_ERR_WRONG_VMK);
        pv_sink_item_abort(&item);
        CHECK(hash.ctx == nullptr);   // the BLAKE2b sink was aborted by the item sink
        pv_vault_close(other);
        pv_vault_close(v);
        ts::remove_tree("item_vault");
    }

    // Path 2: no magic. A Phase 1 style object (deterministic bytes) and an
    // empty object: nothing forwarded, payload aborted once, NOT_ITEM.
    for (uint64_t plen : {0ull, 5ull, 11ull, 12ull, 100ull, static_cast<uint64_t>(2 * c + 1)}) {
        ts::Bytes plain = ts::det_bytes(plen);
        ts::Bytes cipher = ts::encrypt_mem(ts::test_vmk(), plain, c);
        for (bool short_reads : {false, true}) {
            RecSink rec;
            pv_item_header back{};
            back.width = 7;
            int item_rc = 0;
            CHECK_EQ(decrypt_item(cipher, rec, back, &item_rc, short_reads), PV_OK);
            CHECK_EQ(item_rc, PV_ERR_NOT_ITEM);
            CHECK_EQ(rec.aborts, 1);
            CHECK(rec.data.empty());
            CHECK_EQ(back.width, 0u);
        }
    }
    // Magic present in a plaintext that is cut short of 12 bytes: still NOT_ITEM.
    {
        ts::Bytes plain(10, 'x');
        std::memcpy(plain.data(), "PVITEM01", 8);
        ts::Bytes cipher = ts::encrypt_mem(ts::test_vmk(), plain, c);
        RecSink rec;
        pv_item_header back{};
        int item_rc = 0;
        CHECK_EQ(decrypt_item(cipher, rec, back, &item_rc), PV_OK);
        CHECK_EQ(item_rc, PV_ERR_NOT_ITEM);
        CHECK_EQ(rec.aborts, 1);
        CHECK(rec.data.empty());
    }

    // Path 3: header_len out of range (over 4096 or under 48) or beyond the
    // plaintext: nothing forwarded, aborted, MALFORMED.
    {
        ts::Bytes payload = ts::det_bytes(1000);
        pv_item_header h = header_for(payload.size());
        ts::Bytes plain(48 + payload.size());
        size_t n = 0;
        CHECK_EQ(pv_item_header_encode(&h, plain.data(), 48, &n), PV_OK);
        std::memcpy(plain.data() + 48, payload.data(), payload.size());
        for (int bad : {4097, 0xffff, 47, 0, 12}) {
            ts::Bytes p = plain;
            p[10] = static_cast<uint8_t>(bad & 0xff);
            p[11] = static_cast<uint8_t>(bad >> 8);
            ts::Bytes cipher = ts::encrypt_mem(ts::test_vmk(), p, c);
            RecSink rec;
            pv_item_header back{};
            int item_rc = 0;
            CHECK_EQ(decrypt_item(cipher, rec, back, &item_rc, bad == 4097), PV_OK);
            CHECK_EQ(item_rc, PV_ERR_ITEM_MALFORMED);
            CHECK_EQ(rec.aborts, 1);
            CHECK(rec.data.empty());
        }
        // header_len 2000 in range but the plaintext holds only 1048 bytes.
        {
            ts::Bytes p = plain;
            p[10] = 0xd0; p[11] = 0x07;
            ts::Bytes cipher = ts::encrypt_mem(ts::test_vmk(), p, c);
            RecSink rec;
            pv_item_header back{};
            int item_rc = 0;
            CHECK_EQ(decrypt_item(cipher, rec, back, &item_rc), PV_OK);
            CHECK_EQ(item_rc, PV_ERR_ITEM_MALFORMED);
            CHECK_EQ(rec.aborts, 1);
            CHECK(rec.data.empty());
        }
        // header_len 64 in range and present: the 16 unknown bytes are skipped,
        // the payload is forwarded, and payload_len must still add up.
        {
            ts::Bytes p = plain;
            p[10] = 64; p[11] = 0;
            ts::Bytes filler(16, 0xcd);
            p.insert(p.begin() + 48, filler.begin(), filler.end());
            ts::Bytes cipher = ts::encrypt_mem(ts::test_vmk(), p, c);
            RecSink rec;
            pv_item_header back{};
            int item_rc = 0;
            CHECK_EQ(decrypt_item(cipher, rec, back, &item_rc, true), PV_OK);
            CHECK_EQ(item_rc, PV_OK);
            CHECK_EQ(back.header_len, 64);
            CHECK(rec.data == payload);
            CHECK_EQ(rec.aborts, 0);
        }
        // A field that only finish can judge (rotation, payload_len mismatch):
        // the payload was forwarded while the stream flowed, then finish
        // aborts the payload sink and reports the error.
        for (int which : {0, 1, 2}) {
            ts::Bytes p = plain;
            if (which == 0) { p[14] = 45; p[15] = 0; }                     // rotation
            if (which == 1) { p[32] = static_cast<uint8_t>(p[32] ^ 1); }  // payload_len
            if (which == 2) { p[12] = 9; }                                 // media type
            ts::Bytes cipher = ts::encrypt_mem(ts::test_vmk(), p, c);
            RecSink rec;
            pv_item_header back{};
            int item_rc = 0;
            CHECK_EQ(decrypt_item(cipher, rec, back, &item_rc), PV_OK);
            CHECK_EQ(item_rc, which == 2 ? PV_ERR_ITEM_UNSUPPORTED : PV_ERR_ITEM_MALFORMED);
            CHECK_EQ(rec.aborts, 1);
            CHECK(rec.data == payload);
        }
    }

    // Path 4: decrypt failures. Tamper and truncation fail inside the decrypt,
    // before finish could parse anything; the caller aborts and the payload
    // sink is aborted exactly once.
    {
        ts::Bytes payload = ts::det_bytes(3 * c + 100);
        pv_item_header h = header_for(payload.size());
        ts::Bytes cipher = encrypt_item(h, payload, c);
        struct Case { const char* name; ts::Bytes data; int expect; };
        ts::Bytes tampered_first = cipher; tampered_first[PV_HEADER_BYTES + 5] ^= 1;
        ts::Bytes tampered_last = cipher; tampered_last[cipher.size() - 3] ^= 1;
        ts::Bytes tampered_header = cipher; tampered_header[60] ^= 1;
        ts::Bytes cut_boundary(cipher.begin(), cipher.begin() + PV_HEADER_BYTES + (c + 17) * 2);
        ts::Bytes cut_inside(cipher.begin(), cipher.begin() + PV_HEADER_BYTES + c);
        ts::Bytes cut_final(cipher.begin(), cipher.end() - 1);
        ts::Bytes cut_header(cipher.begin(), cipher.begin() + 100);
        ts::Bytes trailing = cipher; trailing.push_back(0);
        const Case cases[] = {
            {"tampered first chunk", tampered_first, PV_ERR_AUTH_FAILED},
            {"tampered final chunk", tampered_last, PV_ERR_AUTH_FAILED},
            {"tampered wrapped dek", tampered_header, PV_ERR_WRONG_VMK},
            {"cut at chunk boundary", cut_boundary, PV_ERR_TRUNCATED},
            {"cut inside chunk", cut_inside, PV_ERR_AUTH_FAILED},
            {"cut inside final", cut_final, PV_ERR_AUTH_FAILED},
            {"cut header", cut_header, PV_ERR_MALFORMED_HEADER},
            {"trailing byte", trailing, PV_ERR_AUTH_FAILED},
        };
        for (const Case& k : cases) {
            RecSink rec;
            pv_item_header back{};
            int item_rc = 0;
            int rc = decrypt_item(k.data, rec, back, &item_rc);
            if (rc != k.expect) {
                std::fprintf(stderr, "%s: got %s\n", k.name, pv_error_name(rc));
                return 1;
            }
            CHECK_EQ(item_rc, -1);
            CHECK_EQ(rec.aborts, 1);
        }
        // Wrong key.
        RecSink rec;
        pv_item_header back{};
        int item_rc = 0;
        CHECK_EQ(decrypt_item(cipher, rec, back, &item_rc, false, ts::other_vmk().data()),
                 PV_ERR_WRONG_VMK);
        CHECK_EQ(rec.aborts, 1);
        CHECK(rec.data.empty());
    }

    // Abort on a sink that already aborted its payload aborts it only once;
    // a null payload_abort is allowed; argument checks.
    {
        ts::Bytes plain = ts::det_bytes(50);
        ts::Bytes cipher = ts::encrypt_mem(ts::test_vmk(), plain, c);
        RecSink rec;
        pv_sink p = rec.as_sink();
        pv_sink item{};
        CHECK_EQ(pv_sink_item_new(&p, &RecSink::abort, &item), PV_OK);
        ts::MemSource src{&cipher};
        pv_source s = src.as_source();
        CHECK_EQ(pv_object_decrypt(ts::test_vmk(), PV_VMK_BYTES, &s, &item, nullptr, nullptr), PV_OK);
        CHECK_EQ(rec.aborts, 1);
        pv_sink_item_abort(&item);
        CHECK_EQ(rec.aborts, 1);
        pv_sink_item_abort(&item);    // no-op when already freed
        CHECK_EQ(pv_sink_item_new(&p, nullptr, &item), PV_OK);
        pv_sink_item_abort(&item);
        CHECK_EQ(rec.aborts, 1);
        pv_item_header back{};
        CHECK_EQ(pv_sink_item_new(nullptr, nullptr, &item), PV_ERR_INVALID_ARGUMENT);
        CHECK_EQ(pv_sink_item_new(&p, nullptr, nullptr), PV_ERR_INVALID_ARGUMENT);
        CHECK_EQ(pv_sink_item_finish(&item, 0, &back), PV_ERR_INVALID_ARGUMENT);
        CHECK_EQ(pv_sink_item_new(&p, nullptr, &item), PV_OK);
        CHECK_EQ(pv_sink_item_finish(&item, 0, nullptr), PV_ERR_INVALID_ARGUMENT);
        pv_sink_item_abort(&item);
    }
    std::printf("item objects ok\n");
    return 0;
}
