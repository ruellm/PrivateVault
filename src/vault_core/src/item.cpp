// Media item header codec and the item sink. Layout in docs/PHASE2_DESIGN.md
// Section 3 and in pv.h.
#include "common.h"

namespace pv {
namespace {

constexpr uint8_t kItemMagic[8] = {'P', 'V', 'I', 'T', 'E', 'M', '0', '1'};
constexpr size_t kOffItemMagic = 0;
constexpr size_t kOffItemVersion = 8;
constexpr size_t kOffHeaderLen = 10;
constexpr size_t kOffMediaType = 12;
constexpr size_t kOffRotation = 14;
constexpr size_t kOffWidth = 16;
constexpr size_t kOffHeight = 20;
constexpr size_t kOffCreated = 24;
constexpr size_t kOffPayloadLen = 32;
constexpr size_t kOffReserved = 40;
static_assert(kOffReserved + 8 == PV_ITEM_HEADER_BYTES, "item header layout");

// The bytes needed before header_len can be read.
constexpr size_t kItemPrefix = kOffHeaderLen + 2;

bool rotation_ok(uint16_t r) {
    return r == 0 || r == 90 || r == 180 || r == 270;
}

bool media_type_ok(uint16_t t) {
    return t == PV_MEDIA_JPEG || t == PV_MEDIA_VIDEO_SEGMENT || t == PV_MEDIA_VIDEO_INDEX;
}

bool header_len_ok(uint16_t n) {
    return n >= PV_ITEM_HEADER_BYTES && n <= PV_ITEM_HEADER_MAX_BYTES;
}

// Field rules shared by encode and decode.
int check_fields(const pv_item_header& h) {
    if (h.item_version != PV_ITEM_VERSION) {
        return PV_ERR_ITEM_UNSUPPORTED;
    }
    if (!header_len_ok(h.header_len)) {
        return PV_ERR_ITEM_MALFORMED;
    }
    if (!media_type_ok(h.media_type)) {
        return PV_ERR_ITEM_UNSUPPORTED;
    }
    if (!rotation_ok(h.rotation_degrees)) {
        return PV_ERR_ITEM_MALFORMED;
    }
    return PV_OK;
}

// Item sink state. The header bytes are kept in `head`; everything after
// header_len bytes goes to the payload sink. `verdict` holds the code decided
// while the stream flowed (0 while undecided).
struct ItemSink {
    pv_sink* payload;
    void (*payload_abort)(pv_sink*);
    bool payload_aborted;
    std::vector<uint8_t> head;
    size_t header_len;  // 0 until read from the stream
    int verdict;
    uint64_t seen;

    void abort_payload() {
        if (!payload_aborted) {
            payload_aborted = true;
            if (payload && payload_abort) {
                payload_abort(payload);
            }
        }
    }
};

int item_write(void* ctx, const uint8_t* buf, size_t n) {
    ItemSink* s = static_cast<ItemSink*>(ctx);
    s->seen += n;
    if (s->verdict != 0) {
        return 0;  // decided already; the rest of the stream is discarded
    }
    while (n > 0) {
        if (s->header_len == 0) {
            // Collect the 12-byte prefix, then read the magic and header_len.
            size_t want = kItemPrefix - s->head.size();
            size_t take = n < want ? n : want;
            s->head.insert(s->head.end(), buf, buf + take);
            buf += take;
            n -= take;
            if (s->head.size() < kItemPrefix) {
                return 0;
            }
            if (std::memcmp(s->head.data(), kItemMagic, sizeof(kItemMagic)) != 0) {
                s->verdict = PV_ERR_NOT_ITEM;
                s->abort_payload();
                return 0;
            }
            uint16_t hl = get_u16(s->head.data() + kOffHeaderLen);
            if (!header_len_ok(hl)) {
                s->verdict = PV_ERR_ITEM_MALFORMED;
                s->abort_payload();
                return 0;
            }
            s->header_len = hl;
            continue;
        }
        if (s->head.size() < s->header_len) {
            size_t want = s->header_len - s->head.size();
            size_t take = n < want ? n : want;
            s->head.insert(s->head.end(), buf, buf + take);
            buf += take;
            n -= take;
            continue;
        }
        // Header complete: the rest is payload.
        if (write_all(s->payload, buf, n) != PV_OK) {
            return -1;
        }
        return 0;
    }
    return 0;
}

}  // namespace
}  // namespace pv

extern "C" {

int pv_item_header_encode(const pv_item_header* h, uint8_t* out, size_t out_cap,
                          size_t* out_len) {
    if (!h || !out || out_cap < PV_ITEM_HEADER_BYTES) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    int rc = pv::check_fields(*h);
    if (rc != PV_OK) {
        return rc;
    }
    if (h->header_len != PV_ITEM_HEADER_BYTES) {
        // Writers emit the fixed 48-byte form only.
        return PV_ERR_ITEM_MALFORMED;
    }
    std::memset(out, 0, PV_ITEM_HEADER_BYTES);
    std::memcpy(out + pv::kOffItemMagic, pv::kItemMagic, sizeof(pv::kItemMagic));
    pv::put_u16(out + pv::kOffItemVersion, h->item_version);
    pv::put_u16(out + pv::kOffHeaderLen, h->header_len);
    pv::put_u16(out + pv::kOffMediaType, h->media_type);
    pv::put_u16(out + pv::kOffRotation, h->rotation_degrees);
    pv::put_u32(out + pv::kOffWidth, h->width);
    pv::put_u32(out + pv::kOffHeight, h->height);
    pv::put_u64(out + pv::kOffCreated, h->created_unix_ms);
    pv::put_u64(out + pv::kOffPayloadLen, h->payload_len);
    if (out_len) {
        *out_len = PV_ITEM_HEADER_BYTES;
    }
    return PV_OK;
}

int pv_item_header_decode(const uint8_t* buf, size_t len, pv_item_header* out) {
    if (!buf || !out) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    std::memset(out, 0, sizeof(*out));
    if (len < sizeof(pv::kItemMagic) ||
        std::memcmp(buf + pv::kOffItemMagic, pv::kItemMagic, sizeof(pv::kItemMagic)) != 0) {
        return PV_ERR_NOT_ITEM;
    }
    if (len < PV_ITEM_HEADER_BYTES) {
        return PV_ERR_ITEM_MALFORMED;
    }
    pv_item_header h{};
    h.item_version = pv::get_u16(buf + pv::kOffItemVersion);
    h.header_len = pv::get_u16(buf + pv::kOffHeaderLen);
    h.media_type = pv::get_u16(buf + pv::kOffMediaType);
    h.rotation_degrees = pv::get_u16(buf + pv::kOffRotation);
    h.width = pv::get_u32(buf + pv::kOffWidth);
    h.height = pv::get_u32(buf + pv::kOffHeight);
    h.created_unix_ms = pv::get_u64(buf + pv::kOffCreated);
    h.payload_len = pv::get_u64(buf + pv::kOffPayloadLen);
    int rc = pv::check_fields(h);
    if (rc != PV_OK) {
        return rc;
    }
    if (h.header_len > len) {
        return PV_ERR_ITEM_MALFORMED;
    }
    *out = h;
    return PV_OK;
}

int pv_item_header_check_len(const pv_item_header* h, uint64_t plaintext_len) {
    if (!h) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    uint64_t total = h->payload_len + h->header_len;
    if (total < h->payload_len || total != plaintext_len) {
        return PV_ERR_ITEM_MALFORMED;
    }
    return PV_OK;
}

int pv_sink_item_new(pv_sink* payload_sink, void (*payload_abort)(pv_sink*), pv_sink* out) {
    if (!payload_sink || !payload_sink->write || !out) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    pv::ItemSink* s = new (std::nothrow) pv::ItemSink{};
    if (!s) {
        return PV_ERR_OUT_OF_MEMORY;
    }
    s->payload = payload_sink;
    s->payload_abort = payload_abort;
    try {
        s->head.reserve(PV_ITEM_HEADER_BYTES);
    } catch (...) {
        delete s;
        return PV_ERR_OUT_OF_MEMORY;
    }
    out->ctx = s;
    out->write = pv::item_write;
    return PV_OK;
}

int pv_sink_item_finish(pv_sink* sink, uint64_t plaintext_len, pv_item_header* header_out) {
    if (!sink || !sink->ctx || !header_out) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    pv::ItemSink* s = static_cast<pv::ItemSink*>(sink->ctx);
    std::memset(header_out, 0, sizeof(*header_out));
    int rc = s->verdict;
    if (rc == 0) {
        if (s->header_len == 0) {
            // Fewer than 12 bytes arrived: no magic to classify.
            rc = PV_ERR_NOT_ITEM;
        } else if (s->head.size() < s->header_len) {
            rc = PV_ERR_ITEM_MALFORMED;
        } else {
            pv_item_header h{};
            rc = pv_item_header_decode(s->head.data(), s->head.size(), &h);
            if (rc == PV_OK) {
                rc = pv_item_header_check_len(&h, plaintext_len);
            }
            if (rc == PV_OK && s->seen != plaintext_len) {
                rc = PV_ERR_ITEM_MALFORMED;
            }
            if (rc == PV_OK) {
                *header_out = h;
            }
        }
        if (rc != PV_OK) {
            s->abort_payload();
        }
    }
    delete s;
    sink->ctx = nullptr;
    sink->write = nullptr;
    return rc;
}

void pv_sink_item_abort(pv_sink* sink) {
    if (!sink || !sink->ctx) {
        return;
    }
    pv::ItemSink* s = static_cast<pv::ItemSink*>(sink->ctx);
    s->abort_payload();
    delete s;
    sink->ctx = nullptr;
    sink->write = nullptr;
}

}
