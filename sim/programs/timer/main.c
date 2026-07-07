#include <stdint.h>

#define MTIME_LO    ((volatile uint32_t *)0x20000008u)
#define MTIMECMP_LO ((volatile uint32_t *)0x20000010u)

extern void trap_vector(void);
volatile uint32_t ticks;

static inline void write_mtvec(uint32_t value) {
  __asm__ volatile ("csrw mtvec, %0" :: "r"(value));
}

static inline void set_mie(uint32_t value) {
  __asm__ volatile ("csrs mie, %0" :: "r"(value));
}

static inline void set_mstatus(uint32_t value) {
  __asm__ volatile ("csrs mstatus, %0" :: "r"(value));
}

int main(void) {
  ticks = 0;
  write_mtvec((uint32_t)trap_vector);

  uint32_t now = *MTIME_LO;
  MTIMECMP_LO[1] = 0xffffffffu;
  MTIMECMP_LO[0] = now + 8;
  MTIMECMP_LO[1] = 0;

  set_mie(1u << 7);
  set_mstatus(1u << 3);

  for (uint32_t i = 0; i < 1000; ++i) {
    if (ticks != 0) {
      return 0;
    }
  }

  return 1;
}
