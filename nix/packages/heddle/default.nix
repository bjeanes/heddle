# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

{
  lib,
  stdenvNoCC,
  jolt,
  heddle-deps,
}:

stdenvNoCC.mkDerivation {
  pname = "heddle";
  version = "0-unstable";
  src = lib.fileset.toSource {
    root = ../../..;
    fileset = lib.fileset.unions [
      ../../../deps.edn
      ../../../src
    ];
  };
  nativeBuildInputs = [ jolt ];
  JOLT_GITLIBS_DIR = "${heddle-deps}/gitlibs";
  JOLT_NO_USER_DEPS = "1";
  buildPhase = ''
    runHook preBuild
    export HOME=$TMPDIR
    jolt build -m heddle.main -o heddle
    runHook postBuild
  '';
  installPhase = ''
    runHook preInstall
    install -Dm755 heddle $out/bin/heddle
    runHook postInstall
  '';
  doInstallCheck = true;
  installCheckPhase = ''
    runHook preInstallCheck
    $out/bin/heddle --help | grep '^usage: heddle' > /dev/null
    runHook postInstallCheck
  '';
  meta.mainProgram = "heddle";
}
