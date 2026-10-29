# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

{
  lib,
  stdenvNoCC,
  fetchurl,
  autoPatchelfHook,
  ncurses,
  version,
  release,
}:

stdenvNoCC.mkDerivation {
  pname = "jolt";
  inherit version;
  src = fetchurl {
    url = "https://github.com/jolt-lang/jolt/releases/download/v${version}/jolt-v${version}-${release.platform}.tar.gz";
    inherit (release) sha256;
  };
  nativeBuildInputs = lib.optionals stdenvNoCC.hostPlatform.isLinux [ autoPatchelfHook ];
  buildInputs = lib.optionals stdenvNoCC.hostPlatform.isLinux [ ncurses ];
  installPhase = ''
    install -Dm755 jolt $out/bin/jolt
  '';
}
