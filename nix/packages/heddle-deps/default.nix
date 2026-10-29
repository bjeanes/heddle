# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

# Jolt's cache of heddle's git deps, fetched with network access so that the
# heddle build can run without it.
{
  lib,
  stdenvNoCC,
  cacert,
  git,
  jolt,
}:

stdenvNoCC.mkDerivation {
  name = "heddle-deps";
  src = lib.fileset.toSource {
    root = ../../..;
    fileset = ../../../deps.edn;
  };
  nativeBuildInputs = [
    jolt
    cacert
    git
  ];
  JOLT_NO_USER_DEPS = "1";
  buildPhase = ''
    runHook preBuild
    export HOME=$TMPDIR
    JOLT_GITLIBS_DIR=$out/gitlibs jolt -P
    runHook postBuild
  '';
  # The checkouts' .git directories can differ from one fetch to the next.
  # Since Jolt doesn't need them, they're removed to keep the hash stable.
  installPhase = ''
    runHook preInstall
    find $out -name .git -prune -exec rm -rf {} +
    runHook postInstall
  '';
  dontFixup = true;
  outputHashMode = "recursive";
  outputHash = "sha256-YeRBmqUXiCyPJF+IsuZbmOeo+u+KJKnnaRCubq2T3Ds=";
}
