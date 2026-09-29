#include <stdint.h>
#ifdef _WIN32
#include <intrin.h>
#endif

static void cpuid(int a, int c, uint32_t *regs) {
#ifdef _WIN32
#if defined(_MSC_VER)
    __cpuidex((int *) regs, a, c);
#else
    // MinGW-w64: __cpuidex may be unavailable; use __cpuid with subleaf via asm
    __asm__ __volatile__("cpuid"
        : "=a" (regs[0]), "=b" (regs[1]), "=c" (regs[2]), "=d" (regs[3])
        : "a" (a), "c" (c));
#endif
#else
    asm volatile("cpuid" : "=a" (regs[0]), "=b" (regs[1]), "=c" (regs[2]), "=d" (regs[3]) : "a" (a), "c" (c));
#endif
}

int verificationFunc() {
    uint32_t bits[4];
    cpuid(7, 0, bits);
    // Check for AVX2
    if(!(bits[1] & (1 << 5)))
        return 1;
    return 0;
}
