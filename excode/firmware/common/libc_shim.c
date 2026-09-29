/*
 * ============================================================================
 * Cryptand OS · 最小 libc 垫片（libc_shim.c，2026-09-17）
 *
 * 为什么需要：我们用 -nostdlib -ffreestanding（裸机），但 GCC 会**自动**把
 * 结构体赋值/数组拷贝/清零优化成 memset/memcpy 调用 —— 不提供就会链接失败：
 *     undefined reference to `memset'   ← tasks.c / heap_4.c / port.c 都有
 *
 * 所以裸机工程必须自带这几个"编译器内建依赖"函数。
 * ============================================================================
 */

#include <stdint.h>
#include <stddef.h>

void *memset(void *dst, int value, size_t n)
{
    uint8_t *d = (uint8_t *)dst;
    const uint8_t v = (uint8_t)value;
    while (n-- > 0u) {
        *d++ = v;
    }
    return dst;
}

void *memcpy(void *dst, const void *src, size_t n)
{
    uint8_t *d = (uint8_t *)dst;
    const uint8_t *s = (const uint8_t *)src;
    while (n-- > 0u) {
        *d++ = *s++;
    }
    return dst;
}

void *memmove(void *dst, const void *src, size_t n)
{
    uint8_t *d = (uint8_t *)dst;
    const uint8_t *s = (const uint8_t *)src;
    if (d == s || n == 0u) {
        return dst;
    }
    if (d < s) {
        while (n-- > 0u) {
            *d++ = *s++;
        }
    } else {
        d += n;
        s += n;
        while (n-- > 0u) {
            *--d = *--s;
        }
    }
    return dst;
}

int memcmp(const void *a, const void *b, size_t n)
{
    const uint8_t *x = (const uint8_t *)a;
    const uint8_t *y = (const uint8_t *)b;
    while (n-- > 0u) {
        if (*x != *y) {
            return (int)*x - (int)*y;
        }
        x++;
        y++;
    }
    return 0;
}

size_t strlen(const char *s)
{
    size_t n = 0;
    while (s[n] != '\0') {
        n++;
    }
    return n;
}
