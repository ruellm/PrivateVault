#include "fs.h"

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#else
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

namespace pv::fs {

namespace {
thread_local int g_last_os_error = 0;
}

int fail(int code) {
#ifdef _WIN32
    g_last_os_error = static_cast<int>(GetLastError());
#else
    g_last_os_error = errno;
#endif
    return code;
}

std::string join(const std::string& dir, const std::string& name) {
    if (dir.empty()) {
        return name;
    }
    char last = dir.back();
    if (last == '/' || last == '\\') {
        return dir + name;
    }
    return dir + "/" + name;
}

#ifdef _WIN32

struct File {
    HANDLE h;
};

static bool to_wide(const std::string& utf8, std::wstring& out) {
    if (utf8.empty()) {
        out.clear();
        return true;
    }
    int n = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, utf8.data(),
                                static_cast<int>(utf8.size()), nullptr, 0);
    if (n <= 0) {
        return false;
    }
    out.resize(static_cast<size_t>(n));
    n = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, utf8.data(),
                            static_cast<int>(utf8.size()), out.data(), n);
    return n > 0;
}

static std::string from_wide(const wchar_t* w) {
    int n = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr);
    if (n <= 1) {
        return std::string();
    }
    std::string out(static_cast<size_t>(n - 1), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w, -1, out.data(), n, nullptr, nullptr);
    return out;
}

int open_read(const std::string& path, File** out) {
    std::wstring w;
    if (!to_wide(path, w)) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    HANDLE h = CreateFileW(w.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING,
                           FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) {
        DWORD e = GetLastError();
        return fail((e == ERROR_FILE_NOT_FOUND || e == ERROR_PATH_NOT_FOUND) ? PV_ERR_NOT_FOUND
                                                                             : PV_ERR_IO);
    }
    *out = new (std::nothrow) File{h};
    if (!*out) {
        CloseHandle(h);
        return PV_ERR_OUT_OF_MEMORY;
    }
    return PV_OK;
}

int create_new(const std::string& path, File** out) {
    std::wstring w;
    if (!to_wide(path, w)) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    HANDLE h = CreateFileW(w.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_NEW,
                           FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) {
        DWORD e = GetLastError();
        return fail((e == ERROR_FILE_EXISTS || e == ERROR_ALREADY_EXISTS) ? PV_ERR_DEST_EXISTS
                                                                          : PV_ERR_IO);
    }
    *out = new (std::nothrow) File{h};
    if (!*out) {
        CloseHandle(h);
        DeleteFileW(w.c_str());
        return PV_ERR_OUT_OF_MEMORY;
    }
    return PV_OK;
}

int read(File* f, uint8_t* buf, size_t cap, size_t* out_n) {
    DWORD want = cap > 0x40000000u ? 0x40000000u : static_cast<DWORD>(cap);
    DWORD got = 0;
    if (!ReadFile(f->h, buf, want, &got, nullptr)) {
        return fail(PV_ERR_IO);
    }
    *out_n = got;
    return PV_OK;
}

int write(File* f, const uint8_t* buf, size_t n) {
    while (n > 0) {
        DWORD want = n > 0x40000000u ? 0x40000000u : static_cast<DWORD>(n);
        DWORD done = 0;
        if (!WriteFile(f->h, buf, want, &done, nullptr) || done == 0) {
            return fail(PV_ERR_IO);
        }
        buf += done;
        n -= done;
    }
    return PV_OK;
}

int flush(File* f) {
    return FlushFileBuffers(f->h) ? PV_OK : fail(PV_ERR_IO);
}

void close(File* f) {
    if (f) {
        CloseHandle(f->h);
        delete f;
    }
}

int exists(const std::string& path, bool* out) {
    std::wstring w;
    if (!to_wide(path, w)) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    DWORD a = GetFileAttributesW(w.c_str());
    *out = (a != INVALID_FILE_ATTRIBUTES);
    return PV_OK;
}

int dir_exists(const std::string& path, bool* out) {
    std::wstring w;
    if (!to_wide(path, w)) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    DWORD a = GetFileAttributesW(w.c_str());
    *out = (a != INVALID_FILE_ATTRIBUTES) && (a & FILE_ATTRIBUTE_DIRECTORY);
    return PV_OK;
}

int remove_file(const std::string& path) {
    std::wstring w;
    if (!to_wide(path, w)) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    if (DeleteFileW(w.c_str())) {
        return PV_OK;
    }
    DWORD e = GetLastError();
    return fail((e == ERROR_FILE_NOT_FOUND || e == ERROR_PATH_NOT_FOUND) ? PV_ERR_NOT_FOUND
                                                                         : PV_ERR_IO);
}

int mkdir_if_missing(const std::string& path) {
    std::wstring w;
    if (!to_wide(path, w)) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    if (CreateDirectoryW(w.c_str(), nullptr)) {
        return PV_OK;
    }
    if (GetLastError() == ERROR_ALREADY_EXISTS) {
        bool is_dir = false;
        dir_exists(path, &is_dir);
        return is_dir ? PV_OK : fail(PV_ERR_IO);
    }
    return fail(PV_ERR_IO);
}

int rename_no_replace(const std::string& from, const std::string& to) {
    std::wstring wf, wt;
    if (!to_wide(from, wf) || !to_wide(to, wt)) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    if (MoveFileExW(wf.c_str(), wt.c_str(), MOVEFILE_WRITE_THROUGH)) {
        return PV_OK;
    }
    DWORD e = GetLastError();
    return fail((e == ERROR_ALREADY_EXISTS || e == ERROR_FILE_EXISTS) ? PV_ERR_DEST_EXISTS
                                                                      : PV_ERR_IO);
}

int list_dir(const std::string& dir, std::vector<std::string>& names) {
    std::wstring w;
    if (!to_wide(join(dir, "*"), w)) {
        return PV_ERR_INVALID_ARGUMENT;
    }
    WIN32_FIND_DATAW fd;
    HANDLE h = FindFirstFileW(w.c_str(), &fd);
    if (h == INVALID_HANDLE_VALUE) {
        DWORD e = GetLastError();
        if (e == ERROR_FILE_NOT_FOUND) {
            return PV_OK;
        }
        return fail((e == ERROR_PATH_NOT_FOUND) ? PV_ERR_NOT_FOUND : PV_ERR_IO);
    }
    do {
        if (wcscmp(fd.cFileName, L".") == 0 || wcscmp(fd.cFileName, L"..") == 0) {
            continue;
        }
        names.push_back(from_wide(fd.cFileName));
    } while (FindNextFileW(h, &fd));
    FindClose(h);
    return PV_OK;
}

#else

struct File {
    int fd;
};

int open_read(const std::string& path, File** out) {
    int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return fail(errno == ENOENT ? PV_ERR_NOT_FOUND : PV_ERR_IO);
    }
    *out = new (std::nothrow) File{fd};
    if (!*out) {
        ::close(fd);
        return PV_ERR_OUT_OF_MEMORY;
    }
    return PV_OK;
}

int create_new(const std::string& path, File** out) {
    int fd = ::open(path.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    if (fd < 0) {
        return fail(errno == EEXIST ? PV_ERR_DEST_EXISTS : PV_ERR_IO);
    }
    *out = new (std::nothrow) File{fd};
    if (!*out) {
        ::close(fd);
        ::unlink(path.c_str());
        return PV_ERR_OUT_OF_MEMORY;
    }
    return PV_OK;
}

int read(File* f, uint8_t* buf, size_t cap, size_t* out_n) {
    for (;;) {
        ssize_t r = ::read(f->fd, buf, cap);
        if (r >= 0) {
            *out_n = static_cast<size_t>(r);
            return PV_OK;
        }
        if (errno != EINTR) {
            return fail(PV_ERR_IO);
        }
    }
}

int write(File* f, const uint8_t* buf, size_t n) {
    while (n > 0) {
        ssize_t w = ::write(f->fd, buf, n);
        if (w < 0) {
            if (errno == EINTR) {
                continue;
            }
            return fail(PV_ERR_IO);
        }
        if (w == 0) {
            return fail(PV_ERR_IO);
        }
        buf += w;
        n -= static_cast<size_t>(w);
    }
    return PV_OK;
}

int flush(File* f) {
    return ::fsync(f->fd) == 0 ? PV_OK : fail(PV_ERR_IO);
}

void close(File* f) {
    if (f) {
        ::close(f->fd);
        delete f;
    }
}

int exists(const std::string& path, bool* out) {
    struct stat st;
    *out = ::stat(path.c_str(), &st) == 0;
    return PV_OK;
}

int dir_exists(const std::string& path, bool* out) {
    struct stat st;
    *out = ::stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
    return PV_OK;
}

int remove_file(const std::string& path) {
    if (::unlink(path.c_str()) == 0) {
        return PV_OK;
    }
    return fail(errno == ENOENT ? PV_ERR_NOT_FOUND : PV_ERR_IO);
}

int mkdir_if_missing(const std::string& path) {
    if (::mkdir(path.c_str(), 0700) == 0) {
        return PV_OK;
    }
    if (errno == EEXIST) {
        bool is_dir = false;
        dir_exists(path, &is_dir);
        return is_dir ? PV_OK : fail(PV_ERR_IO);
    }
    return fail(PV_ERR_IO);
}

static std::string parent_dir(const std::string& path) {
    size_t pos = path.find_last_of('/');
    if (pos == std::string::npos) {
        return ".";
    }
    if (pos == 0) {
        return "/";
    }
    return path.substr(0, pos);
}

static void fsync_dir(const std::string& dir) {
    int fd = ::open(dir.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd >= 0) {
        ::fsync(fd);
        ::close(fd);
    }
}

// link() refuses to replace an existing destination, which is the property
// wanted here. Where link() is not permitted (SELinux may deny it inside app
// data), fall back to an existence check followed by rename(). See
// docs/PHASE1_DESIGN.md Section 7 for the race this leaves.
int rename_no_replace(const std::string& from, const std::string& to) {
    if (::link(from.c_str(), to.c_str()) == 0) {
        ::unlink(from.c_str());
        fsync_dir(parent_dir(to));
        return PV_OK;
    }
    if (errno == EEXIST) {
        return fail(PV_ERR_DEST_EXISTS);
    }
    bool present = false;
    exists(to, &present);
    if (present) {
        return PV_ERR_DEST_EXISTS;
    }
    if (::rename(from.c_str(), to.c_str()) != 0) {
        return fail(PV_ERR_IO);
    }
    fsync_dir(parent_dir(to));
    return PV_OK;
}

int list_dir(const std::string& dir, std::vector<std::string>& names) {
    DIR* d = ::opendir(dir.c_str());
    if (!d) {
        return fail(errno == ENOENT ? PV_ERR_NOT_FOUND : PV_ERR_IO);
    }
    while (struct dirent* e = ::readdir(d)) {
        if (std::strcmp(e->d_name, ".") == 0 || std::strcmp(e->d_name, "..") == 0) {
            continue;
        }
        names.push_back(e->d_name);
    }
    ::closedir(d);
    return PV_OK;
}

#endif

}  // namespace pv::fs

extern "C" int pv_last_os_error(void) {
    return pv::fs::g_last_os_error;
}
