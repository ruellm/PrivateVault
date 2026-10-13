// pv: desktop command line tool for the PrivateVault Phase 1 prototype.
//
//   pv init <vault_dir>
//   pv encrypt <vault_dir> <input_file> [--chunk-size N]
//   pv decrypt <vault_dir> <object_id> <output_file>
//   pv verify <vault_dir> <object_id>
//   pv inspect <vault_dir> <object_id>
//   pv inspect-file <path.pvo>
//   pv version
//
// Options: --password-stdin reads the password from standard input instead of
// the console prompt. Passwords are never taken from the command line.
// With PV_TEST_HOOKS: --test-vmk-file <path> opens the vault with the raw or
// hex key in that file and skips vault.key; --kdf interactive makes init use
// the libsodium interactive Argon2id parameters.
//
// Exit codes: 0 success, 1 usage, 2 operation failed (the error name is printed),
// 3 passwords did not match.
#include "envelope.h"
#include "password.h"
#include "pv/pv.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include <sodium.h>

namespace {

struct Options {
    bool password_stdin = false;
    uint32_t chunk_size = 0;
    std::string test_vmk_file;
    bool kdf_interactive = false;
    std::vector<std::string> args;
};

int usage() {
    std::fputs(
        "usage:\n"
        "  pv init <vault_dir>\n"
        "  pv encrypt <vault_dir> <input_file> [--chunk-size N]\n"
        "  pv decrypt <vault_dir> <object_id> <output_file>\n"
        "  pv verify <vault_dir> <object_id>\n"
        "  pv inspect <vault_dir> <object_id>\n"
        "  pv inspect-file <path.pvo>\n"
        "  pv version\n"
        "options: --password-stdin\n",
        stderr);
    return 1;
}

int fail(int code) {
    std::fprintf(stderr, "error: %s\n", pv_error_name(code));
    return 2;
}

bool parse_options(int argc, char** argv, Options& o) {
    for (int i = 1; i < argc; ++i) {
        std::string a = argv[i];
        if (a == "--password-stdin") {
            o.password_stdin = true;
        } else if (a == "--chunk-size" && i + 1 < argc) {
            char* end = nullptr;
            unsigned long v = std::strtoul(argv[++i], &end, 10);
            if (!end || *end != '\0' || v == 0 || v > PV_MAX_CHUNK_SIZE) {
                return false;
            }
            o.chunk_size = static_cast<uint32_t>(v);
#ifdef PV_TEST_HOOKS
        } else if (a == "--test-vmk-file" && i + 1 < argc) {
            o.test_vmk_file = argv[++i];
        } else if (a == "--kdf" && i + 1 < argc) {
            std::string k = argv[++i];
            if (k == "interactive") {
                o.kdf_interactive = true;
            } else if (k != "moderate") {
                return false;
            }
#endif
        } else if (!a.empty() && a[0] == '-') {
            return false;
        } else {
            o.args.push_back(a);
        }
    }
    return true;
}

std::string key_path(const std::string& vault_dir) {
    char last = vault_dir.empty() ? '/' : vault_dir.back();
    return (last == '/' || last == '\\') ? vault_dir + "vault.key" : vault_dir + "/vault.key";
}

// Key buffer wiped on scope exit.
struct Vmk {
    uint8_t bytes[PV_VMK_BYTES] = {0};
    ~Vmk() { sodium_memzero(bytes, sizeof(bytes)); }
};

bool read_password(const Options& o, const char* prompt, pvd::Password& pw) {
    if (o.password_stdin) {
        return pw.read_stdin();
    }
    return pw.read_console(prompt);
}

#ifdef PV_TEST_HOOKS
// Reads a test key: 32 raw bytes or 64 hex characters (trailing newline allowed).
int load_test_vmk(const std::string& path, Vmk& vmk) {
    uint8_t buf[80];
    size_t got = 0;
    int rc = pv_file_read_all(path.c_str(), buf, sizeof(buf), &got);
    if (rc != PV_OK) {
        return rc;
    }
    if (got == PV_VMK_BYTES) {
        std::memcpy(vmk.bytes, buf, PV_VMK_BYTES);
        return PV_OK;
    }
    while (got > 0 && (buf[got - 1] == '\n' || buf[got - 1] == '\r' || buf[got - 1] == ' ')) {
        --got;
    }
    size_t bin_len = 0;
    if (got == 2 * PV_VMK_BYTES &&
        sodium_hex2bin(vmk.bytes, PV_VMK_BYTES, reinterpret_cast<const char*>(buf), got,
                       nullptr, &bin_len, nullptr) == 0 &&
        bin_len == PV_VMK_BYTES) {
        return PV_OK;
    }
    return PV_ERR_INVALID_ARGUMENT;
}
#endif

// Obtains the VMK for an existing vault, through the envelope or a test key.
int open_vault(const Options& o, const std::string& vault_dir, pv_vault** out) {
    Vmk vmk;
#ifdef PV_TEST_HOOKS
    if (!o.test_vmk_file.empty()) {
        int rc = load_test_vmk(o.test_vmk_file, vmk);
        if (rc != PV_OK) {
            return rc;
        }
        return pv_vault_open(vault_dir.c_str(), vmk.bytes, sizeof(vmk.bytes), out);
    }
#endif
    pvd::Password pw;
    if (!read_password(o, "Password: ", pw)) {
        return PV_ERR_IO;
    }
    int rc = pvd::envelope_read_file(key_path(vault_dir), pw.data(), pw.size(), vmk.bytes,
                                     sizeof(vmk.bytes));
    if (rc != PV_OK) {
        return rc;
    }
    return pv_vault_open(vault_dir.c_str(), vmk.bytes, sizeof(vmk.bytes), out);
}

int cmd_init(const Options& o) {
    if (o.args.size() != 2) {
        return usage();
    }
    const std::string& dir = o.args[1];
    int present = 0;
    int rc = pv_file_exists(key_path(dir).c_str(), &present);
    if (rc != PV_OK) {
        return fail(rc);
    }
    if (present) {
        return fail(PV_ERR_DEST_EXISTS);
    }
    Vmk vmk;
    rc = pv_vmk_generate(vmk.bytes, sizeof(vmk.bytes));
    if (rc != PV_OK) {
        return fail(rc);
    }
#ifdef PV_TEST_HOOKS
    if (!o.test_vmk_file.empty()) {
        rc = load_test_vmk(o.test_vmk_file, vmk);
        if (rc != PV_OK) {
            return fail(rc);
        }
        rc = pv_vault_create(dir.c_str());
        if (rc != PV_OK) {
            return fail(rc);
        }
        std::printf("vault created (test key, no vault.key written)\n");
        return 0;
    }
#endif
    pvd::Password pw1, pw2;
    if (!read_password(o, "New password: ", pw1)) {
        return fail(PV_ERR_IO);
    }
    if (o.password_stdin) {
        // Scripts supply the password once.
    } else {
        if (!read_password(o, "Repeat password: ", pw2)) {
            return fail(PV_ERR_IO);
        }
        if (!pw1.equals(pw2)) {
            std::fputs("error: passwords do not match\n", stderr);
            return 3;
        }
    }
    if (pw1.size() == 0) {
        std::fputs("error: empty password\n", stderr);
        return 3;
    }
    rc = pv_vault_create(dir.c_str());
    if (rc != PV_OK) {
        return fail(rc);
    }
    pvd::KdfParams params = o.kdf_interactive ? pvd::kdf_interactive() : pvd::kdf_moderate();
    rc = pvd::envelope_write_file(key_path(dir), vmk.bytes, sizeof(vmk.bytes), pw1.data(),
                                  pw1.size(), params);
    if (rc != PV_OK) {
        return fail(rc);
    }
    std::printf("vault created\n");
    return 0;
}

int cmd_encrypt(const Options& o) {
    if (o.args.size() != 3) {
        return usage();
    }
    pv_vault* v = nullptr;
    int rc = open_vault(o, o.args[1], &v);
    if (rc != PV_OK) {
        return fail(rc);
    }
    uint8_t id[PV_OBJECT_ID_BYTES];
    rc = pv_vault_encrypt_file(v, o.args[2].c_str(), o.chunk_size, id);
    pv_vault_close(v);
    if (rc != PV_OK) {
        return fail(rc);
    }
    char hex[2 * PV_OBJECT_ID_BYTES + 1];
    pv_object_id_to_hex(id, hex, sizeof(hex));
    std::printf("%s\n", hex);
    return 0;
}

int parse_id(const std::string& s, uint8_t* id) {
    return pv_object_id_from_hex(s.c_str(), id);
}

int cmd_decrypt(const Options& o) {
    if (o.args.size() != 4) {
        return usage();
    }
    uint8_t id[PV_OBJECT_ID_BYTES];
    if (parse_id(o.args[2], id) != PV_OK) {
        return usage();
    }
    pv_vault* v = nullptr;
    int rc = open_vault(o, o.args[1], &v);
    if (rc != PV_OK) {
        return fail(rc);
    }
    rc = pv_vault_decrypt_to_file(v, id, o.args[3].c_str());
    pv_vault_close(v);
    if (rc != PV_OK) {
        return fail(rc);
    }
    std::printf("ok\n");
    return 0;
}

int cmd_verify(const Options& o) {
    if (o.args.size() != 3) {
        return usage();
    }
    uint8_t id[PV_OBJECT_ID_BYTES];
    if (parse_id(o.args[2], id) != PV_OK) {
        return usage();
    }
    pv_vault* v = nullptr;
    int rc = open_vault(o, o.args[1], &v);
    if (rc != PV_OK) {
        return fail(rc);
    }
    // Decrypt through the item sink so item fields can be printed. The payload
    // goes to a null sink; nothing is written to disk.
    pv_sink null_sink{nullptr, [](void*, const uint8_t*, size_t) { return 0; }};
    pv_sink item{};
    rc = pv_sink_item_new(&null_sink, nullptr, &item);
    if (rc != PV_OK) {
        pv_vault_close(v);
        return fail(rc);
    }
    uint64_t len = 0;
    rc = pv_vault_decrypt(v, id, &item, &len);
    pv_vault_close(v);
    if (rc != PV_OK) {
        pv_sink_item_abort(&item);
        return fail(rc);
    }
    pv_item_header h{};
    int item_rc = pv_sink_item_finish(&item, len, &h);
    std::printf("ok %llu\n", static_cast<unsigned long long>(len));
    if (item_rc == PV_OK) {
        const char* type = h.media_type == PV_MEDIA_JPEG             ? "jpeg"
                           : h.media_type == PV_MEDIA_VIDEO_SEGMENT ? "video_segment"
                                                                    : "video_index";
        std::printf("item: %s %ux%u rotation %u created %llu payload %llu\n", type, h.width,
                    h.height, h.rotation_degrees,
                    static_cast<unsigned long long>(h.created_unix_ms),
                    static_cast<unsigned long long>(h.payload_len));
    } else if (item_rc == PV_ERR_NOT_ITEM) {
        std::printf("item: unknown\n");
    } else {
        std::printf("item: %s\n", pv_error_name(item_rc));
    }
    return 0;
}

void print_info(const pv_object_info& info) {
    char hex[2 * PV_OBJECT_ID_BYTES + 1];
    pv_object_id_to_hex(info.object_id, hex, sizeof(hex));
    std::printf("format_version %u\nsuite_id %u\nchunk_size %u\nobject_id %s\n",
                info.format_version, info.suite_id, info.chunk_size, hex);
}

int inspect_path(const std::string& path) {
    pv_source src{};
    int rc = pv_file_source_open(path.c_str(), &src);
    if (rc != PV_OK) {
        return fail(rc);
    }
    pv_object_info info{};
    rc = pv_object_inspect(&src, &info);
    pv_file_source_close(&src);
    if (rc != PV_OK) {
        return fail(rc);
    }
    print_info(info);
    return 0;
}

// Inspect reads only the header, so no password is needed and the vault is
// not opened.
int cmd_inspect(const Options& o) {
    if (o.args.size() != 3) {
        return usage();
    }
    uint8_t id[PV_OBJECT_ID_BYTES];
    if (parse_id(o.args[2], id) != PV_OK) {
        return usage();
    }
    const std::string& dir = o.args[1];
    char last = dir.empty() ? '/' : dir.back();
    std::string sep = (last == '/' || last == '\\') ? "" : "/";
    return inspect_path(dir + sep + "objects/" + o.args[2] + ".pvo");
}

int cmd_inspect_file(const Options& o) {
    if (o.args.size() != 2) {
        return usage();
    }
    return inspect_path(o.args[1]);
}

}  // namespace

int main(int argc, char** argv) {
    if (pv_init() != PV_OK) {
        return fail(PV_ERR_INIT);
    }
    Options o;
    if (!parse_options(argc, argv, o) || o.args.empty()) {
        return usage();
    }
    const std::string& cmd = o.args[0];
    if (cmd == "version") {
        std::printf("pv %s (libsodium %s)\n", pv_version_string(), pv_sodium_version_string());
        return 0;
    }
    if (cmd == "init") return cmd_init(o);
    if (cmd == "encrypt") return cmd_encrypt(o);
    if (cmd == "decrypt") return cmd_decrypt(o);
    if (cmd == "verify") return cmd_verify(o);
    if (cmd == "inspect") return cmd_inspect(o);
    if (cmd == "inspect-file") return cmd_inspect_file(o);
    return usage();
}
