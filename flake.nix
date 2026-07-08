{
  description = "Chisel dev environment";

  inputs = {
    nixpkgs.url = "nixpkgs";
  };

  outputs = { nixpkgs, ... }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs { inherit system; };
    in {
      devShells.${system} = {
        default = pkgs.mkShell {
          packages = with pkgs; [
            jdk21
            sbt
            metals
            circt
            verilator
          ];

          shellHook = ''
            export JAVA_HOME="${pkgs.jdk21}"
            export CHISEL_FIRTOOL_PATH="''${CHISEL_FIRTOOL_PATH:-$HOME/.cache/llvm-firtool/1.62.1/bin}"
            echo "Chisel dev shell ready. firtool $(firtool --version 2>&1 | head -1), verilator $(verilator --version | head -1)"
          '';
        };

        vivado = (pkgs.buildFHSEnv {
          name = "vivado-env";
          targetPkgs = pkgs: with pkgs; [
            bash
            coreutils
            file
            findutils
            gawk
            gcc
            gnugrep
            gnused
            graphviz
            nettools
            unzip
            which
            xz
            expat
            fontconfig
            freetype
            glib
            gtk2
            gtk3
            libusb1
            libuuid
            libxcrypt
            libxcrypt-legacy
            libxml2
            ncurses5
            stdenv.cc.cc
            udev
            zlib
            xorg.libX11
            xorg.libXext
            xorg.libXft
            xorg.libXi
            xorg.libXrender
            xorg.libXtst
            xorg.libxcb
          ];
          multiPkgs = pkgs: with pkgs; [
            libuuid
            libxcrypt-legacy
            ncurses5
            zlib
          ];
          runScript = "bash";
        }).env;
      };
    };
}
