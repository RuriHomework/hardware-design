#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

: "${VIVADO_ROOT:=/home/ruri/Xilinx/Vivado/2024.2}"

fix_nixos_vivado_shebangs() {
  local bash_path
  bash_path="$(command -v bash)"
  local f
  while IFS= read -r -d '' f; do
    if [ -w "$f" ] && head -n 1 "$f" | grep -Eq '^#! */bin/(ba)?sh'; then
      sed -i "1s|^#! */bin/.*sh.*|#!${bash_path}|" "$f"
    fi
  done < <(grep -rIlZ -m 1 '^#! */bin/.*sh' "$VIVADO_ROOT/bin" "$VIVADO_ROOT/scripts")
}

vivado_runtime_ldpath() {
  local cache="build/vivado/vivado-runtime-ldpath"
  mkdir -p build/vivado
  if [ ! -s "$cache" ]; then
    local tmp="${cache}.tmp"
    : > "$tmp"
    local attr path
    for attr in \
      libxcrypt-legacy \
      ncurses5 \
      zlib \
      libx11 \
      libxext \
      libxrender \
      libxtst \
      libxi \
      libxft \
      libxcb \
      freetype \
      fontconfig \
      stdenv.cc.cc.lib; do
      path="$(nix eval --raw "nixpkgs#${attr}.outPath")"
      if [ -d "$path/lib" ]; then
        printf '%s/lib:' "$path" >> "$tmp"
      fi
    done
    mv "$tmp" "$cache"
  fi
  cat "$cache"
}

usage() {
  cat <<'USAGE'
Usage: scripts/vivado-fast.sh <command> [args]

Commands:
  ooc [Top]       Fast out-of-context synth/place/timing for build/<Top>.sv
                  Default Top: IssueQueue
  synth           BoardTop: stop after synth_design and timing report
  place           BoardTop: stop after place_design and timing report
  route           BoardTop: route and report timing, but do not write bitstream
  bit             Full BoardTop flow, including write_bitstream

Environment:
  VIVADO_ROOT     Vivado install root, default /home/ruri/Xilinx/Vivado/2024.2
  VIVADO_THREADS  Max Vivado worker threads, default 6
  VIVADO_XDC      BoardTop XDC path relative to repo root, default constraints.xdc
  VIVADO_STAGE    Override stage for ooc: synth, place, or route. Default place

Run scripts/chisel-fast.sh elab <Top> first when the SystemVerilog is stale.
USAGE
}

vivado_batch() {
  local tcl="$1"
  local name="$2"
  mkdir -p build/vivado
  fix_nixos_vivado_shebangs
  local runtime_ldpath
  runtime_ldpath="$(vivado_runtime_ldpath)"

  nix develop --impure .#vivado <<EOF
set -euo pipefail
source "$VIVADO_ROOT/settings64.sh"
export LD_LIBRARY_PATH="$runtime_ldpath\${LD_LIBRARY_PATH:-}"
"$VIVADO_ROOT/bin/vivado" -mode batch -source "$tcl" -journal "build/vivado/${name}.jou" -log "build/vivado/${name}.log"
EOF
}

cmd="${1:-}"
case "$cmd" in
  ooc)
    top="${2:-IssueQueue}"
    sv="build/${top}.sv"
    if [ ! -f "$sv" ]; then
      echo "Missing $sv. Run: scripts/chisel-fast.sh elab $top" >&2
      exit 1
    fi
    export VIVADO_TOP="$top"
    export VIVADO_STAGE="${VIVADO_STAGE:-place}"
    vivado_batch "scripts/vivado_ooc.tcl" "${top}_ooc_${VIVADO_STAGE}"
    ;;
  synth|place|route|bit)
    if [ ! -f build/BoardTop.sv ]; then
      echo "Missing build/BoardTop.sv. Run: scripts/chisel-fast.sh elab BoardTop" >&2
      exit 1
    fi
    export VIVADO_STAGE="$cmd"
    vivado_batch "scripts/vivado_boardtop_iter.tcl" "BoardTop_${cmd}"
    ;;
  -h|--help|help|"")
    usage
    ;;
  *)
    echo "Unknown command: $cmd" >&2
    usage >&2
    exit 2
    ;;
esac
