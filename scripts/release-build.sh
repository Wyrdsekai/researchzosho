#!/usr/bin/env bash
# Build the release artifact and its checksums, from this tree, on this machine.
#
#   scripts/release-build.sh            -> dist/researchzosho-<version>.tar.gz + dist/SHA256SUMS
#
# The version is the one in build.gradle.kts. CI signs what is published; it builds nothing (see
# .github/workflows/release.yml and docs/RELEASING.md).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
VER="$(sed -n 's/^ *version = "\(.*\)"/\1/p' build.gradle.kts | head -1)"
[[ -n "$VER" ]] || { echo "no version in build.gradle.kts" >&2; exit 1; }
[[ "$VER" != *-SNAPSHOT ]] || { echo "version $VER is a snapshot; a release is not" >&2; exit 1; }
./gradlew -q :librarian:installDist
rm -rf dist && mkdir -p dist
tar czf "dist/researchzosho-$VER.tar.gz" -C librarian/build/install researchzosho
( cd dist && sha256sum "researchzosho-$VER.tar.gz" > SHA256SUMS )
ls -l dist
echo "publish: gh release create v$VER dist/researchzosho-$VER.tar.gz dist/SHA256SUMS --repo Wyrdsekai/researchzosho --title v$VER --notes-file <notes>"
