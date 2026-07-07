#include <stdint.h>

#define SIM_EXIT ((volatile uint32_t *)0x20000000u)

static volatile uint32_t results[4];

int main(void) {
  uint32_t a = 42;
  uint32_t b = 10;
  uint32_t c = a + b;
  uint32_t d = c * 3;

  results[0] = a;
  results[1] = b;
  results[2] = c;
  results[3] = d;

  if (results[0] != 42 || results[1] != 10 || results[2] != 52 || results[3] != 156) {
    *SIM_EXIT = 1;
    return 1;
  }

  *SIM_EXIT = 0;
  return 0;
}
