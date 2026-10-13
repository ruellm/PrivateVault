// Minimal assertion helper for the plain CTest test programs.
#ifndef PV_TESTS_CHECK_H
#define PV_TESTS_CHECK_H

#include <cstdio>
#include <cstdlib>

#define CHECK(cond)                                                              \
    do {                                                                         \
        if (!(cond)) {                                                           \
            std::fprintf(stderr, "%s:%d: check failed: %s\n", __FILE__, __LINE__, \
                         #cond);                                                 \
            std::exit(1);                                                        \
        }                                                                        \
    } while (0)

#define CHECK_EQ(a, b) CHECK((a) == (b))

#endif
