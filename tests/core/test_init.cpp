#include "check.h"
#include "pv/pv.h"

#include <cstring>

int main() {
    CHECK_EQ(pv_init(), PV_OK);
    CHECK_EQ(pv_init(), PV_OK);
    CHECK(std::strlen(pv_version_string()) > 0);
    // libsodium 1.0.21 is the oldest release allowed by the project brief.
    CHECK(std::strcmp(pv_sodium_version_string(), "1.0.21") >= 0);
    std::printf("libsodium %s\n", pv_sodium_version_string());
    return 0;
}
