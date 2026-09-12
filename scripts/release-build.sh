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
# the launcher on npm, the registry entry and the compose file all name this version: they move together
for f in npm/package.json server.json; do
    V=$(python3 -c "import json,sys; print(json.load(open('$f'))['version'])")
    [[ "${V%%-*}" == "$VER" ]] || { echo "$f says $V, build.gradle.kts says $VER — bump them together" >&2; exit 1; }   # an npm-only suffix (0.1.6-1) is allowed
done
grep -q "researchzosho:$VER\b" docker-compose.yml || { echo "docker-compose.yml does not name researchzosho:$VER" >&2; exit 1; }
grep -q "\"identifier\": \"ghcr.io/wyrdsekai/researchzosho:$VER\"" server.json || { echo "server.json's image is not :$VER" >&2; exit 1; }
./gradlew -q :librarian:installDist
rm -rf dist && mkdir -p dist
tar czf "dist/researchzosho-$VER.tar.gz" -C librarian/build/install researchzosho
# the builds with their own Java runtime, one per platform, from the tarball just made (scripts/package-runtime.sh)
scripts/package-runtime.sh dist "$VER"
( cd dist && sha256sum researchzosho-*.tar.gz > SHA256SUMS )
ls -l dist
echo "publish: gh release create v$VER dist/researchzosho-*.tar.gz dist/SHA256SUMS --repo Wyrdsekai/researchzosho --title v$VER --notes-file <notes>"
