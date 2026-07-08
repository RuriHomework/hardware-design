#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

usage() {
  cat <<'USAGE'
Usage: scripts/chisel-fast.sh <command> [args]

Commands:
  compile             Compile main/test Scala without running tests
  test-backend        Run only cpu.BackendStructuresSpec
  test-issue          Run only IssueQueue-related backend tests
  test-core           Run only cpu.CoreSpec
  test                Run the full test suite
  elab [Top]          Elaborate a Chisel top into build/<Top>.sv (default: BoardTop)
  shell               Open a persistent sbt shell inside the Nix dev environment
  sbt <command...>    Run an arbitrary sbt command through the same environment

For the tightest edit/test loop, use "shell" and run multiple sbt commands in
that same process. One-shot commands are convenient but still pay shell startup.
USAGE
}

run_sbt() {
  local sbt_cmd="$1"
  nix develop .#default --command bash -lc '
    set -euo pipefail
    export CHISEL_FIRTOOL_PATH="${CHISEL_FIRTOOL_PATH:-$HOME/.cache/llvm-firtool/1.62.1/bin}"
    exec sbt "$1"
  ' bash "$sbt_cmd"
}

run_sbt_shell() {
  nix develop .#default --command bash -lc '
    set -euo pipefail
    export CHISEL_FIRTOOL_PATH="${CHISEL_FIRTOOL_PATH:-$HOME/.cache/llvm-firtool/1.62.1/bin}"
    exec sbt
  '
}

cmd="${1:-}"
case "$cmd" in
  compile)
    run_sbt "Test/compile"
    ;;
  test-backend)
    run_sbt "testOnly cpu.BackendStructuresSpec"
    ;;
  test-issue)
    run_sbt 'testOnly cpu.BackendStructuresSpec -- -z "wake operands"'
    ;;
  test-core)
    run_sbt "testOnly cpu.CoreSpec"
    ;;
  test)
    run_sbt "test"
    ;;
  elab)
    top="${2:-BoardTop}"
    run_sbt "runMain Elaborate --top ${top}"
    ;;
  shell)
    run_sbt_shell
    ;;
  sbt)
    shift
    if [ "$#" -eq 0 ]; then
      usage
      exit 2
    fi
    run_sbt "$*"
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
