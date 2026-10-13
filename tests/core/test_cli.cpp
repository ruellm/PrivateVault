// Drives the pv command line tool as a separate process: init, encrypt,
// decrypt, verify, inspect with --password-stdin, then wrong password and a
// tampered object. Also the test-only key path. Requires PV_TEST_HOOKS.
#include "test_support.h"

#include <cstdio>
#include <cstdlib>
#include <fstream>

#ifndef PV_CLI_PATH
#error "PV_CLI_PATH must be defined"
#endif

namespace {

const std::string kCli = PV_CLI_PATH;
const std::string kPwFile = "cli_password.txt";   // synthetic test password, deleted at the end
const std::string kOut = "cli_stdout.txt";
const std::string kErr = "cli_stderr.txt";

std::string quoted(const std::string& s) { return "\"" + s + "\""; }

int run(const std::string& args, const std::string& stdin_file = "") {
    std::string cmd = quoted(kCli) + " " + args;
    if (!stdin_file.empty()) cmd += " < " + quoted(stdin_file);
    cmd += " > " + quoted(kOut) + " 2> " + quoted(kErr);
#ifdef _WIN32
    cmd = "\"" + cmd + "\"";
#endif
    int rc = std::system(cmd.c_str());
#ifdef _WIN32
    return rc;
#else
    return WEXITSTATUS(rc);
#endif
}

std::string read_text(const std::string& path) {
    std::ifstream in(path);
    std::string s((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    while (!s.empty() && (s.back() == '\n' || s.back() == '\r')) s.pop_back();
    return s;
}

bool contains(const std::string& s, const std::string& needle) {
    return s.find(needle) != std::string::npos;
}

}  // namespace

int main() {
    CHECK_EQ(pv_init(), PV_OK);
    const std::string vault = "cli_vault";
    ts::remove_tree(vault);
    for (const char* f : {"cli_out.bin", "cli_out2.bin", "cli_out3.bin", "cli_out4.bin",
                          "cli_out.bin.pvtmp", "cli_in.bin"}) {
        ts::remove_tree(f);
    }
    {
        std::ofstream pw(kPwFile);
        pw << "test-password-for-cli\n";
    }
    std::ofstream(std::string("cli_wrong.txt")) << "not-the-password\n";

    CHECK_EQ(run("version"), 0);
    CHECK(contains(read_text(kOut), "libsodium"));
    CHECK_EQ(run(""), 1);
    CHECK_EQ(run("bogus"), 1);

    // init with interactive KDF so the test stays fast.
    CHECK_EQ(run("init " + vault + " --password-stdin --kdf interactive", kPwFile), 0);
    CHECK(ts::path_exists(vault + "/vault.key"));
    CHECK(ts::path_exists(vault + "/objects"));
    CHECK_EQ(run("init " + vault + " --password-stdin --kdf interactive", kPwFile), 2);
    CHECK(contains(read_text(kErr), "PV_ERR_DEST_EXISTS"));

    // encrypt a file.
    ts::Bytes plain = ts::det_bytes(PV_DEFAULT_CHUNK_SIZE * 2 + 7);
    ts::write_file("cli_in.bin", plain);
    CHECK_EQ(run("encrypt " + vault + " cli_in.bin --password-stdin", kPwFile), 0);
    std::string id = read_text(kOut);
    CHECK_EQ(id.size(), 32u);
    CHECK(ts::path_exists(vault + "/objects/" + id + ".pvo"));

    // verify, inspect (no password), decrypt.
    CHECK_EQ(run("verify " + vault + " " + id + " --password-stdin", kPwFile), 0);
    CHECK(contains(read_text(kOut), "ok 131079"));
    CHECK_EQ(run("inspect " + vault + " " + id), 0);
    CHECK(contains(read_text(kOut), "chunk_size 65536"));
    CHECK(contains(read_text(kOut), "object_id " + id));
    CHECK_EQ(run("inspect-file " + vault + "/objects/" + id + ".pvo"), 0);
    CHECK(contains(read_text(kOut), "format_version 1"));
    CHECK_EQ(run("decrypt " + vault + " " + id + " cli_out.bin --password-stdin", kPwFile), 0);
    CHECK(ts::read_file("cli_out.bin") == plain);
    CHECK(!ts::path_exists("cli_out.bin.pvtmp"));
    CHECK_EQ(run("decrypt " + vault + " " + id + " cli_out.bin --password-stdin", kPwFile), 2);
    CHECK(contains(read_text(kErr), "PV_ERR_DEST_EXISTS"));

    // Wrong password.
    CHECK_EQ(run("verify " + vault + " " + id + " --password-stdin", "cli_wrong.txt"), 2);
    CHECK(contains(read_text(kErr), "PV_ERR_ENVELOPE_UNWRAP_FAILED"));
    CHECK_EQ(run("decrypt " + vault + " " + id + " cli_out2.bin --password-stdin", "cli_wrong.txt"), 2);
    CHECK(!ts::path_exists("cli_out2.bin"));
    CHECK(!ts::path_exists("cli_out2.bin.pvtmp"));

    // Bad object id and missing object.
    CHECK_EQ(run("verify " + vault + " nothex --password-stdin", kPwFile), 1);
    CHECK_EQ(run("verify " + vault + " 00000000000000000000000000000000 --password-stdin", kPwFile), 2);
    CHECK(contains(read_text(kErr), "PV_ERR_NOT_FOUND"));

    // Tampered object: no output file, no temporary file.
    std::string obj = vault + "/objects/" + id + ".pvo";
    ts::Bytes original = ts::read_file(obj);
    ts::Bytes damaged = original;
    damaged[PV_HEADER_BYTES + 10] ^= 1;
    ts::write_file(obj, damaged);
    CHECK_EQ(run("decrypt " + vault + " " + id + " cli_out3.bin --password-stdin", kPwFile), 2);
    CHECK(contains(read_text(kErr), "PV_ERR_AUTH_FAILED"));
    CHECK(!ts::path_exists("cli_out3.bin"));
    CHECK(!ts::path_exists("cli_out3.bin.pvtmp"));
    ts::Bytes cut = original;
    cut.resize(PV_HEADER_BYTES + PV_DEFAULT_CHUNK_SIZE + 17);
    ts::write_file(obj, cut);
    CHECK_EQ(run("verify " + vault + " " + id + " --password-stdin", kPwFile), 2);
    CHECK(contains(read_text(kErr), "PV_ERR_TRUNCATED"));

    // Tampered envelope: wrong-password class of failure, nothing written.
    ts::Bytes env = ts::read_file(vault + "/vault.key");
    env[100] ^= 1;
    ts::write_file(vault + "/vault.key", env);
    CHECK_EQ(run("verify " + vault + " " + id + " --password-stdin", kPwFile), 2);
    CHECK(contains(read_text(kErr), "PV_ERR_ENVELOPE_UNWRAP_FAILED"));

    // Test-only key path: a vault without vault.key, key from a hex file.
    const std::string tvault = "cli_vault_testkey";
    ts::remove_tree(tvault);
    {
        std::ofstream k("cli_test_vmk.hex");
        k << ts::hex(ts::Bytes(ts::test_vmk(), ts::test_vmk() + PV_VMK_BYTES)) << "\n";
    }
    CHECK_EQ(run("init " + tvault + " --test-vmk-file cli_test_vmk.hex"), 0);
    CHECK(!ts::path_exists(tvault + "/vault.key"));
    CHECK_EQ(run("encrypt " + tvault + " cli_in.bin --test-vmk-file cli_test_vmk.hex --chunk-size 4096"), 0);
    std::string id2 = read_text(kOut);
    CHECK_EQ(run("inspect " + tvault + " " + id2), 0);
    CHECK(contains(read_text(kOut), "chunk_size 4096"));
    CHECK_EQ(run("decrypt " + tvault + " " + id2 + " cli_out4.bin --test-vmk-file cli_test_vmk.hex"), 0);
    CHECK(ts::read_file("cli_out4.bin") == plain);
    CHECK_EQ(run("encrypt " + tvault + " cli_in.bin --test-vmk-file cli_test_vmk.hex --chunk-size 100"), 2);
    CHECK(contains(read_text(kErr), "PV_ERR_CHUNK_SIZE_OUT_OF_RANGE"));
    CHECK_EQ(run("encrypt " + tvault + " cli_in.bin --test-vmk-file cli_test_vmk.hex --chunk-size 0"), 1);

    ts::remove_tree(kPwFile);
    ts::remove_tree("cli_wrong.txt");
    std::printf("cli ok\n");
    return 0;
}
