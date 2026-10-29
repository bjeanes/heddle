# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

{
  stdenv,
  stdenvNoCC,
  fetchurl,
  chez,
  pkg-config,
  xxd,
  libuuid,
  lz4,
  ncurses,
  openssl,
  zlib,
  version,
  release,
  jolt-src,
}:

let
  fromRelease = stdenvNoCC.mkDerivation {
    pname = "jolt";
    inherit version;
    src = fetchurl {
      url = "https://github.com/jolt-lang/jolt/releases/download/v${version}/jolt-v${version}-${release.platform}.tar.gz";
      inherit (release) sha256;
    };
    installPhase = ''
      install -Dm755 jolt $out/bin/jolt
    '';
  };

  # Built the way Jolt's own flake builds it.
  fromSource = stdenv.mkDerivation {
    pname = "jolt";
    inherit version;
    src = jolt-src;
    strictDeps = true;
    nativeBuildInputs = [
      chez
      pkg-config
      xxd
    ];
    buildInputs = [
      libuuid
      lz4
      ncurses
      openssl
      zlib
    ];
    JOLT_VERSION = "v${version}";
    dontConfigure = true;
    buildPhase = ''
      runHook preBuild
      scheme --script host/chez/build-jolt.ss release target/release/jolt
      runHook postBuild
    '';
    installPhase = ''
      runHook preInstall
      install -Dm755 target/release/jolt $out/bin/jolt
      runHook postInstall
    '';
  };
in
if release != null then fromRelease else fromSource
