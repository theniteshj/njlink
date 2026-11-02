// SPDX-License-Identifier: GPL-3.0-or-later
#if defined(TEST_BUILTIN) || defined(TEST_PLUGIN)
#ifdef TEST_BUILTIN
#include "../cpp/linux_video_builtin.h"
#endif
#include "../cpp/linux_video_codec.h"

#ifndef TEST_LABEL
#define TEST_LABEL "test"
#endif
namespace { int object; }

extern "C" void* njlink_video_decoder_create(bool, char*, size_t)
{
#ifdef TEST_AVAILABLE
    return &object;
#else
    return nullptr;
#endif
}
extern "C" void njlink_video_decoder_destroy(void*) {}
extern "C" bool njlink_video_decoder_next_device(void* p, char*, size_t) { return p == &object; }
extern "C" const char* njlink_video_decoder_backend(const void*) { return TEST_LABEL; }
extern "C" int njlink_video_decoder_send(void* p, const uint8_t*, size_t n, char*, size_t)
{ return p == &object ? static_cast<int>(n) : -1; }
extern "C" int njlink_video_decoder_receive(void*, NJLinkDecodedNv12*, char*, size_t) { return 0; }
extern "C" void njlink_video_decoder_flush(void*) {}
extern "C" void* njlink_video_encoder_create(bool, int, int, int, int, char*, size_t)
{
#ifdef TEST_AVAILABLE
    return &object;
#else
    return nullptr;
#endif
}
extern "C" void njlink_video_encoder_destroy(void*) {}
extern "C" bool njlink_video_encoder_next_device(void* p, char*, size_t) { return p == &object; }
extern "C" const char* njlink_video_encoder_backend(const void*) { return TEST_LABEL; }
extern "C" int njlink_video_encoder_send(void* p, const uint8_t*, size_t n, int64_t, char*, size_t)
{ return p == &object ? static_cast<int>(n) : -1; }
extern "C" int njlink_video_encoder_receive(void*, const uint8_t**, size_t*, bool*, char*, size_t) { return 0; }
extern "C" void njlink_video_encoder_request_keyframe(void*) {}
extern "C" bool njlink_video_encoder_set_bitrate(void* p, int bitrate) { return p == &object && bitrate == 123; }

#else
#include "../cpp/linux_video_codec.h"
#include <cassert>
#include <cstring>

int main(int argc, char** argv)
{
    char error[128]{};
    auto* encoder = njlink_video_encoder_create(false, 320, 240, 30, 123, error, sizeof(error));
    auto* decoder = njlink_video_decoder_create(false, error, sizeof(error));
    if (argc > 1 && std::strcmp(argv[1], "none") == 0) {
        assert(!encoder && !decoder);
        return 0;
    }
    assert(encoder && decoder);
    const char* expected = argc > 1 ? argv[1] : "test";
    assert(std::strcmp(njlink_video_encoder_backend(encoder), expected) == 0);
    assert(std::strcmp(njlink_video_decoder_backend(decoder), expected) == 0);
    const uint8_t data[] = {1, 2, 3};
    assert(njlink_video_encoder_send(encoder, data, sizeof(data), 0, error, sizeof(error)) == 3);
    assert(njlink_video_decoder_send(decoder, data, sizeof(data), error, sizeof(error)) == 3);
    NJLinkDecodedNv12 frame{};
    assert(njlink_video_decoder_receive(decoder, &frame, error, sizeof(error)) == 0);
    const uint8_t* packet = nullptr;
    size_t length = 0;
    bool keyframe = false;
    assert(njlink_video_encoder_receive(encoder, &packet, &length, &keyframe, error, sizeof(error)) == 0);
    assert(njlink_video_encoder_set_bitrate(encoder, 123));
    assert(njlink_video_encoder_next_device(encoder, error, sizeof(error)));
    assert(njlink_video_decoder_next_device(decoder, error, sizeof(error)));
    njlink_video_encoder_request_keyframe(encoder);
    njlink_video_decoder_flush(decoder);
    njlink_video_encoder_destroy(encoder);
    njlink_video_decoder_destroy(decoder);
}
#endif
