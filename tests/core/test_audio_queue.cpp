// Audio queue (docs/PHASE3F_DESIGN.md Section 4.2): frames in, frames out
// into a segment writer by time, the clamp for a late frame, the discard
// before a time, the head time, the cap, the compaction, arguments, and
// the error name.
#include "test_support.h"

#include <cstdio>

namespace {

const uint8_t kSps[] = {0x67, 0x64, 0x00, 0x28, 0xac, 0xd9, 0x40, 0x78, 0x02, 0x27, 0xe5, 0xc0, 0x44,
                        0x00, 0x00, 0x03, 0x00, 0x04, 0x00, 0x00, 0x03, 0x00, 0xf0, 0x3c, 0x60, 0xc6, 0x58};
const uint8_t kPps[] = {0x68, 0xeb, 0xe3, 0xcb, 0x22, 0xc0};
const uint8_t kAsc[] = {0x11, 0x90};

pv_segment_audio audio() {
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

pv_segment_writer* open_writer() {
    pv_segment_audio a = audio();
    pv_segment_writer* w = nullptr;
    CHECK_EQ(pv_segment_writer_open_av(1280, 720, 0, kSps, sizeof(kSps), kPps, sizeof(kPps), &a, 1 << 20, &w), PV_OK);
    const uint8_t key[] = {0, 0, 0, 1, 0x65, 0x7f, 0x7f, 0x7f};
    CHECK_EQ(pv_segment_writer_add(w, key, sizeof(key), 0, 1), PV_OK);
    return w;
}

ts::Bytes frame(size_t n, uint8_t fill) {
    return ts::Bytes(n, fill);
}

void stats(pv_audio_queue* q, size_t bytes, uint32_t frames, uint64_t head) {
    size_t b = 0;
    uint32_t f = 0;
    uint64_t h = 0;
    CHECK_EQ(pv_audio_queue_stats(q, &b, &f, &h), PV_OK);
    CHECK_EQ(b, bytes);
    CHECK_EQ(f, frames);
    CHECK_EQ(h, head);
}

void push_and_move() {
    pv_audio_queue* q = nullptr;
    CHECK_EQ(pv_audio_queue_open(1 << 16, &q), PV_OK);
    stats(q, 0, 0, 0);
    ts::Bytes a = frame(300, 1), b = frame(301, 2), c = frame(302, 3), d = frame(303, 4);
    CHECK_EQ(pv_audio_queue_push(q, a.data(), a.size(), 1000), PV_OK);
    CHECK_EQ(pv_audio_queue_push(q, b.data(), b.size(), 22333), PV_OK);
    CHECK_EQ(pv_audio_queue_push(q, c.data(), c.size(), 43666), PV_OK);
    CHECK_EQ(pv_audio_queue_push(q, d.data(), d.size(), 65000), PV_OK);
    // Times must not decrease.
    CHECK_EQ(pv_audio_queue_push(q, d.data(), d.size(), 64999), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_audio_queue_push(q, d.data(), d.size(), 65000), PV_OK);
    stats(q, 5 * 12 + 300 + 301 + 302 + 303 + 303, 5, 1000);
    // Move the frames below 43666 (two of them) timed from 500.
    pv_segment_writer* w = open_writer();
    uint32_t moved = 0, clamped = 0;
    CHECK_EQ(pv_audio_queue_move(q, w, 500, 43666, &moved, &clamped), PV_OK);
    CHECK_EQ(moved, 2u);
    CHECK_EQ(clamped, 0u);
    stats(q, 3 * 12 + 302 + 303 + 303, 3, 43666);
    uint32_t samples = 0;
    uint64_t tick = 0;
    CHECK_EQ(pv_segment_writer_audio_stats(w, &samples, &tick), PV_OK);
    CHECK_EQ(samples, 2u);
    CHECK_EQ(tick, 24u);   // (1000 - 500) us at 48 kHz
    // Nothing below 43666 is left; a move with the same bound moves nothing.
    CHECK_EQ(pv_audio_queue_move(q, w, 500, 43666, &moved, &clamped), PV_OK);
    CHECK_EQ(moved, 0u);
    // A new segment starting at 50000: the frame at 43666 is late and goes
    // in at time 0, counted as clamped; the two at 65000 follow.
    pv_segment_writer* w2 = open_writer();
    CHECK_EQ(pv_audio_queue_move(q, w2, 50000, 1000000, &moved, &clamped), PV_OK);
    CHECK_EQ(moved, 3u);
    CHECK_EQ(clamped, 1u);
    stats(q, 0, 0, 0);
    CHECK_EQ(pv_segment_writer_audio_stats(w2, &samples, &tick), PV_OK);
    CHECK_EQ(samples, 3u);
    CHECK_EQ(tick, 0u);
    // The written bytes are the frames, in order, behind the video sample.
    CHECK_EQ(pv_segment_writer_finish(w2, 100000), PV_OK);
    const uint8_t* data = nullptr;
    size_t len = 0;
    CHECK_EQ(pv_segment_writer_data(w2, &data, &len), PV_OK);
    ts::Bytes image(data, data + len);
    ts::Bytes expect = c;
    expect.insert(expect.end(), d.begin(), d.end());
    expect.insert(expect.end(), d.begin(), d.end());
    CHECK(len > expect.size());
    CHECK(ts::Bytes(image.end() - static_cast<ptrdiff_t>(expect.size()), image.end()) == expect);
    pv_segment_writer_close(w);
    pv_segment_writer_close(w2);
    pv_audio_queue_close(q);
}

void discard_and_errors() {
    pv_audio_queue* q = nullptr;
    CHECK_EQ(pv_audio_queue_open(1 << 16, &q), PV_OK);
    ts::Bytes a = frame(100, 9);
    for (uint64_t t = 0; t < 10; ++t) CHECK_EQ(pv_audio_queue_push(q, a.data(), a.size(), t * 21333), PV_OK);
    uint32_t dropped = 0;
    CHECK_EQ(pv_audio_queue_discard_before(q, 3 * 21333, &dropped), PV_OK);
    CHECK_EQ(dropped, 3u);
    stats(q, 7 * 112, 7, 3 * 21333);
    CHECK_EQ(pv_audio_queue_discard_before(q, 0, &dropped), PV_OK);
    CHECK_EQ(dropped, 0u);
    // A writer error stops the move and leaves the frame queued: no audio
    // track in this writer.
    pv_segment_writer* plain = nullptr;
    CHECK_EQ(pv_segment_writer_open(1280, 720, 0, kSps, sizeof(kSps), kPps, sizeof(kPps), 1 << 20, &plain), PV_OK);
    const uint8_t key[] = {0, 0, 0, 1, 0x65, 0x7f, 0x7f, 0x7f};
    CHECK_EQ(pv_segment_writer_add(plain, key, sizeof(key), 0, 1), PV_OK);
    uint32_t moved = 0, clamped = 0;
    CHECK_EQ(pv_audio_queue_move(q, plain, 0, 1000000, &moved, &clamped), PV_ERR_SEGMENT_STATE);
    CHECK_EQ(moved, 0u);
    stats(q, 7 * 112, 7, 3 * 21333);
    pv_segment_writer_close(plain);
    // Arguments.
    pv_audio_queue* out = nullptr;
    CHECK_EQ(pv_audio_queue_open(1 << 16, nullptr), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_audio_queue_open(100, &out), PV_ERR_INVALID_ARGUMENT);
    CHECK(out == nullptr);
    CHECK_EQ(pv_audio_queue_push(nullptr, a.data(), a.size(), 0), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_audio_queue_push(q, nullptr, 1, 0), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_audio_queue_push(q, a.data(), 0, 0), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_audio_queue_discard_before(nullptr, 0, nullptr), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_audio_queue_move(nullptr, plain, 0, 0, nullptr, nullptr), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_audio_queue_move(q, nullptr, 0, 0, nullptr, nullptr), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_audio_queue_stats(nullptr, nullptr, nullptr, nullptr), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_audio_queue_stats(q, nullptr, nullptr, nullptr), PV_OK);
    pv_audio_queue_close(q);
    pv_audio_queue_close(nullptr);
    CHECK_EQ(std::string(pv_error_name(PV_ERR_AUDIO_QUEUE_FULL)), "PV_ERR_AUDIO_QUEUE_FULL");
}

// The cap, and the slide of the used range when the head has moved.
void cap_and_compaction() {
    pv_audio_queue* q = nullptr;
    CHECK_EQ(pv_audio_queue_open(4096, &q), PV_OK);
    ts::Bytes a = frame(1000, 5);
    // Four frames of 1012 bytes fill 4048 of 4096; the fifth does not fit.
    for (uint64_t t = 0; t < 4; ++t) CHECK_EQ(pv_audio_queue_push(q, a.data(), a.size(), t * 21333), PV_OK);
    CHECK_EQ(pv_audio_queue_push(q, a.data(), a.size(), 4 * 21333), PV_ERR_AUDIO_QUEUE_FULL);
    stats(q, 4048, 4, 0);
    // Two out, two in: the used range slides down and the push fits.
    uint32_t dropped = 0;
    CHECK_EQ(pv_audio_queue_discard_before(q, 2 * 21333, &dropped), PV_OK);
    CHECK_EQ(dropped, 2u);
    CHECK_EQ(pv_audio_queue_push(q, a.data(), a.size(), 4 * 21333), PV_OK);
    CHECK_EQ(pv_audio_queue_push(q, a.data(), a.size(), 5 * 21333), PV_OK);
    CHECK_EQ(pv_audio_queue_push(q, a.data(), a.size(), 6 * 21333), PV_ERR_AUDIO_QUEUE_FULL);
    stats(q, 4048, 4, 2 * 21333);
    // Everything out through a writer, in order.
    pv_segment_writer* w = open_writer();
    uint32_t moved = 0, clamped = 0;
    CHECK_EQ(pv_audio_queue_move(q, w, 0, 1000000, &moved, &clamped), PV_OK);
    CHECK_EQ(moved, 4u);
    stats(q, 0, 0, 0);
    uint32_t samples = 0;
    uint64_t tick = 0;
    CHECK_EQ(pv_segment_writer_audio_stats(w, &samples, &tick), PV_OK);
    CHECK_EQ(samples, 4u);
    CHECK_EQ(tick, 2048u);   // 2 x 21333 us at 48 kHz, rounded
    // Many small frames through a half-used buffer: the slide keeps the
    // order and the bytes.
    ts::Bytes s = frame(50, 7);
    const uint8_t key[] = {0, 0, 0, 1, 0x65, 0x7f, 0x7f, 0x7f};
    for (uint64_t t = 0; t < 2000; ++t) {
        // A key frame every 400 frames keeps each fragment under its audio bound.
        // Times continue after the six frames the writer already holds.
        const uint64_t at = (t + 6) * 21333;
        if (t > 0 && t % 400 == 0) CHECK_EQ(pv_segment_writer_add(w, key, sizeof(key), at, 1), PV_OK);
        s[0] = static_cast<uint8_t>(t);
        CHECK_EQ(pv_audio_queue_push(q, s.data(), s.size(), at), PV_OK);
        if (t % 3 == 2) {
            CHECK_EQ(pv_audio_queue_move(q, w, 0, at + 1, &moved, &clamped), PV_OK);
            CHECK_EQ(moved, 3u);
        }
    }
    CHECK_EQ(pv_audio_queue_move(q, w, 0, ~0ull, &moved, &clamped), PV_OK);
    CHECK_EQ(moved, 2u);
    stats(q, 0, 0, 0);
    CHECK_EQ(pv_segment_writer_audio_stats(w, &samples, &tick), PV_OK);
    CHECK_EQ(samples, 2004u);
    CHECK_EQ(pv_segment_writer_finish(w, 2006 * 21333), PV_OK);
    const uint8_t* data = nullptr;
    size_t len = 0;
    CHECK_EQ(pv_segment_writer_data(w, &data, &len), PV_OK);
    // The last fragment ends the file with its 400 frames: the first byte counts up.
    for (size_t i = 0; i < 400; ++i) {
        CHECK_EQ(data[len - 400 * 50 + i * 50], static_cast<uint8_t>(1600 + i));
    }
    pv_segment_writer_close(w);
    pv_audio_queue_close(q);
}

}  // namespace

int main() {
    CHECK_EQ(pv_init(), PV_OK);
    push_and_move();
    discard_and_errors();
    cap_and_compaction();
    std::printf("audio queue ok\n");
    return 0;
}
