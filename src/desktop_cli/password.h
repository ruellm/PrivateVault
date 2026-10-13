// Password input for the command line tool. Never reads from argv.
#ifndef PV_DESKTOP_PASSWORD_H
#define PV_DESKTOP_PASSWORD_H

#include <cstddef>

namespace pvd {

// A password buffer that is wiped on destruction.
class Password {
public:
    Password();
    ~Password();
    Password(const Password&) = delete;
    Password& operator=(const Password&) = delete;

    const char* data() const { return buf_; }
    size_t size() const { return len_; }
    bool equals(const Password& other) const;

    // Reads one line from the console without echo. Returns false on failure.
    bool read_console(const char* prompt);
    // Reads one line from stdin (for scripts). Trailing CR and LF are removed.
    bool read_stdin();

private:
    bool read_fd_line();
    void push(char c);

    char* buf_;
    size_t len_;
    size_t cap_;
};

}  // namespace pvd

#endif
