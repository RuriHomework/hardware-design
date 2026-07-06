{
  description = "Chisel dev environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { nixpkgs, flake-utils, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
      in {
        devShells.default = pkgs.mkShell {
          packages = with pkgs; [
            # Scala 工具链
            jdk21          # Chisel 6.x 需要 JDK 17+
            sbt
            metals          # Scala LSP，Zed 用得到

            # CIRCT 后端 (提供 firtool)
            circt

            # 仿真
            # verilator    # 当前 nixpkgs 上的 verilator 在 darwin 会从源码编译失败，建议用 `brew install verilator`
            # gtkwave      # 可选，看波形
          ];

          # sbt 默认会往 ~/.sbt 塞一堆东西，可以重定向
          shellHook = ''
            export JAVA_HOME="${pkgs.jdk21}"
            if command -v verilator >/dev/null 2>&1; then
              echo "Chisel dev shell ready. firtool $(firtool --version 2>&1 | head -1), verilator $(verilator --version | head -1)"
            else
              echo "Chisel dev shell ready. firtool $(firtool --version 2>&1 | head -1)"
              echo "提示: 本 shell 未提供 verilator。如需仿真，可运行: brew install verilator"
            fi
          '';
        };
      }
    );
}
