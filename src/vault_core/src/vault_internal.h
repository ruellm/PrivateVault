// The vault handle and the open helper shared by vault.cpp and envelope3.cpp.
// Not part of the C ABI.
#ifndef PV_CORE_VAULT_INTERNAL_H
#define PV_CORE_VAULT_INTERNAL_H

#include "common.h"

struct pv_vault {
    std::string dir;
    std::string objects_dir;
    uint8_t* vmk;
};

namespace pv {

constexpr const char* kObjectsDirName = "objects";
constexpr const char* kKeyFileName = "vault.key";
constexpr const char* kKeyPartialName = "vault.key.partial";

// Checks dir/objects, removes stale .partial objects, copies vmk into guarded
// memory, and returns a new handle. Arguments are already validated.
int vault_open_raw(const std::string& dir, const uint8_t* vmk, pv_vault** out);

}  // namespace pv

#endif
