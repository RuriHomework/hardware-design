#include <stddef.h>
#include <stdint.h>

#include "FreeRTOS.h"
#include "task.h"

#define MMIO_EXIT ((volatile uint32_t *)0x20000000u)
#define MMIO_UART_TX ((volatile uint32_t *)0x20000004u)
#define MMIO_UART_STATUS ((volatile uint32_t *)0x20000018u)

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

static void uart_putc(char c)
{
    while ((*MMIO_UART_STATUS & 1u) == 0u) {
    }
    *MMIO_UART_TX = (uint32_t)(uint8_t)c;
}

static void uart_boot(void)
{
    uart_putc('F');
    uart_putc('r');
    uart_putc('e');
    uart_putc('e');
    uart_putc('R');
    uart_putc('T');
    uart_putc('O');
    uart_putc('S');
    uart_putc(' ');
    uart_putc('b');
    uart_putc('o');
    uart_putc('o');
    uart_putc('t');
    uart_putc('\n');
}

static void uart_pass(void)
{
    uart_putc('P');
    uart_putc('A');
    uart_putc('S');
    uart_putc('S');
    uart_putc('\n');
}

static void uart_fail(void)
{
    uart_putc('F');
    uart_putc('A');
    uart_putc('I');
    uart_putc('L');
    uart_putc('\n');
}

static void report_exit(uint32_t code)
{
    if (code == 0u) {
        uart_pass();
    } else {
        uart_fail();
    }
    *MMIO_EXIT = code;
}

static void sim_exit(uint32_t code)
{
    report_exit(code);
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
        if (task_a_count == 1u) {
            uart_putc('A');
            uart_putc('\n');
        }
        vTaskDelay(1);
    }
}

static void task_b(void *arg)
{
    (void)arg;
    for (;;) {
        task_b_count++;
        if (task_b_count == 1u) {
            uart_putc('B');
            uart_putc('\n');
        }
        vTaskDelay(1);
    }
}

static void monitor_task(void *arg)
{
    (void)arg;
    for (;;) {
        if (task_a_count >= 3u && task_b_count >= 3u) {
            report_exit(0);
            for (;;) {
                vTaskDelay(configTICK_RATE_HZ);
                uart_pass();
            }
        }
        vTaskDelay(1);
    }
}

int main(void)
{
    TaskHandle_t a;
    TaskHandle_t b;
    TaskHandle_t mon;

    uart_boot();

    a = xTaskCreateStatic(task_a, NULL, configMINIMAL_STACK_SIZE, NULL, 2,
                          task_a_stack, &task_a_tcb);
    b = xTaskCreateStatic(task_b, NULL, configMINIMAL_STACK_SIZE, NULL, 2,
                          task_b_stack, &task_b_tcb);
    mon = xTaskCreateStatic(monitor_task, NULL, configMINIMAL_STACK_SIZE, NULL, 3,
                            monitor_stack, &monitor_tcb);

    if (a == NULL || b == NULL || mon == NULL) {
        sim_exit(1);
    }

    vTaskStartScheduler();
    sim_exit(3);
    return 3;
}
