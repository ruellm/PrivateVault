// Audio queue: encoded audio frames in one guarded buffer, oldest first,
// each stored as a 4-byte length, an 8-byte time, and the bytes. Frames
// leave from the head; the used range slides down when the head has moved
// past half the buffer. Rules in pv.h and docs/PHASE3F_DESIGN.md Section 4.2.
#include "common.h"

struct pv_audio_queue {
    uint8_t* buf;
    size_t cap;
    size_t head;
    size_t tail;
    uint32_t frames;
    uint64_t last_pts;
};

namespace pv {
namespace {

constexpr size_t kFrameHeader = 12;

uint32_t frame_len(const pv_audio_queue* q, size_t at) {
    return get_u32(q->buf + at);
}

uint64_t frame_pts(const pv_audio_queue* q, size_t at) {
    return get_u64(q->buf + at + 4);
}

// Removes the head frame, wiping its bytes.
void pop(pv_audio_queue* q) {
    const size_t n = kFrameHeader + frame_len(q, q->head);
    sodium_memzero(q->buf + q->head, n);
    q->head += n;
    q->frames--;
    if (q->frames == 0) {
        q->head = 0;
        q->tail = 0;
    }
}

void compact(pv_audio_queue* q) {
    if (q->head == 0) {
        return;
    }
    const size_t used = q->tail - q->head;
    std::memmove(q->buf, q->buf + q->head, used);
    sodium_memzero(q->buf + used, q->tail - used);
    q->head = 0;
    q->tail = used;
}

}  // namespace
}  // namespace pv

extern "C" {

int pv_audio_queue_open(size_t cap, pv_audio_queue** out) {
    int rc = pv::ensure_init();
    if (rc != PV_OK) {
        return rc;
    }
    if (!out) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    *out = nullptr;
    if (cap < 4096) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    void* mem = nullptr;
    rc = pv_secure_alloc(cap, &mem);
    if (rc != PV_OK) {
        return rc;
    }
    pv_audio_queue* q = new (std::nothrow) pv_audio_queue();
    if (!q) {
        pv_secure_free(mem, cap);
        return PV_ERR_OUT_OF_MEMORY;
    }
    q->buf = static_cast<uint8_t*>(mem);
    q->cap = cap;
    q->head = 0;
    q->tail = 0;
    q->frames = 0;
    q->last_pts = 0;
    *out = q;
    return PV_OK;
}

int pv_audio_queue_push(pv_audio_queue* q, const uint8_t* sample, size_t len, uint64_t pts_us) {
    if (!q || !sample || len == 0 || len > 0xffffffffu) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    if (q->frames > 0 && pts_us < q->last_pts) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    const size_t need = pv::kFrameHeader + len;
    if (q->cap - q->tail < need) {
        pv::compact(q);
        if (q->cap - q->tail < need) {
            return PV_ERR_AUDIO_QUEUE_FULL;
        }
    }
    pv::put_u32(q->buf + q->tail, static_cast<uint32_t>(len));
    pv::put_u64(q->buf + q->tail + 4, pts_us);
    std::memcpy(q->buf + q->tail + pv::kFrameHeader, sample, len);
    q->tail += need;
    q->frames++;
    q->last_pts = pts_us;
    return PV_OK;
}

int pv_audio_queue_discard_before(pv_audio_queue* q, uint64_t pts_us, uint32_t* dropped_out) {
    if (!q) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    uint32_t dropped = 0;
    while (q->frames > 0 && pv::frame_pts(q, q->head) < pts_us) {
        pv::pop(q);
        dropped++;
    }
    if (dropped_out) {
        *dropped_out = dropped;
    }
    return PV_OK;
}

int pv_audio_queue_move(pv_audio_queue* q, pv_segment_writer* w, uint64_t base_us, uint64_t until_us,
                        uint32_t* moved_out, uint32_t* clamped_out) {
    if (!q || !w) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    uint32_t moved = 0;
    uint32_t clamped = 0;
    int rc = PV_OK;
    while (q->frames > 0) {
        const uint64_t pts = pv::frame_pts(q, q->head);
        if (pts >= until_us) {
            break;
        }
        const uint64_t rel = pts < base_us ? 0 : pts - base_us;
        rc = pv_segment_writer_add_audio(w, q->buf + q->head + pv::kFrameHeader, pv::frame_len(q, q->head), rel);
        if (rc != PV_OK) {
            break;
        }
        if (pts < base_us) {
            clamped++;
        }
        moved++;
        pv::pop(q);
    }
    if (q->frames > 0 && q->head > q->cap / 2) {
        pv::compact(q);
    }
    if (moved_out) {
        *moved_out = moved;
    }
    if (clamped_out) {
        *clamped_out = clamped;
    }
    return rc;
}

int pv_audio_queue_stats(const pv_audio_queue* q, size_t* bytes_out, uint32_t* frames_out, uint64_t* head_pts_out) {
    if (!q) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    if (bytes_out) {
        *bytes_out = q->tail - q->head;
    }
    if (frames_out) {
        *frames_out = q->frames;
    }
    if (head_pts_out) {
        *head_pts_out = q->frames > 0 ? pv::frame_pts(q, q->head) : 0;
    }
    return PV_OK;
}

void pv_audio_queue_close(pv_audio_queue* q) {
    if (!q) {
        return;
    }
    pv_secure_free(q->buf, q->cap);
    delete q;
}

}
