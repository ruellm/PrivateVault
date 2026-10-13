#include "password.h"

#include <sodium.h>

#include <cstdio>
#include <cstring>

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#else
#include <termios.h>
#include <unistd.h>
#endif

namespace pvd {

namespace {
constexpr size_t kMaxPassword = 1024;
}

Password::Password() : buf_(nullptr), len_(0), cap_(kMaxPassword) {
    buf_ = static_cast<char*>(sodium_malloc(cap_));
    if (buf_) {
        std::memset(buf_, 0, cap_);
    }
}

Password::~Password() {
    if (buf_) {
        sodium_free(buf_);
    }
}

bool Password::equals(const Password& other) const {
    if (!buf_ || !other.buf_ || len_ != other.len_) {
        return false;
    }
    return len_ == 0 || sodium_memcmp(buf_, other.buf_, len_) == 0;
}

void Password::push(char c) {
    if (buf_ && len_ + 1 < cap_) {
        buf_[len_++] = c;
    }
}

bool Password::read_stdin() {
    if (!buf_) {
        return false;
    }
    len_ = 0;
    int c;
    bool any = false;
    while ((c = std::fgetc(stdin)) != EOF) {
        any = true;
        if (c == '\n') {
            break;
        }
        push(static_cast<char>(c));
    }
    while (len_ > 0 && buf_[len_ - 1] == '\r') {
        buf_[--len_] = '\0';
    }
    return any;
}

#ifdef _WIN32

bool Password::read_console(const char* prompt) {
    if (!buf_) {
        return false;
    }
    len_ = 0;
    HANDLE in = GetStdHandle(STD_INPUT_HANDLE);
    DWORD mode = 0;
    if (in == INVALID_HANDLE_VALUE || !GetConsoleMode(in, &mode)) {
        // Not a console: read a plain line (for example a redirected file).
        std::fputs(prompt, stderr);
        return read_stdin();
    }
    std::fputs(prompt, stderr);
    std::fflush(stderr);
    SetConsoleMode(in, mode & ~static_cast<DWORD>(ENABLE_ECHO_INPUT));
    wchar_t wbuf[kMaxPassword];
    DWORD got = 0;
    BOOL ok = ReadConsoleW(in, wbuf, static_cast<DWORD>(kMaxPassword - 1), &got, nullptr);
    SetConsoleMode(in, mode);
    std::fputs("\n", stderr);
    if (!ok) {
        return false;
    }
    while (got > 0 && (wbuf[got - 1] == L'\n' || wbuf[got - 1] == L'\r')) {
        --got;
    }
    if (got == 0) {
        sodium_memzero(wbuf, sizeof(wbuf));
        return true;
    }
    int n = WideCharToMultiByte(CP_UTF8, 0, wbuf, static_cast<int>(got), buf_,
                                static_cast<int>(cap_ - 1), nullptr, nullptr);
    sodium_memzero(wbuf, sizeof(wbuf));
    if (n <= 0) {
        return false;
    }
    len_ = static_cast<size_t>(n);
    buf_[len_] = '\0';
    return true;
}

#else

bool Password::read_console(const char* prompt) {
    if (!buf_) {
        return false;
    }
    std::fputs(prompt, stderr);
    std::fflush(stderr);
    struct termios old{};
    bool tty = isatty(STDIN_FILENO) && tcgetattr(STDIN_FILENO, &old) == 0;
    if (tty) {
        struct termios quiet = old;
        quiet.c_lflag &= ~static_cast<tcflag_t>(ECHO);
        tcsetattr(STDIN_FILENO, TCSAFLUSH, &quiet);
    }
    bool ok = read_stdin();
    if (tty) {
        tcsetattr(STDIN_FILENO, TCSAFLUSH, &old);
    }
    std::fputs("\n", stderr);
    return ok;
}

#endif

}  // namespace pvd
