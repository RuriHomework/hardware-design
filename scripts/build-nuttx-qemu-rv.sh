#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WRAP_DIR="$(mktemp -d)"
trap 'rm -rf "$WRAP_DIR"' EXIT

for tool in gcc g++ cpp ld ar as nm objcopy objdump ranlib size strip; do
  cat >"$WRAP_DIR/riscv64-unknown-elf-$tool" <<EOF
#!/usr/bin/env bash
exec riscv32-none-elf-$tool "\$@"
EOF
  chmod +x "$WRAP_DIR/riscv64-unknown-elf-$tool"
done

export PATH="$WRAP_DIR:$PATH"

cd "$ROOT/third_party/nuttx"
./tools/configure.sh -l -a ../apps rv-virt:nsh
kconfig-tweak --file .config --set-val CONFIG_STACK_USAGE_WARNING 0
make olddefconfig
make -j"$(nproc)"
