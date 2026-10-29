# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

{
  description = "heddle: define a project's language servers once, for any editor or agent";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
  inputs.jolt-src = {
    type = "git";
    url = "https://github.com/jolt-lang/jolt";
    ref = "refs/tags/v0.8.23";
    submodules = true;
    flake = false;
  };

  outputs =
    {
      self,
      nixpkgs,
      jolt-src,
    }:
    let
      systems = [
        "aarch64-darwin"
        "aarch64-linux"
        "x86_64-linux"
      ];
      forEachSystem = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});

      joltVersion = "0.8.23";
      # Jolt also publishes an x86_64 Linux release. Since it segfaults before
      # main once patched for Nix, the flake builds Jolt from source on Linux.
      joltReleases = {
        aarch64-darwin = {
          platform = "aarch64-macos";
          sha256 = "ebcb856554c9ef9dfd866883aa2e65e1a42a347cae15c90e16db49b07e08aa63";
        };
      };
    in
    {
      formatter = forEachSystem (pkgs: pkgs.nixfmt);

      packages = forEachSystem (
        pkgs:
        let
          packages = self.packages.${pkgs.stdenv.hostPlatform.system};
        in
        {
          vim-plugin = pkgs.callPackage ./nix/packages/vim-plugin { };

          jolt = pkgs.callPackage ./nix/packages/jolt {
            inherit jolt-src;
            version = joltVersion;
            release = joltReleases.${pkgs.stdenv.hostPlatform.system} or null;
          };

          heddle-deps = pkgs.callPackage ./nix/packages/heddle-deps { inherit (packages) jolt; };

          heddle = pkgs.callPackage ./nix/packages/heddle { inherit (packages) jolt heddle-deps; };

          default = packages.heddle;
        }
      );

      devShells = forEachSystem (pkgs: {
        default = pkgs.mkShellNoCC {
          packages = [ self.packages.${pkgs.stdenv.hostPlatform.system}.jolt ];
        };
      });
    };
}
