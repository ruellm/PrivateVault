// Injected read and write failures surface as PV_ERR_IO and leave no partial
// or temporary files behind.
#include "test_support.h"

#include <cstdio>

int main() {
    CHECK_EQ(pv_init(), PV_OK);
    const uint32_t c = PV_MIN_CHUNK_SIZE;
    ts::Bytes plain = ts::det_bytes(3 * c + 50);
    ts::Bytes cipher = ts::encrypt_mem(ts::test_vmk(), plain, c);

    // Read failures during encrypt at several points.
    for (size_t at : {size_t(0), size_t(1), size_t(c), size_t(c + 1), size_t(3 * c + 49)}) {
        ts::FaultSource fs{&plain, 0, at};
        ts::MemSink sink;
        pv_source s = fs.as_source();
        pv_sink k = sink.as_sink();
        CHECK_EQ(pv_object_encrypt(ts::test_vmk(), PV_VMK_BYTES, &s, &k, c, nullptr), PV_ERR_IO);
    }

    // Write failures during encrypt: header, first chunk, final chunk.
    for (size_t at : {size_t(0), size_t(PV_HEADER_BYTES), size_t(PV_HEADER_BYTES + c + 17),
                      cipher.size() - 1}) {
        ts::MemSource src{&plain};
        ts::FaultSink fk{0, at};
        pv_source s = src.as_source();
        pv_sink k = fk.as_sink();
        CHECK_EQ(pv_object_encrypt(ts::test_vmk(), PV_VMK_BYTES, &s, &k, c, nullptr), PV_ERR_IO);
    }

    // Read failures during decrypt.
    for (size_t at : {size_t(0), size_t(100), size_t(PV_HEADER_BYTES), size_t(PV_HEADER_BYTES + 5),
                      cipher.size() - 1}) {
        ts::FaultSource fs{&cipher, 0, at};
        ts::MemSink sink;
        pv_source s = fs.as_source();
        pv_sink k = sink.as_sink();
        uint64_t len = 0;
        CHECK_EQ(pv_object_decrypt(ts::test_vmk(), PV_VMK_BYTES, &s, &k, nullptr, &len), PV_ERR_IO);
        CHECK(sink.data != plain);
    }

    // Write failures during decrypt.
    for (size_t at : {size_t(0), size_t(c), size_t(3 * c)}) {
        ts::MemSource src{&cipher};
        ts::FaultSink fk{0, at};
        pv_source s = src.as_source();
        pv_sink k = fk.as_sink();
        uint64_t len = 0;
        CHECK_EQ(pv_object_decrypt(ts::test_vmk(), PV_VMK_BYTES, &s, &k, nullptr, &len), PV_ERR_IO);
    }

    // Through a vault: a failing source leaves no partial file; a failing
    // sink on decrypt leaves no temporary file.
    const std::string vault = "vault_faults";
    ts::remove_tree(vault);
    CHECK_EQ(pv_vault_create(vault.c_str()), PV_OK);
    pv_vault* v = nullptr;
    CHECK_EQ(pv_vault_open(vault.c_str(), ts::test_vmk(), PV_VMK_BYTES, &v), PV_OK);
    {
        ts::FaultSource fs{&plain, 0, c + 10};
        pv_source s = fs.as_source();
        uint8_t id[PV_OBJECT_ID_BYTES];
        CHECK_EQ(pv_vault_encrypt(v, &s, c, id), PV_ERR_IO);
        std::error_code ec;
        size_t entries = 0;
        for (auto& e : std::filesystem::directory_iterator(ts::u8path(vault + "/objects"), ec)) {
            (void)e;
            ++entries;
        }
        CHECK_EQ(entries, 0u);
    }
    {
        ts::MemSource src{&plain};
        pv_source s = src.as_source();
        uint8_t id[PV_OBJECT_ID_BYTES];
        CHECK_EQ(pv_vault_encrypt(v, &s, c, id), PV_OK);
        ts::FaultSink fk{0, c};
        pv_sink k = fk.as_sink();
        uint64_t len = 0;
        CHECK_EQ(pv_vault_decrypt(v, id, &k, &len), PV_ERR_IO);
    }
    pv_vault_close(v);
    std::printf("io faults ok\n");
    return 0;
}
