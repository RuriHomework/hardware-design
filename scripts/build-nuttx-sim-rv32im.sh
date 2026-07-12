#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WRAP_DIR="$(mktemp -d)"
trap 'rm -rf "$WRAP_DIR"' EXIT

cat >"$WRAP_DIR/rv32im_builtins.c" <<'EOF'
typedef unsigned int u32;
typedef int s32;
typedef unsigned long long u64;
typedef long long s64;

typedef union
{
  u64 v;
  struct
  {
    u32 lo;
    u32 hi;
  } w;
} du;

static int du_bit(du x, unsigned int bit)
{
  return bit < 32 ? ((x.w.lo >> bit) & 1u) : ((x.w.hi >> (bit - 32)) & 1u);
}

static void du_setbit(du *x, unsigned int bit)
{
  if (bit < 32)
    {
      x->w.lo |= 1u << bit;
    }
  else
    {
      x->w.hi |= 1u << (bit - 32);
    }
}

static int du_ge(du a, du b)
{
  return a.w.hi > b.w.hi || (a.w.hi == b.w.hi && a.w.lo >= b.w.lo);
}

static du du_sub(du a, du b)
{
  du r;
  r.w.lo = a.w.lo - b.w.lo;
  r.w.hi = a.w.hi - b.w.hi - (a.w.lo < b.w.lo);
  return r;
}

static du du_neg(du x)
{
  du r;
  r.w.lo = ~x.w.lo + 1u;
  r.w.hi = ~x.w.hi + (r.w.lo == 0);
  return r;
}

static void du_shl1_or(du *x, unsigned int bit)
{
  x->w.hi = (x->w.hi << 1) | (x->w.lo >> 31);
  x->w.lo = (x->w.lo << 1) | (bit & 1u);
}

static du du_lshr(du x, unsigned int shift)
{
  du r;
  shift &= 63;
  if (shift == 0)
    {
      return x;
    }
  if (shift < 32)
    {
      r.w.lo = (x.w.lo >> shift) | (x.w.hi << (32 - shift));
      r.w.hi = x.w.hi >> shift;
    }
  else
    {
      r.w.lo = x.w.hi >> (shift - 32);
      r.w.hi = 0;
    }
  return r;
}

static du du_shl(du x, unsigned int shift)
{
  du r;
  shift &= 63;
  if (shift == 0)
    {
      return x;
    }
  if (shift < 32)
    {
      r.w.lo = x.w.lo << shift;
      r.w.hi = (x.w.hi << shift) | (x.w.lo >> (32 - shift));
    }
  else
    {
      r.w.lo = 0;
      r.w.hi = x.w.lo << (shift - 32);
    }
  return r;
}

static du du_ashr(du x, unsigned int shift)
{
  du r;
  shift &= 63;
  if (shift == 0)
    {
      return x;
    }
  if (shift < 32)
    {
      r.w.lo = (x.w.lo >> shift) | (x.w.hi << (32 - shift));
      r.w.hi = (u32)((s32)x.w.hi >> shift);
    }
  else
    {
      r.w.lo = (u32)((s32)x.w.hi >> (shift - 32));
      r.w.hi = (u32)((s32)x.w.hi >> 31);
    }
  return r;
}

static du du_udivmod(du n, du d, du *rem_out)
{
  du q = {0};
  du r = {0};
  int i;

  if (d.w.lo == 0 && d.w.hi == 0)
    {
      return q;
    }

  for (i = 63; i >= 0; --i)
    {
      du_shl1_or(&r, du_bit(n, (unsigned int)i));
      if (du_ge(r, d))
        {
          r = du_sub(r, d);
          du_setbit(&q, (unsigned int)i);
        }
    }

  if (rem_out)
    {
      *rem_out = r;
    }

  return q;
}

u64 __ashldi3(u64 a, int b)
{
  du x = {.v = a};
  return du_shl(x, (unsigned int)b).v;
}

u64 __lshrdi3(u64 a, int b)
{
  du x = {.v = a};
  return du_lshr(x, (unsigned int)b).v;
}

s64 __ashrdi3(s64 a, int b)
{
  du x = {.v = (u64)a};
  return (s64)du_ashr(x, (unsigned int)b).v;
}

u64 __udivdi3(u64 a, u64 b)
{
  du n = {.v = a};
  du d = {.v = b};
  return du_udivmod(n, d, 0).v;
}

u64 __umoddi3(u64 a, u64 b)
{
  du n = {.v = a};
  du d = {.v = b};
  du r = {0};
  (void)du_udivmod(n, d, &r);
  return r.v;
}

s64 __divdi3(s64 a, s64 b)
{
  du n = {.v = (u64)a};
  du d = {.v = (u64)b};
  int neg = 0;

  if ((s32)n.w.hi < 0)
    {
      n = du_neg(n);
      neg = !neg;
    }

  if ((s32)d.w.hi < 0)
    {
      d = du_neg(d);
      neg = !neg;
    }

  n = du_udivmod(n, d, 0);
  return (s64)(neg ? du_neg(n).v : n.v);
}

s64 __moddi3(s64 a, s64 b)
{
  du n = {.v = (u64)a};
  du d = {.v = (u64)b};
  du r = {0};
  int neg = 0;

  if ((s32)n.w.hi < 0)
    {
      n = du_neg(n);
      neg = 1;
    }

  if ((s32)d.w.hi < 0)
    {
      d = du_neg(d);
    }

  (void)du_udivmod(n, d, &r);
  return (s64)(neg ? du_neg(r).v : r.v);
}
EOF
clang --target=riscv32-unknown-elf -march=rv32im_zicsr_zifencei -mabi=ilp32 \
  -ffreestanding -fno-builtin -fno-lto -Oz -c "$WRAP_DIR/rv32im_builtins.c" \
  -o "$WRAP_DIR/rv32im_builtins.o"
llvm-ar rcs "$WRAP_DIR/librv32im_builtins.a" "$WRAP_DIR/rv32im_builtins.o"

NOLIBC_GCC_DIR="$(find /nix/store -maxdepth 1 -type d -name '*-riscv32-none-elf-nolibc-gcc-*' ! -name '*-lib' ! -name '*.drv' | sort | tail -n 1 || true)"
if [ -n "$NOLIBC_GCC_DIR" ] && [ -d "$NOLIBC_GCC_DIR/bin" ]; then
  export PATH="$NOLIBC_GCC_DIR/bin:$PATH"
fi

for prefix in riscv32-unknown-elf riscv64-unknown-elf; do
  for tool in gcc g++ cpp ld ar as nm objcopy objdump ranlib size strip; do
    cat >"$WRAP_DIR/$prefix-$tool" <<EOF
#!/usr/bin/env bash
exec riscv32-none-elf-$tool "\$@"
EOF
    chmod +x "$WRAP_DIR/$prefix-$tool"
  done

  cat >"$WRAP_DIR/$prefix-clang" <<'EOF'
#!/usr/bin/env bash
exec clang --target=riscv32-unknown-elf "$@"
EOF
  chmod +x "$WRAP_DIR/$prefix-clang"

  cat >"$WRAP_DIR/$prefix-clang++" <<'EOF'
#!/usr/bin/env bash
exec clang++ --target=riscv32-unknown-elf "$@"
EOF
  chmod +x "$WRAP_DIR/$prefix-clang++"

  for tool in strip ar nm objcopy objdump readelf size; do
    cat >"$WRAP_DIR/$prefix-llvm-$tool" <<EOF
#!/usr/bin/env bash
exec llvm-$tool "\$@"
EOF
    chmod +x "$WRAP_DIR/$prefix-llvm-$tool"
  done

  cat >"$WRAP_DIR/$prefix-ld" <<'EOF'
#!/usr/bin/env bash
exec ld.lld "$@"
EOF
  chmod +x "$WRAP_DIR/$prefix-ld"
done

export PATH="$WRAP_DIR:$PATH"

set_config() {
  local key="$1"
  local value="$2"
  if grep -Eq "^(# )?${key}(=| is not set)" .config; then
    perl -0pi -e "s/(^# ${key} is not set\\n|^${key}=.*\\n)/${key}=${value}\\n/m" .config
  else
    printf '%s=%s\n' "$key" "$value" >> .config
  fi
}

disable_config() {
  local key="$1"
  if grep -Eq "^(# )?${key}(=| is not set)" .config; then
    perl -0pi -e "s/(^# ${key} is not set\\n|^${key}=.*\\n)/# ${key} is not set\\n/m" .config
  else
    printf '# %s is not set\n' "$key" >> .config
  fi
}

cd "$ROOT/third_party/nuttx"
./tools/configure.sh -l -a ../apps rv-virt:nsh

disable_config CONFIG_ARCH_CHIP_QEMU_RV_ISA_A
disable_config CONFIG_ARCH_CHIP_QEMU_RV_ISA_C
disable_config CONFIG_ARCH_RV_ISA_A
disable_config CONFIG_ARCH_RV_ISA_C
disable_config CONFIG_ARCH_FPU
disable_config CONFIG_ARCH_DPFPU
disable_config CONFIG_ARCH_QPFPU
disable_config CONFIG_DISABLE_FLOAT
disable_config CONFIG_FS_HOSTFS
disable_config CONFIG_FS_PROCFS
disable_config CONFIG_RISCV_SEMIHOSTING_HOSTFS
disable_config CONFIG_RISCV_TOOLCHAIN_GNU_RV64
disable_config CONFIG_RISCV_TOOLCHAIN_GNU_RV32
disable_config CONFIG_RISCV_TOOLCHAIN_GNU_RV64ILP32
set_config CONFIG_RISCV_TOOLCHAIN_CLANG y
set_config CONFIG_STACK_USAGE_WARNING 0
set_config CONFIG_RAM_SIZE 1048576
disable_config CONFIG_LIBC_FLOATINGPOINT
disable_config CONFIG_LIBM
disable_config CONFIG_LIBM_NEWLIB
disable_config CONFIG_LIBM_LIBMCS
disable_config CONFIG_LIBM_OPENLIBM
disable_config CONFIG_LIBM_TOOLCHAIN
set_config CONFIG_LIBM_NONE y
disable_config CONFIG_SYSTEM_DD
disable_config CONFIG_TESTING_OSTEST
make olddefconfig
make clean
make -j"$(nproc)" EXTRA_LIBS="$WRAP_DIR/librv32im_builtins.a"
