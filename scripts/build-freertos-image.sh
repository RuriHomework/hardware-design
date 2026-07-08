#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../sim/programs/freertos"

nix shell nixpkgs#clang nixpkgs#llvmPackages.bintools nixpkgs#git nixpkgs#gnumake --command \
  make LLVM_PREFIX= CLANG=clang OBJCOPY=llvm-objcopy "$@"

bin_to_hex() {
  local bin="$1"
  local hex="$2"
  if [ -s "$bin" ]; then
    od -An -tx4 -v -w4 "$bin" | sed 's/ //g' > "$hex"
  else
    : > "$hex"
  fi
}

bin_to_hex build/smoke.text.bin build/smoke.text.hex
bin_to_hex build/smoke.data.bin build/smoke.data.hex

echo "IMEM_HEX=sim/programs/freertos/build/smoke.text.hex"
echo "DMEM_HEX=sim/programs/freertos/build/smoke.data.hex"
