// Desktop key envelope (vault.key, provider 1): the vault master key wrapped
// under a key derived from a password with Argon2id. Layout in
// docs/PHASE1_DESIGN.md Section 4. This code lives outside the portable core.
#ifndef PV_DESKTOP_ENVELOPE_H
#define PV_DESKTOP_ENVELOPE_H

#include "pv/pv.h"

#include <cstddef>
#include <cstdint>
#include <string>

namespace pvd {

constexpr size_t kEnvelopeBytes = 112;
constexpr uint16_t kEnvelopeVersion = 1;
constexpr uint16_t kProviderPassword = 1;

struct KdfParams {
    uint32_t opslimit;
    uint64_t memlimit;
};

KdfParams kdf_moderate();
KdfParams kdf_interactive();

// Accepted range for parameters read from an envelope. Checked before the
// KDF runs.
bool kdf_params_in_range(const KdfParams& p);

// Produces the 112-byte envelope for vmk under the password.
int envelope_seal(const uint8_t* vmk, size_t vmk_len, const char* password,
                  size_t password_len, const KdfParams& params, uint8_t* out, size_t out_len);

// Recovers the VMK from an envelope. Errors use the PV_ERR_ENVELOPE_* codes.
int envelope_open(const uint8_t* data, size_t len, const char* password, size_t password_len,
                  uint8_t* vmk_out, size_t vmk_out_len);

// File forms. write refuses an existing file; read rejects files of the wrong size.
int envelope_write_file(const std::string& path, const uint8_t* vmk, size_t vmk_len,
                        const char* password, size_t password_len, const KdfParams& params);
int envelope_read_file(const std::string& path, const char* password, size_t password_len,
                       uint8_t* vmk_out, size_t vmk_out_len);

}  // namespace pvd

#endif
