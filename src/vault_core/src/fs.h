// Platform file operations behind one small interface. Paths are UTF-8.
// Windows uses the wide character APIs; everything else uses POSIX calls.
#ifndef PV_CORE_FS_H
#define PV_CORE_FS_H

#include "common.h"

namespace pv::fs {

struct File;

// Opens an existing file for reading. Missing file: PV_ERR_NOT_FOUND.
int open_read(const std::string& path, File** out);

// Creates a new file for writing. Existing file: PV_ERR_DEST_EXISTS.
int create_new(const std::string& path, File** out);

int read(File* f, uint8_t* buf, size_t cap, size_t* out_n);
int write(File* f, const uint8_t* buf, size_t n);
int flush(File* f);
void close(File* f);

int exists(const std::string& path, bool* out);
int dir_exists(const std::string& path, bool* out);
int remove_file(const std::string& path);
int mkdir_if_missing(const std::string& path);

// Renames from to to without replacing an existing destination
// (PV_ERR_DEST_EXISTS). Durable on return as far as the platform allows.
int rename_no_replace(const std::string& from, const std::string& to);

// Renames from to to, replacing an existing destination. Used only for the
// key envelope rewrite. Durable on return as far as the platform allows.
int rename_replace(const std::string& from, const std::string& to);

// Removes an empty directory. Missing: PV_ERR_NOT_FOUND. Not empty: PV_ERR_IO.
int remove_dir(const std::string& path);

// Names of the entries in dir, without "." and "..".
int list_dir(const std::string& dir, std::vector<std::string>& names);

// Length of a file in bytes. Missing: PV_ERR_NOT_FOUND.
int file_size(const std::string& path, uint64_t* out);

// Flushes directory metadata after an unlink or rename where the platform
// needs it (POSIX). No-op on Windows.
void sync_dir(const std::string& dir);

std::string join(const std::string& dir, const std::string& name);

// Records the platform error of a failed call for pv_last_os_error().
int fail(int code);

}  // namespace pv::fs

#endif
