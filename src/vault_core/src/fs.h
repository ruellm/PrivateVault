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

// Names of the entries in dir, without "." and "..".
int list_dir(const std::string& dir, std::vector<std::string>& names);

std::string join(const std::string& dir, const std::string& name);

}  // namespace pv::fs

#endif
