// Vault directory behaviour: creation, naming, partial files, atomic
// finalize, temporary files on decrypt, cleanup after failure, and a
// non-ASCII directory name.
#include "test_support.h"

#include <cstdio>

namespace {

std::string objects_dir(const std::string& vault) { return vault + "/objects"; }

std::string object_file(const std::string& vault, const uint8_t* id) {
    return objects_dir(vault) + "/" + ts::id_hex(id) + ".pvo";
}

void run_in(const std::string& vault) {
    ts::remove_tree(vault);
    CHECK(!ts::path_exists(vault));

    // open before create: no objects directory.
    pv_vault* v = nullptr;
    CHECK_EQ(pv_vault_open(vault.c_str(), ts::test_vmk(), PV_VMK_BYTES, &v), PV_ERR_NOT_FOUND);
    CHECK(v == nullptr);

    CHECK_EQ(pv_vault_create(vault.c_str()), PV_OK);
    CHECK(ts::path_exists(objects_dir(vault)));
    CHECK_EQ(pv_vault_create(vault.c_str()), PV_OK);  // idempotent

    // A stale partial file is removed on open.
    ts::write_file(objects_dir(vault) + "/00000000000000000000000000000000.pvo.partial",
                   ts::Bytes(10, 1));
    ts::write_file(objects_dir(vault) + "/keep.txt", ts::Bytes(3, 2));
    CHECK_EQ(pv_vault_open(vault.c_str(), ts::test_vmk(), PV_VMK_BYTES, &v), PV_OK);
    CHECK(!ts::path_exists(objects_dir(vault) + "/00000000000000000000000000000000.pvo.partial"));
    CHECK(ts::path_exists(objects_dir(vault) + "/keep.txt"));

    // Encrypt from a stream, verify, decrypt to a stream.
    ts::Bytes plain = ts::det_bytes(PV_DEFAULT_CHUNK_SIZE + 123);
    ts::MemSource src{&plain};
    pv_source s = src.as_source();
    uint8_t id[PV_OBJECT_ID_BYTES];
    CHECK_EQ(pv_vault_encrypt(v, &s, 0, id), PV_OK);
    CHECK(ts::path_exists(object_file(vault, id)));
    CHECK(!ts::path_exists(object_file(vault, id) + ".partial"));

    uint64_t len = 0;
    CHECK_EQ(pv_vault_verify(v, id, &len), PV_OK);
    CHECK_EQ(len, plain.size());

    ts::MemSink sink;
    pv_sink k = sink.as_sink();
    CHECK_EQ(pv_vault_decrypt(v, id, &k, &len), PV_OK);
    CHECK(sink.data == plain);

    pv_object_info info{};
    CHECK_EQ(pv_vault_inspect(v, id, &info), PV_OK);
    CHECK_EQ(info.chunk_size, PV_DEFAULT_CHUNK_SIZE);
    CHECK_EQ(std::memcmp(info.object_id, id, sizeof(id)), 0);

    char path[1024];
    CHECK_EQ(pv_vault_object_path(v, id, path, sizeof(path)), PV_OK);
    CHECK_EQ(std::string(path), object_file(vault, id));

    // Decrypt to a file; the temporary name must be gone afterwards.
    std::string out = vault + "/out.bin";
    CHECK_EQ(pv_vault_decrypt_to_file(v, id, out.c_str()), PV_OK);
    CHECK(ts::read_file(out) == plain);
    CHECK(!ts::path_exists(out + ".pvtmp"));
    // Destination exists: refused, untouched.
    CHECK_EQ(pv_vault_decrypt_to_file(v, id, out.c_str()), PV_ERR_DEST_EXISTS);
    CHECK(ts::read_file(out) == plain);
    // Temporary file exists: refused, untouched.
    std::string out2 = vault + "/out2.bin";
    ts::write_file(out2 + ".pvtmp", ts::Bytes(5, 9));
    CHECK_EQ(pv_vault_decrypt_to_file(v, id, out2.c_str()), PV_ERR_DEST_EXISTS);
    CHECK(!ts::path_exists(out2));
    CHECK_EQ(ts::read_file(out2 + ".pvtmp").size(), 5u);

    // Unknown object.
    uint8_t missing[PV_OBJECT_ID_BYTES] = {7};
    CHECK_EQ(pv_vault_verify(v, missing, &len), PV_ERR_NOT_FOUND);
    CHECK_EQ(pv_vault_decrypt_to_file(v, missing, (vault + "/none.bin").c_str()),
             PV_ERR_NOT_FOUND);
    CHECK(!ts::path_exists(vault + "/none.bin"));
    CHECK(!ts::path_exists(vault + "/none.bin.pvtmp"));

    // File name and header disagree (file renamed): rejected.
    uint8_t other[PV_OBJECT_ID_BYTES];
    std::memcpy(other, id, sizeof(id));
    other[0] ^= 0xff;
    ts::write_file(object_file(vault, other), ts::read_file(object_file(vault, id)));
    CHECK_EQ(pv_vault_verify(v, other, &len), PV_ERR_MALFORMED_HEADER);

    // Tampered object: decrypt to file leaves neither output nor temporary file.
    ts::Bytes damaged = ts::read_file(object_file(vault, id));
    damaged[damaged.size() - 1] ^= 1;
    std::string dpath = object_file(vault, id);
    ts::write_file(dpath, damaged);
    std::string out3 = vault + "/out3.bin";
    CHECK_EQ(pv_vault_decrypt_to_file(v, id, out3.c_str()), PV_ERR_AUTH_FAILED);
    CHECK(!ts::path_exists(out3));
    CHECK(!ts::path_exists(out3 + ".pvtmp"));
    ts::Bytes cut = damaged;
    cut.resize(PV_HEADER_BYTES + PV_DEFAULT_CHUNK_SIZE + 17);
    ts::write_file(dpath, cut);
    CHECK_EQ(pv_vault_decrypt_to_file(v, id, out3.c_str()), PV_ERR_TRUNCATED);
    CHECK(!ts::path_exists(out3));
    CHECK(!ts::path_exists(out3 + ".pvtmp"));

    // Wrong key through the vault.
    pv_vault* w = nullptr;
    CHECK_EQ(pv_vault_open(vault.c_str(), ts::other_vmk().data(), PV_VMK_BYTES, &w), PV_OK);
    ts::write_file(dpath, ts::read_file(object_file(vault, other)));  // restore a good copy
    CHECK_EQ(pv_vault_verify(v, id, &len), PV_OK);
    CHECK_EQ(pv_vault_verify(w, id, &len), PV_ERR_WRONG_VMK);
    pv_vault_close(w);

    // Encrypt from a file and read it back.
    std::string in = vault + "/in.bin";
    ts::Bytes plain2 = ts::det_bytes(3 * PV_MIN_CHUNK_SIZE);
    ts::write_file(in, plain2);
    uint8_t id2[PV_OBJECT_ID_BYTES];
    CHECK_EQ(pv_vault_encrypt_file(v, in.c_str(), PV_MIN_CHUNK_SIZE, id2), PV_OK);
    std::string out4 = vault + "/out4.bin";
    CHECK_EQ(pv_vault_decrypt_to_file(v, id2, out4.c_str()), PV_OK);
    CHECK(ts::read_file(out4) == plain2);
    CHECK_EQ(pv_vault_encrypt_file(v, (vault + "/missing.bin").c_str(), 0, id2), PV_ERR_NOT_FOUND);

    // Empty input through the vault.
    ts::Bytes empty;
    ts::MemSource esrc{&empty};
    pv_source es = esrc.as_source();
    uint8_t id3[PV_OBJECT_ID_BYTES];
    CHECK_EQ(pv_vault_encrypt(v, &es, 0, id3), PV_OK);
    CHECK_EQ(pv_vault_verify(v, id3, &len), PV_OK);
    CHECK_EQ(len, 0u);
    CHECK_EQ(ts::read_file(object_file(vault, id3)).size(), PV_HEADER_BYTES + 17u);

    // Argument checks.
    CHECK_EQ(pv_vault_encrypt(nullptr, &s, 0, id), PV_ERR_INVALID_ARGUMENT);
    CHECK_EQ(pv_vault_encrypt(v, &s, PV_MAX_CHUNK_SIZE + 1, id), PV_ERR_CHUNK_SIZE_OUT_OF_RANGE);
    CHECK_EQ(pv_vault_open(vault.c_str(), ts::test_vmk(), 16, &w), PV_ERR_INVALID_ARGUMENT);

    pv_vault_close(v);
    pv_vault_close(nullptr);
}

}  // namespace

int main() {
    CHECK_EQ(pv_init(), PV_OK);
    run_in("vault_ascii");
    // Non-ASCII directory name (UTF-8): Latin, Japanese, and an emoji.
    run_in("vault_\xc3\xbc\xc3\xb1\xc3\xae_\xe6\x97\xa5\xe6\x9c\xac_\xf0\x9f\x94\x92");
    std::printf("vault ok\n");
    return 0;
}
