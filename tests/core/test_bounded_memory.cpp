// Runs the command line tool on a 1 MB and a 256 MB input in separate
// processes and compares peak working set. The difference must stay under
// 16 MB, which shows that memory use does not grow with the input.
// Windows only (GetProcessMemoryInfo). CTest label: large.
#include "test_support.h"

#include <cstdio>
#include <fstream>

#ifndef PV_CLI_PATH
#error "PV_CLI_PATH must be defined"
#endif

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <psapi.h>

namespace {

// Runs the CLI with the given arguments, waits, and returns the peak working
// set in bytes. stdout_path receives the output.
SIZE_T run_peak(const std::wstring& args, const std::wstring& stdout_path, DWORD* exit_code) {
    SECURITY_ATTRIBUTES sa{sizeof(sa), nullptr, TRUE};
    HANDLE out = CreateFileW(stdout_path.c_str(), GENERIC_WRITE, FILE_SHARE_READ, &sa,
                             CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    CHECK(out != INVALID_HANDLE_VALUE);
    STARTUPINFOW si{};
    si.cb = sizeof(si);
    si.dwFlags = STARTF_USESTDHANDLES;
    si.hStdOutput = out;
    si.hStdError = GetStdHandle(STD_ERROR_HANDLE);
    si.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
    PROCESS_INFORMATION pi{};
    std::wstring cmd = L"\"" + std::wstring(PV_CLI_PATH_W) + L"\" " + args;
    std::vector<wchar_t> buf(cmd.begin(), cmd.end());
    buf.push_back(0);
    CHECK(CreateProcessW(nullptr, buf.data(), nullptr, nullptr, TRUE, 0, nullptr, nullptr, &si,
                         &pi));
    WaitForSingleObject(pi.hProcess, INFINITE);
    GetExitCodeProcess(pi.hProcess, exit_code);
    PROCESS_MEMORY_COUNTERS pmc{};
    pmc.cb = sizeof(pmc);
    CHECK(GetProcessMemoryInfo(pi.hProcess, &pmc, sizeof(pmc)));
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    CloseHandle(out);
    return pmc.PeakWorkingSetSize;
}

std::wstring widen(const std::string& s) {
    int n = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, nullptr, 0);
    std::wstring w(static_cast<size_t>(n), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, w.data(), n);
    w.resize(static_cast<size_t>(n - 1));
    return w;
}

std::string read_line(const std::string& path) {
    std::ifstream in(path);
    std::string s;
    std::getline(in, s);
    while (!s.empty() && (s.back() == '\r' || s.back() == '\n')) s.pop_back();
    return s;
}

// Writes length bytes of the deterministic stream to path.
void write_det(const std::string& path, uint64_t length) {
    ts::remove_tree(path);
    pv_source src{};
    CHECK_EQ(pv_source_deterministic_new(ts::test_seed(), PV_SEED_BYTES, length, &src), PV_OK);
    pv_sink sink{};
    CHECK_EQ(pv_file_sink_create(path.c_str(), &sink), PV_OK);
    std::vector<uint8_t> buf(1 << 20);
    for (;;) {
        size_t n = 0;
        CHECK_EQ(src.read(src.ctx, buf.data(), buf.size(), &n), 0);
        if (n == 0) break;
        CHECK_EQ(sink.write(sink.ctx, buf.data(), n), 0);
    }
    CHECK_EQ(pv_file_sink_commit(&sink), PV_OK);
    pv_source_deterministic_free(&src);
}

struct Result {
    SIZE_T enc_peak;
    SIZE_T dec_peak;
};

Result measure(const std::string& vault, const std::string& input, const std::string& output) {
    DWORD code = 0;
    std::wstring key = L" --test-vmk-file cli_test_vmk.hex";
    SIZE_T enc = run_peak(L"encrypt " + widen(vault) + L" " + widen(input) + key, L"mem_out.txt", &code);
    CHECK_EQ(code, 0u);
    std::string id = read_line("mem_out.txt");
    CHECK_EQ(id.size(), 32u);
    ts::remove_tree(output);
    SIZE_T dec = run_peak(L"decrypt " + widen(vault) + L" " + widen(id) + L" " + widen(output) + key,
                          L"mem_out.txt", &code);
    CHECK_EQ(code, 0u);
    return {enc, dec};
}

}  // namespace

int main() {
    CHECK_EQ(pv_init(), PV_OK);
    const std::string vault = "mem_vault";
    ts::remove_tree(vault);
    CHECK_EQ(pv_vault_create(vault.c_str()), PV_OK);
    {
        std::ofstream k("cli_test_vmk.hex");
        k << ts::hex(ts::Bytes(ts::test_vmk(), ts::test_vmk() + PV_VMK_BYTES)) << "\n";
    }
    const uint64_t small = 1ull << 20;
    const uint64_t large = 256ull << 20;
    write_det("mem_small.bin", small);
    write_det("mem_large.bin", large);

    Result s = measure(vault, "mem_small.bin", "mem_small.out");
    Result l = measure(vault, "mem_large.bin", "mem_large.out");

    // Output integrity for the large file.
    ts::Bytes expect(PV_HASH_BYTES);
    CHECK_EQ(pv_hash_deterministic(ts::test_seed(), PV_SEED_BYTES, large, expect.data(),
                                   expect.size()), PV_OK);
    {
        pv_source src{};
        CHECK_EQ(pv_file_source_open("mem_large.out", &src), PV_OK);
        pv_sink sink{};
        CHECK_EQ(pv_sink_blake2b_new(&sink), PV_OK);
        std::vector<uint8_t> buf(1 << 20);
        for (;;) {
            size_t n = 0;
            CHECK_EQ(src.read(src.ctx, buf.data(), buf.size(), &n), 0);
            if (n == 0) break;
            CHECK_EQ(sink.write(sink.ctx, buf.data(), n), 0);
        }
        pv_file_source_close(&src);
        ts::Bytes h(PV_HASH_BYTES);
        CHECK_EQ(pv_sink_blake2b_finish(&sink, h.data(), h.size()), PV_OK);
        CHECK(h == expect);
    }

    const double mb = 1024.0 * 1024.0;
    std::printf("peak working set, encrypt: 1 MB input %.1f MB, 256 MB input %.1f MB\n",
                s.enc_peak / mb, l.enc_peak / mb);
    std::printf("peak working set, decrypt: 1 MB input %.1f MB, 256 MB input %.1f MB\n",
                s.dec_peak / mb, l.dec_peak / mb);
    const SIZE_T limit = 16u << 20;
    CHECK(l.enc_peak <= s.enc_peak + limit);
    CHECK(l.dec_peak <= s.dec_peak + limit);

    ts::remove_tree("mem_small.bin");
    ts::remove_tree("mem_large.bin");
    ts::remove_tree("mem_small.out");
    ts::remove_tree("mem_large.out");
    ts::remove_tree(vault);
    std::printf("bounded memory ok\n");
    return 0;
}

#else

int main() {
    std::printf("bounded memory test is Windows only; skipped\n");
    return 0;
}

#endif
