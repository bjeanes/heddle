{
  description = "monorepo-lsp: one language server proxy for a whole monorepo";

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
      packages = forEachSystem (pkgs: {
        jolt =
          let
            release = joltReleases.${pkgs.stdenv.hostPlatform.system};
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
      });

      devShells = forEachSystem (pkgs: {
        default = pkgs.mkShellNoCC {
          packages = [ self.packages.${pkgs.stdenv.hostPlatform.system}.jolt ];
        };
      });
    };
}
