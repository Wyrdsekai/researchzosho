#!/bin/bash
# ResearchZosho platform pass. Args: <dir with researchzosho-X.Y.Z*.tar.gz + SHA256SUMS> <version> [<install script>, default <dir>/install]
# Installs through the one-liner from a local HTTP server, drives the new verbs on a fresh library, fetches the pages,
# installs the service, swaps in a fake next version through `update now`, checks the service came back, uninstalls.
set -u
DIST="$1"; VER="$2"; INSTALL="${3:-$DIST/install}"; NEXT="${VER%.*}.$(( ${VER##*.} + 1 ))"
P=0; F=0; pass() { echo "  ok   $1"; P=$((P+1)); }; fail() { echo "  FAIL $1: $2"; F=$((F+1)); }
check() { local name="$1"; shift; local out; if out=$("$@" 2>&1); then pass "$name"; else fail "$name" "$(echo "$out" | tail -2 | tr '\n' ' ')"; fi; }
expect() { local name="$1" want="$2"; shift 2; local out; out=$("$@" 2>&1); if echo "$out" | grep -q -- "$want"; then pass "$name"; else fail "$name" "wanted '$want', got: $(echo "$out" | tail -2 | tr '\n' ' ')"; fi; }
W=$(mktemp -d); export HOME_SAVE="$HOME"
export RESEARCHZOSHO_PREFIX="$W/prefix" RESEARCHZOSHO_CONFIG="$W/config" RESEARCHZOSHO_LIBRARY="$W/lib"
mkdir -p "$W/www"; cp "$DIST"/researchzosho-"$VER"*.tar.gz "$DIST/SHA256SUMS" "$W/www/"
# a fake next release: the same programs under the next version's name (the tarball and every platform build), their own checksums
for f in "$DIST"/researchzosho-"$VER"*.tar.gz; do cp "$f" "$W/www/$(basename "$f" | sed "s/$VER/$NEXT/")"; done
( cd "$W/www" && (sha256sum researchzosho-"$NEXT"*.tar.gz 2>/dev/null || shasum -a 256 researchzosho-"$NEXT"*.tar.gz) >> SHA256SUMS )
PORT=$(( 20000 + RANDOM % 20000 )); ( cd "$W/www" && python3 -m http.server $PORT --bind 127.0.0.1 >/dev/null 2>&1 & echo $! > "$W/http.pid" ); sleep 1
export RESEARCHZOSHO_DOWNLOAD_BASE="http://127.0.0.1:$PORT"
echo "== install $VER from the one-liner (local server)"
check "install" env RESEARCHZOSHO_VERSION="$VER" sh "$INSTALL"
Z="$RESEARCHZOSHO_PREFIX/bin/researchzosho"; [ -x "$Z" ] || Z="$(ls "$RESEARCHZOSHO_PREFIX"/researchzosho*/bin/researchzosho 2>/dev/null | head -1)"
expect "version" "$VER" "$Z" --version
echo "== a fresh library and the new verbs"
check "init" "$Z" init
check "questions add" "$Z" questions add "How were the gears of the Antikythera mechanism cut?"
check "questions add 2" "$Z" questions add "What did the 1978 lead paint ban set as the limit?"
expect "questions list" "Antikythera" "$Z" questions list
expect "questions park" "parked" "$Z" questions park 1
expect "questions list --parked" "parked" "$Z" questions list --parked
expect "questions unpark" "back in the queue" "$Z" questions unpark "How were the gears of the Antikythera mechanism cut?"
expect "questions list --grep" "1 shown" "$Z" questions list --grep "lead paint"
expect "questions tidy" "no duplicate" "$Z" questions tidy
expect "questions budget" "1 run" "$Z" questions budget 1
expect "shelf add" "every 3 days" "$Z" shelf add gears "antikythera gears cutting" 3
expect "shelf park" "parked" "$Z" shelf park gears
expect "tonight shows parked" "parked" "$Z" tonight
expect "shelf unpark" "back in the rotation" "$Z" shelf unpark gears
expect "inbox empty" "nothing awaits" "$Z" inbox
expect "refresh" "refreshed" "$Z" refresh
expect "update status" "$VER" "$Z" update status
expect "embed status" "embeddings server" "$Z" embed status
echo "== the pages"
PORT2=$(( 20000 + RANDOM % 20000 ))
( "$Z" serve --host 127.0.0.1 --port $PORT2 --log "$W/serve.log" >/dev/null 2>&1 & echo $! > "$W/serve.pid" )
for i in $(seq 1 30); do curl -s -o /dev/null "http://127.0.0.1:$PORT2/" && break; sleep 1; done
expect "page /questions" "Open questions" curl -s "http://127.0.0.1:$PORT2/questions?show=all"
expect "page /questions grouped" "Park" curl -s "http://127.0.0.1:$PORT2/questions"
expect "page /inbox" "Nothing awaits" curl -s "http://127.0.0.1:$PORT2/inbox"
expect "page /jobs pause button" "Pause the runner" curl -s "http://127.0.0.1:$PORT2/jobs"
expect "csp allows the pick form's script" "unsafe-inline" curl -sI "http://127.0.0.1:$PORT2/questions"
expect "http frontier filters" "Antikythera" curl -s -X POST "http://127.0.0.1:$PORT2/v1/frontier" -H 'Content-Type: application/json' -d '{"op":"list","q":"gears"}'
expect "http inbox" "items" curl -s -X POST "http://127.0.0.1:$PORT2/v1/inbox" -H 'Content-Type: application/json' -d '{"op":"list"}'
expect "http serials parked flag" "parked" curl -s -X POST "http://127.0.0.1:$PORT2/v1/serials" -H 'Content-Type: application/json' -d '{"op":"list"}'
kill $(cat "$W/serve.pid") 2>/dev/null; sleep 1
echo "== the build with its own Java runtime: installed on request, runs with no Java of its own, updates to its own kind"
RT="$W/prefix-rt"
check "install (own runtime)" env RESEARCHZOSHO_VERSION="$VER" RESEARCHZOSHO_RUNTIME=1 RESEARCHZOSHO_PREFIX="$RT" sh "$INSTALL"
expect "the runtime came with it" "java" ls "$RT/share/researchzosho/jre/bin"
expect "version (own runtime, JAVA_HOME pointing nowhere)" "$VER" env JAVA_HOME=/nonexistent "$RT/bin/researchzosho" --version
if [ "$(uname)" = Darwin ]; then
  # What a copy saved through a browser meets: every file quarantined, then Gatekeeper's verdict at exec. The runtime's
  # binaries are Temurin's own, signed and notarized by Eclipse Adoptium (Developer ID), and jlink copies them unchanged;
  # everything we add is jars and text, which Gatekeeper does not assess. So no signing of our own — and this proves it.
  find "$RT/share/researchzosho" -type f -exec xattr -w com.apple.quarantine "0083;$(printf '%x' "$(date +%s)");Safari;" {} \; 2>/dev/null
  expect "the runtime's java carries a Developer ID signature" "Authority=Developer ID Application" codesign -dvv "$RT/share/researchzosho/jre/bin/java"
  check "every Mach-O in the build has a valid signature" sh -c 'for f in $(find "$1" -type f); do file -b "$f" | grep -q Mach-O || continue; codesign --verify --strict "$f" || exit 1; done' _ "$RT/share/researchzosho"
  expect "runs with every file quarantined" "$VER" env JAVA_HOME=/nonexistent "$RT/bin/researchzosho" --version
fi
check "init (own runtime)" env RESEARCHZOSHO_CONFIG="$W/config-rt" RESEARCHZOSHO_LIBRARY="$W/lib-rt" "$RT/bin/researchzosho" init
expect "update now keeps its own runtime" "$NEXT" env RESEARCHZOSHO_CONFIG="$W/config-rt" RESEARCHZOSHO_LIBRARY="$W/lib-rt" "$RT/bin/researchzosho" update now "$NEXT" --no-restart
expect "the runtime is still there after the update" "java" ls "$RT/share/researchzosho/jre/bin"
expect "version after the update (own runtime)" "$NEXT\|$VER" env JAVA_HOME=/nonexistent "$RT/bin/researchzosho" --version
echo "== the service, and the update path with a restart"
# never on a box that already runs the service: the unit name is shared, and an uninstall here would remove the live one
LIVE=""
case "$(uname)" in Darwin) launchctl print "gui/$(id -u)/org.researchzosho.librarian" >/dev/null 2>&1 && LIVE=yes ;; *) systemctl --user cat researchzosho >/dev/null 2>&1 && LIVE=yes ;; esac
if [ -n "$LIVE" ]; then echo "  skip service: this box already runs the researchzosho service; the update swap is tested without a restart"
  expect "update now swaps in $NEXT (no service)" "$NEXT" "$Z" update now "$NEXT" --no-restart
  expect "the swapped-in program answers" "$NEXT\|$VER" "$Z" --version
else
check "service install" "$Z" service install
sleep 6
expect "service status" "running\|active\|loaded\|installed" "$Z" service status
expect "update now swaps in $NEXT" "$NEXT" "$Z" update now "$NEXT"
sleep 8
expect "the swapped-in program answers" "$NEXT\|$VER" "$Z" --version
expect "service came back" "running\|active\|loaded\|installed" "$Z" service status
check "service uninstall" "$Z" service uninstall
fi
kill $(cat "$W/http.pid") 2>/dev/null
# the work dir goes with a clean pass (a day of passes left 14 GB on one box); a failed one keeps it for reading
if [ "$F" -eq 0 ]; then rm -rf "$W"; echo "  $P passed, $F failed"; else echo "  $P passed, $F failed   (work dir kept: $W)"; fi
[ $F -eq 0 ]
