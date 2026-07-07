#include <stddef.h>
#include <stdint.h>

#include "FreeRTOS.h"
#include "task.h"

#define MMIO_EXIT ((volatile uint32_t *)0x20000000u)

static volatile uint32_t task_a_count;
static volatile uint32_t task_b_count;
static StaticTask_t task_a_tcb;
static StaticTask_t task_b_tcb;
static StaticTask_t monitor_tcb;
static StackType_t task_a_stack[configMINIMAL_STACK_SIZE];
static StackType_t task_b_stack[configMINIMAL_STACK_SIZE];
static StackType_t monitor_stack[configMINIMAL_STACK_SIZE];
static StaticTask_t idle_tcb;
static StackType_t idle_stack[configMINIMAL_STACK_SIZE];

static void sim_exit(uint32_t code)
{
    *MMIO_EXIT = code;
    for (;;) {
    }
}

void *memset(void *dst, int value, size_t len)
{
    unsigned char *p = (unsigned char *)dst;
    while (len-- != 0u) {
        *p++ = (unsigned char)value;
    }
    return dst;
}

void vApplicationGetIdleTaskMemory(StaticTask_t **tcb,
                                   StackType_t **stack,
                                   uint32_t *stack_size)
{
    *tcb = &idle_tcb;
    *stack = idle_stack;
    *stack_size = configMINIMAL_STACK_SIZE;
}

void *memcpy(void *dst, const void *src, size_t len)
{
    unsigned char *d = (unsigned char *)dst;
    const unsigned char *s = (const unsigned char *)src;
    while (len-- != 0u) {
        *d++ = *s++;
    }
    return dst;
}

void *memmove(void *dst, const void *src, size_t len)
{
    unsigned char *d = (unsigned char *)dst;
    const unsigned char *s = (const unsigned char *)src;

    if (d < s) {
        while (len-- != 0u) {
            *d++ = *s++;
        }
    } else if (d > s) {
        d += len;
        s += len;
        while (len-- != 0u) {
            *--d = *--s;
        }
    }

    return dst;
}

size_t strlen(const char *s)
{
    const char *p = s;
    while (*p != '\0') {
        p++;
    }
    return (size_t)(p - s);
}

static void task_a(void *arg)
{
    (void)arg;
    for (;;) {
        task_a_count++;
        vTaskDelay(1);
    }
}

static void task_b(void *arg)
{
    (void)arg;
    for (;;) {
        task_b_count++;
        vTaskDelay(1);
    }
}

static void monitor_task(void *arg)
{
    (void)arg;
    for (;;) {
        if (task_a_count >= 3u && task_b_count >= 3u) {
            sim_exit(0);
        }
        vTaskDelay(1);
    }
}

int main(void)
{
    TaskHandle_t a;
    TaskHandle_t b;
    TaskHandle_t mon;

    a = xTaskCreateStatic(task_a, "a", configMINIMAL_STACK_SIZE, NULL, 2,
                          task_a_stack, &task_a_tcb);
    b = xTaskCreateStatic(task_b, "b", configMINIMAL_STACK_SIZE, NULL, 2,
                          task_b_stack, &task_b_tcb);
    mon = xTaskCreateStatic(monitor_task, "mon", configMINIMAL_STACK_SIZE, NULL, 3,
                            monitor_stack, &monitor_tcb);

    if (a == NULL || b == NULL || mon == NULL) {
        sim_exit(1);
    }

    vTaskStartScheduler();
    sim_exit(3);
    return 3;
}
