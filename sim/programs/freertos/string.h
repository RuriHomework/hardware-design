#ifndef FREERTOS_DEMO_STRING_H
#define FREERTOS_DEMO_STRING_H

#include <stddef.h>

void *memset(void *dst, int value, size_t len);
void *memcpy(void *dst, const void *src, size_t len);
void *memmove(void *dst, const void *src, size_t len);
size_t strlen(const char *s);

#endif
