#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
  echo "usage: $0 <nuttx-elf> <out-hex> [bytes]" >&2
  exit 2
fi

ELF="$1"
OUT="$2"
BYTES="${3:-1048576}"
BIN="${OUT%.hex}.bin"

OBJCOPY="${OBJCOPY:-riscv32-none-elf-objcopy}"

"$OBJCOPY" -O binary "$ELF" "$BIN"
truncate -s "$BYTES" "$BIN"
xxd -e -g4 -c4 "$BIN" | awk '{print $2}' > "$OUT"
