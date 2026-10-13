// Object header layout, format version 1. See docs/PHASE1_DESIGN.md Section 3.
#ifndef PV_CORE_FORMAT_H
#define PV_CORE_FORMAT_H

#include "common.h"

namespace pv {

constexpr uint8_t kMagic[8] = {'P', 'V', 'O', 'B', 'J', 'E', 'C', 'T'};

constexpr size_t kOffMagic = 0;
constexpr size_t kOffVersion = 8;
constexpr size_t kOffSuite = 10;
constexpr size_t kOffChunkSize = 12;
constexpr size_t kOffObjectId = 16;
constexpr size_t kOffWrapNonce = 32;
constexpr size_t kOffWrappedDek = 56;
constexpr size_t kOffStreamHeader = 104;
constexpr size_t kHeaderBytes = PV_HEADER_BYTES;

constexpr size_t kDekBytes = crypto_secretstream_xchacha20poly1305_KEYBYTES;
constexpr size_t kWrapNonceBytes = crypto_aead_xchacha20poly1305_ietf_NPUBBYTES;
constexpr size_t kWrappedDekBytes = kDekBytes + crypto_aead_xchacha20poly1305_ietf_ABYTES;
constexpr size_t kStreamHeaderBytes = crypto_secretstream_xchacha20poly1305_HEADERBYTES;
constexpr size_t kChunkOverhead = crypto_secretstream_xchacha20poly1305_ABYTES;

static_assert(kDekBytes == 32, "unexpected secretstream key size");
static_assert(kWrapNonceBytes == 24, "unexpected AEAD nonce size");
static_assert(kWrappedDekBytes == 48, "unexpected wrapped key size");
static_assert(kStreamHeaderBytes == 24, "unexpected secretstream header size");
static_assert(kChunkOverhead == 17, "unexpected secretstream overhead");
static_assert(crypto_aead_xchacha20poly1305_ietf_KEYBYTES == PV_VMK_BYTES, "VMK size");
static_assert(kOffWrapNonce + kWrapNonceBytes == kOffWrappedDek, "layout");
static_assert(kOffWrappedDek + kWrappedDekBytes == kOffStreamHeader, "layout");
static_assert(kOffStreamHeader + kStreamHeaderBytes == kHeaderBytes, "layout");

struct Header {
    uint16_t version;
    uint16_t suite;
    uint32_t chunk_size;
    uint8_t object_id[PV_OBJECT_ID_BYTES];
    uint8_t wrap_nonce[kWrapNonceBytes];
    uint8_t wrapped_dek[kWrappedDekBytes];
    uint8_t stream_header[kStreamHeaderBytes];
};

void serialize_header(const Header& h, uint8_t* out);

// Checks magic, version, suite, and chunk_size range, in that order.
int parse_header(const uint8_t* in, Header& h);

bool chunk_size_in_range(uint32_t chunk_size);

}  // namespace pv

#endif
