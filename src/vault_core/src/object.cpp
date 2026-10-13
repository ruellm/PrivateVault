// Stream-level encrypt, decrypt, and inspect of one object.
#include "format.h"

namespace pv {

int fill(pv_source* src, uint8_t* buf, size_t want, size_t* got) {
    size_t have = 0;
    while (have < want) {
        size_t n = 0;
        if (src->read(src->ctx, buf + have, want - have, &n) != 0) {
            return PV_ERR_IO;
        }
        if (n == 0) {
            break;
        }
        if (n > want - have) {
            return PV_ERR_IO;
        }
        have += n;
    }
    *got = have;
    return PV_OK;
}

int probe_more(pv_source* src, bool* has_more) {
    uint8_t b = 0;
    size_t got = 0;
    int rc = fill(src, &b, 1, &got);
    if (rc != PV_OK) {
        return rc;
    }
    *has_more = got > 0;
    return PV_OK;
}

namespace {

// Wraps the DEK under the VMK with the header prefix as additional data.
int wrap_dek(const uint8_t* vmk, const uint8_t* dek, const uint8_t* header_prefix,
             const uint8_t* nonce, uint8_t* wrapped_out) {
    unsigned long long clen = 0;
    if (crypto_aead_xchacha20poly1305_ietf_encrypt(wrapped_out, &clen, dek, kDekBytes,
                                                   header_prefix, kOffWrappedDek, nullptr,
                                                   nonce, vmk) != 0) {
        return PV_ERR_INTERNAL;
    }
    return clen == kWrappedDekBytes ? PV_OK : PV_ERR_INTERNAL;
}

int unwrap_dek(const uint8_t* vmk, const uint8_t* wrapped, const uint8_t* header_prefix,
               const uint8_t* nonce, uint8_t* dek_out) {
    unsigned long long mlen = 0;
    if (crypto_aead_xchacha20poly1305_ietf_decrypt(dek_out, &mlen, nullptr, wrapped,
                                                   kWrappedDekBytes, header_prefix,
                                                   kOffWrappedDek, nonce, vmk) != 0) {
        return PV_ERR_WRONG_VMK;
    }
    return mlen == kDekBytes ? PV_OK : PV_ERR_INTERNAL;
}

int read_and_parse_header(pv_source* src, uint8_t* raw, Header& h) {
    size_t got = 0;
    int rc = fill(src, raw, kHeaderBytes, &got);
    if (rc != PV_OK) {
        return rc;
    }
    if (got < kHeaderBytes) {
        return PV_ERR_MALFORMED_HEADER;
    }
    return parse_header(raw, h);
}

struct StreamState {
    crypto_secretstream_xchacha20poly1305_state st;
    ~StreamState() { sodium_memzero(&st, sizeof(st)); }
};

}  // namespace

int object_encrypt(const uint8_t* vmk, pv_source* src, pv_sink* dst, uint32_t chunk_size,
                   const uint8_t* object_id) {
    Header h{};
    h.version = PV_FORMAT_VERSION;
    h.suite = PV_SUITE_SECRETSTREAM_XCHACHA20;
    h.chunk_size = chunk_size;
    if (object_id) {
        std::memcpy(h.object_id, object_id, PV_OBJECT_ID_BYTES);
    } else {
        randombytes_buf(h.object_id, PV_OBJECT_ID_BYTES);
    }
    randombytes_buf(h.wrap_nonce, kWrapNonceBytes);

    uint8_t dek[kDekBytes];
    crypto_secretstream_xchacha20poly1305_keygen(dek);
    StreamState s;
    if (crypto_secretstream_xchacha20poly1305_init_push(&s.st, h.stream_header, dek) != 0) {
        sodium_memzero(dek, sizeof(dek));
        return PV_ERR_INTERNAL;
    }

    uint8_t raw[kHeaderBytes];
    serialize_header(h, raw);
    int rc = wrap_dek(vmk, dek, raw, h.wrap_nonce, raw + kOffWrappedDek);
    sodium_memzero(dek, sizeof(dek));
    if (rc != PV_OK) {
        return rc;
    }
    rc = write_all(dst, raw, kHeaderBytes);
    if (rc != PV_OK) {
        return rc;
    }

    WipedBuffer m(chunk_size);
    std::vector<uint8_t> c(static_cast<size_t>(chunk_size) + kChunkOverhead);
    bool first = true;
    for (;;) {
        size_t got = 0;
        rc = fill(src, m.data(), chunk_size, &got);
        if (rc != PV_OK) {
            return rc;
        }
        unsigned char tag = got == chunk_size
                                ? crypto_secretstream_xchacha20poly1305_TAG_MESSAGE
                                : crypto_secretstream_xchacha20poly1305_TAG_FINAL;
        const uint8_t* ad = first ? raw : nullptr;
        unsigned long long adlen = first ? kHeaderBytes : 0;
        unsigned long long clen = 0;
        if (crypto_secretstream_xchacha20poly1305_push(&s.st, c.data(), &clen, m.data(), got,
                                                       ad, adlen, tag) != 0) {
            return PV_ERR_INTERNAL;
        }
        rc = write_all(dst, c.data(), static_cast<size_t>(clen));
        if (rc != PV_OK) {
            return rc;
        }
        first = false;
        if (tag == crypto_secretstream_xchacha20poly1305_TAG_FINAL) {
            return PV_OK;
        }
    }
}

int object_decrypt(const uint8_t* vmk, pv_source* src, pv_sink* dst,
                   const uint8_t* expected_object_id, uint64_t* plaintext_len_out) {
    uint8_t raw[kHeaderBytes];
    Header h{};
    int rc = read_and_parse_header(src, raw, h);
    if (rc != PV_OK) {
        return rc;
    }
    if (expected_object_id &&
        sodium_memcmp(expected_object_id, h.object_id, PV_OBJECT_ID_BYTES) != 0) {
        return PV_ERR_MALFORMED_HEADER;
    }

    uint8_t dek[kDekBytes];
    rc = unwrap_dek(vmk, h.wrapped_dek, raw, h.wrap_nonce, dek);
    if (rc != PV_OK) {
        sodium_memzero(dek, sizeof(dek));
        return rc;
    }
    StreamState s;
    int init_rc = crypto_secretstream_xchacha20poly1305_init_pull(&s.st, h.stream_header, dek);
    sodium_memzero(dek, sizeof(dek));
    if (init_rc != 0) {
        return PV_ERR_MALFORMED_HEADER;
    }

    const size_t full = static_cast<size_t>(h.chunk_size) + kChunkOverhead;
    std::vector<uint8_t> c(full);
    WipedBuffer m(h.chunk_size);
    bool first = true;
    uint64_t total = 0;
    for (;;) {
        size_t got = 0;
        rc = fill(src, c.data(), full, &got);
        if (rc != PV_OK) {
            return rc;
        }
        if (got < kChunkOverhead) {
            return PV_ERR_TRUNCATED;
        }
        const uint8_t* ad = first ? raw : nullptr;
        unsigned long long adlen = first ? kHeaderBytes : 0;
        unsigned long long mlen = 0;
        unsigned char tag = 0;
        if (crypto_secretstream_xchacha20poly1305_pull(&s.st, m.data(), &mlen, &tag, c.data(),
                                                       got, ad, adlen) != 0) {
            return PV_ERR_AUTH_FAILED;
        }
        first = false;
        if (tag == crypto_secretstream_xchacha20poly1305_TAG_MESSAGE) {
            if (got != full) {
                return PV_ERR_MALFORMED_CHUNK;
            }
            rc = write_all(dst, m.data(), static_cast<size_t>(mlen));
            if (rc != PV_OK) {
                return rc;
            }
            total += mlen;
            continue;
        }
        if (tag == crypto_secretstream_xchacha20poly1305_TAG_FINAL) {
            bool more = false;
            rc = probe_more(src, &more);
            if (rc != PV_OK) {
                return rc;
            }
            if (more) {
                return PV_ERR_TRAILING_DATA;
            }
            rc = write_all(dst, m.data(), static_cast<size_t>(mlen));
            if (rc != PV_OK) {
                return rc;
            }
            total += mlen;
            if (plaintext_len_out) {
                *plaintext_len_out = total;
            }
            return PV_OK;
        }
        return PV_ERR_MALFORMED_CHUNK;
    }
}

int object_inspect(pv_source* src, pv_object_info* out) {
    uint8_t raw[kHeaderBytes];
    Header h{};
    int rc = read_and_parse_header(src, raw, h);
    if (rc != PV_OK) {
        return rc;
    }
    out->format_version = h.version;
    out->suite_id = h.suite;
    out->chunk_size = h.chunk_size;
    std::memcpy(out->object_id, h.object_id, PV_OBJECT_ID_BYTES);
    return PV_OK;
}

}  // namespace pv

extern "C" {

int pv_object_encrypt(const uint8_t* vmk, size_t vmk_len, pv_source* src, pv_sink* dst,
                      uint32_t chunk_size, const uint8_t* object_id) {
    int rc = pv::ensure_init();
    if (rc != PV_OK) {
        return rc;
    }
    if (!vmk || vmk_len != PV_VMK_BYTES || !src || !src->read || !dst || !dst->write) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    if (chunk_size == 0) {
        chunk_size = PV_DEFAULT_CHUNK_SIZE;
    }
    if (!pv::chunk_size_in_range(chunk_size)) {
        return PV_ERR_CHUNK_SIZE_OUT_OF_RANGE;
    }
    try {
        return pv::object_encrypt(vmk, src, dst, chunk_size, object_id);
    } catch (const std::bad_alloc&) {
        return PV_ERR_OUT_OF_MEMORY;
    } catch (...) {
        return PV_ERR_INTERNAL;
    }
}

int pv_object_decrypt(const uint8_t* vmk, size_t vmk_len, pv_source* src, pv_sink* dst,
                      const uint8_t* expected_object_id, uint64_t* plaintext_len_out) {
    int rc = pv::ensure_init();
    if (rc != PV_OK) {
        return rc;
    }
    if (!vmk || vmk_len != PV_VMK_BYTES || !src || !src->read || !dst || !dst->write) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    try {
        return pv::object_decrypt(vmk, src, dst, expected_object_id, plaintext_len_out);
    } catch (const std::bad_alloc&) {
        return PV_ERR_OUT_OF_MEMORY;
    } catch (...) {
        return PV_ERR_INTERNAL;
    }
}

int pv_object_inspect(pv_source* src, pv_object_info* out) {
    int rc = pv::ensure_init();
    if (rc != PV_OK) {
        return rc;
    }
    if (!src || !src->read || !out) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    try {
        return pv::object_inspect(src, out);
    } catch (...) {
        return PV_ERR_INTERNAL;
    }
}

}
