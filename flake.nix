{
  description = "heddle: define a project's language servers once, for any editor or agent";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";

  outputs =
    { self, nixpkgs }:
    let
      joltVersion = "0.8.23";
      joltReleases = {
        aarch64-darwin = {
          platform = "aarch64-macos";
          sha256 = "ebcb856554c9ef9dfd866883aa2e65e1a42a347cae15c90e16db49b07e08aa63";
        };
        x86_64-linux = {
          platform = "x86_64-linux";
          sha256 = "90031be43241e61d0788a2f1bbd3da56d8f4324ae9fa87271f9637b43a6071f2";
        };
      };
      forEachSystem = f: nixpkgs.lib.genAttrs (builtins.attrNames joltReleases) (system: f nixpkgs.legacyPackages.${system});
    in
    {
      packages = nixpkgs.lib.genAttrs nixpkgs.lib.systems.flakeExposed (
        system:
        let
          pkgs = nixpkgs.legacyPackages.${system};
        in
        {
          vim-plugin = pkgs.vimUtils.buildVimPlugin {
            pname = "heddle";
            version = "0-unstable";
            src = nixpkgs.lib.fileset.toSource {
              root = ./.;
              fileset = ./lua;
            };
            meta.license = nixpkgs.lib.licenses.mit;
          };
        }
        // nixpkgs.lib.optionalAttrs (joltReleases ? ${system}) {
          jolt =
            let
              release = joltReleases.${system};
            in
            pkgs.stdenvNoCC.mkDerivation {
              pname = "jolt";
              version = joltVersion;
              src = pkgs.fetchurl {
                url = "https://github.com/jolt-lang/jolt/releases/download/v${joltVersion}/jolt-v${joltVersion}-${release.platform}.tar.gz";
                inherit (release) sha256;
              };
              nativeBuildInputs = pkgs.lib.optionals pkgs.stdenv.hostPlatform.isLinux [ pkgs.autoPatchelfHook ];
              buildInputs = pkgs.lib.optionals pkgs.stdenv.hostPlatform.isLinux [ pkgs.ncurses ];
              installPhase = ''
                install -Dm755 jolt $out/bin/jolt
              '';
            };
        }
      );

      devShells = forEachSystem (
        pkgs:
        let
          jolt = self.packages.${pkgs.stdenv.hostPlatform.system}.jolt;
          install-heddle = pkgs.writeShellScriptBin "install-heddle" ''
            # Usage: install-heddle [DIR], from the repo root. DIR defaults to ~/bin.
            set -eu
            dest=''${1:-$HOME/bin}
            if [ ! -f src/heddle/main.clj ]; then
              echo "install-heddle: run this from the root of the heddle repo" >&2
              exit 1
            fi
            tmp=$(mktemp -d)
            trap 'rm -rf "$tmp"' EXIT
            ${jolt}/bin/jolt build -m heddle.main -o "$tmp/heddle"
            mkdir -p "$dest"
            # Replace the file rather than writing over it, since macOS can kill
            # a signed binary whose file was changed in place.
            cp "$tmp/heddle" "$dest/.heddle.new"
            mv -f "$dest/.heddle.new" "$dest/heddle"
            echo "Installed $dest/heddle"
          '';
        in
        {
          default = pkgs.mkShellNoCC {
            packages = [
              jolt
              install-heddle
            ];
          };
        }
      );
    };
}
