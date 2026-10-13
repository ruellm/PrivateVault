#include "format.h"

namespace pv {

bool chunk_size_in_range(uint32_t chunk_size) {
    return chunk_size >= PV_MIN_CHUNK_SIZE && chunk_size <= PV_MAX_CHUNK_SIZE;
}

void serialize_header(const Header& h, uint8_t* out) {
    std::memcpy(out + kOffMagic, kMagic, sizeof(kMagic));
    put_u16(out + kOffVersion, h.version);
    put_u16(out + kOffSuite, h.suite);
    put_u32(out + kOffChunkSize, h.chunk_size);
    std::memcpy(out + kOffObjectId, h.object_id, PV_OBJECT_ID_BYTES);
    std::memcpy(out + kOffWrapNonce, h.wrap_nonce, kWrapNonceBytes);
    std::memcpy(out + kOffWrappedDek, h.wrapped_dek, kWrappedDekBytes);
    std::memcpy(out + kOffStreamHeader, h.stream_header, kStreamHeaderBytes);
}

int parse_header(const uint8_t* in, Header& h) {
    if (std::memcmp(in + kOffMagic, kMagic, sizeof(kMagic)) != 0) {
        return PV_ERR_MALFORMED_HEADER;
    }
    h.version = get_u16(in + kOffVersion);
    if (h.version != PV_FORMAT_VERSION) {
        return PV_ERR_UNSUPPORTED_VERSION;
    }
    h.suite = get_u16(in + kOffSuite);
    if (h.suite != PV_SUITE_SECRETSTREAM_XCHACHA20) {
        return PV_ERR_UNSUPPORTED_SUITE;
    }
    h.chunk_size = get_u32(in + kOffChunkSize);
    if (!chunk_size_in_range(h.chunk_size)) {
        return PV_ERR_CHUNK_SIZE_OUT_OF_RANGE;
    }
    std::memcpy(h.object_id, in + kOffObjectId, PV_OBJECT_ID_BYTES);
    std::memcpy(h.wrap_nonce, in + kOffWrapNonce, kWrapNonceBytes);
    std::memcpy(h.wrapped_dek, in + kOffWrappedDek, kWrappedDekBytes);
    std::memcpy(h.stream_header, in + kOffStreamHeader, kStreamHeaderBytes);
    return PV_OK;
}

}  // namespace pv
