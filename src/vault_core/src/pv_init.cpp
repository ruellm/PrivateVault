#include "common.h"

namespace pv {

int ensure_init() {
    return sodium_init() < 0 ? PV_ERR_INIT : PV_OK;
}

}  // namespace pv

extern "C" {

int pv_init(void) {
    return pv::ensure_init();
}

const char* pv_version_string(void) {
    return "0.1.0";
}

const char* pv_sodium_version_string(void) {
    return sodium_version_string();
}

const char* pv_error_name(int code) {
    switch (code) {
        case PV_OK: return "PV_OK";
        case PV_ERR_INIT: return "PV_ERR_INIT";
        case PV_ERR_INVALID_ARGUMENT: return "PV_ERR_INVALID_ARGUMENT";
        case PV_ERR_IO: return "PV_ERR_IO";
        case PV_ERR_DEST_EXISTS: return "PV_ERR_DEST_EXISTS";
        case PV_ERR_MALFORMED_HEADER: return "PV_ERR_MALFORMED_HEADER";
        case PV_ERR_UNSUPPORTED_VERSION: return "PV_ERR_UNSUPPORTED_VERSION";
        case PV_ERR_UNSUPPORTED_SUITE: return "PV_ERR_UNSUPPORTED_SUITE";
        case PV_ERR_CHUNK_SIZE_OUT_OF_RANGE: return "PV_ERR_CHUNK_SIZE_OUT_OF_RANGE";
        case PV_ERR_WRONG_VMK: return "PV_ERR_WRONG_VMK";
        case PV_ERR_AUTH_FAILED: return "PV_ERR_AUTH_FAILED";
        case PV_ERR_TRUNCATED: return "PV_ERR_TRUNCATED";
        case PV_ERR_TRAILING_DATA: return "PV_ERR_TRAILING_DATA";
        case PV_ERR_MALFORMED_CHUNK: return "PV_ERR_MALFORMED_CHUNK";
        case PV_ERR_ENVELOPE_MALFORMED: return "PV_ERR_ENVELOPE_MALFORMED";
        case PV_ERR_ENVELOPE_UNSUPPORTED: return "PV_ERR_ENVELOPE_UNSUPPORTED";
        case PV_ERR_ENVELOPE_KDF_PARAMS: return "PV_ERR_ENVELOPE_KDF_PARAMS";
        case PV_ERR_ENVELOPE_UNWRAP_FAILED: return "PV_ERR_ENVELOPE_UNWRAP_FAILED";
        case PV_ERR_OUT_OF_MEMORY: return "PV_ERR_OUT_OF_MEMORY";
        case PV_ERR_INTERNAL: return "PV_ERR_INTERNAL";
        case PV_ERR_NOT_FOUND: return "PV_ERR_NOT_FOUND";
        case PV_ERR_ITEM_MALFORMED: return "PV_ERR_ITEM_MALFORMED";
        case PV_ERR_ITEM_UNSUPPORTED: return "PV_ERR_ITEM_UNSUPPORTED";
        case PV_ERR_NOT_ITEM: return "PV_ERR_NOT_ITEM";
        case PV_ERR_ENVELOPE_NO_SLOT: return "PV_ERR_ENVELOPE_NO_SLOT";
        case PV_ERR_BUFFER_TOO_SMALL: return "PV_ERR_BUFFER_TOO_SMALL";
        case PV_ERR_CANCELLED: return "PV_ERR_CANCELLED";
        default: return "PV_ERR_UNKNOWN";
    }
}

int pv_vmk_generate(uint8_t* out, size_t out_len) {
    int rc = pv::ensure_init();
    if (rc != PV_OK) {
        return rc;
    }
    if (!out || out_len != PV_VMK_BYTES) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    randombytes_buf(out, out_len);
    return PV_OK;
}

}
