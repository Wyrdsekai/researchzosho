#!/bin/bash
# ResearchZosho platform pass. Args: <dir with researchzosho-X.Y.Z*.tar.gz + SHA256SUMS> <version> [<install script>, default <dir>/install]
# Installs through the one-liner from a local HTTP server, drives the new verbs on a fresh library, fetches the pages,
# installs the service, swaps in a fake next version through `update now`, checks the service came back, uninstalls.
#
# Everything the pass writes stays in its work folder. Every command runs with Java's user.home there (JAVA_OPTS), so
# the state directory (the server's pid record, the logs, the version cache) is the work folder's and never this
# machine's own, and a service that already runs here is left alone. The pass calls no model: the drive is a closed port.
set -u
DIST="$1"; VER="$2"; INSTALL="${3:-$DIST/install}"; NEXT="${VER%.*}.$(( ${VER##*.} + 1 ))"
P=0; F=0; pass() { echo "  ok   $1"; P=$((P+1)); }; fail() { echo "  FAIL $1: $2"; F=$((F+1)); }
check() { local name="$1"; shift; local out; if out=$("$@" 2>&1); then pass "$name"; else fail "$name" "$(echo "$out" | tail -2 | tr '\n' ' ')"; fi; }
expect() { local name="$1" want="$2"; shift 2; local out; out=$("$@" 2>&1); if echo "$out" | grep -q -- "$want"; then pass "$name"; else fail "$name" "wanted '$want', got: $(echo "$out" | tail -2 | tr '\n' ' ')"; fi; }
# wait up to $1 seconds for a command to succeed
within() { local n="$1"; shift; for _ in $(seq 1 "$n"); do "$@" >/dev/null 2>&1 && return 0; sleep 1; done; "$@" >/dev/null 2>&1; }
W=$(mktemp -d)
export RESEARCHZOSHO_PREFIX="$W/prefix" RESEARCHZOSHO_CONFIG="$W/config" RESEARCHZOSHO_LIBRARY="$W/lib"
export JAVA_OPTS="-Duser.home=$W/home" RESEARCHZOSHO_DRIVE="http://127.0.0.1:9"
mkdir -p "$W/home" "$W/www"
HTTP_PID=""; SERVE_PID=""; UNIT_LINK=""
cleanup() {
  [ -n "$SERVE_PID" ] && kill "$SERVE_PID" 2>/dev/null
  [ -n "$HTTP_PID" ] && { kill "$HTTP_PID" 2>/dev/null; wait "$HTTP_PID" 2>/dev/null; }
  [ -n "$UNIT_LINK" ] && { rm -f "$UNIT_LINK"; systemctl --user daemon-reload 2>/dev/null; }
}
trap cleanup EXIT
cp "$DIST"/researchzosho-"$VER"*.tar.gz "$DIST/SHA256SUMS" "$W/www/"
# A fake next release: each tarball again, its program's manifest saying the next version, with its own checksum.
# The jar keeps its file name, so the start scripts find it; `--version` and the pages say the next version.
fake_next() {
python3 - "$1" "$2" "$NEXT" <<'PY'
import io, re, sys, tarfile, zipfile
src, dst, nxt = sys.argv[1:4]
with tarfile.open(src, 'r:gz') as tin, tarfile.open(dst, 'w:gz') as tout:
    for m in tin:
        data = tin.extractfile(m).read() if m.isfile() else None
        if data is not None and re.fullmatch(r'researchzosho/lib/librarian-[^/]+\.jar', m.name):
            zin = zipfile.ZipFile(io.BytesIO(data)); buf = io.BytesIO()
            with zipfile.ZipFile(buf, 'w') as zout:
                for zi in zin.infolist():
                    b = zin.read(zi)
                    if zi.filename == 'META-INF/MANIFEST.MF':
                        b = re.sub(rb'Implementation-Version: [^\r\n]*', b'Implementation-Version: ' + nxt.encode(), b)
                    zout.writestr(zi, b)
            data = buf.getvalue(); m.size = len(data)
        tout.addfile(m, io.BytesIO(data) if data is not None else None)
PY
}
for f in "$DIST"/researchzosho-"$VER"*.tar.gz; do fake_next "$f" "$W/www/$(basename "$f" | sed "s/$VER/$NEXT/")" || echo "  (could not make the fake $NEXT from $f)"; done
( cd "$W/www" && (sha256sum researchzosho-"$NEXT"*.tar.gz 2>/dev/null || shasum -a 256 researchzosho-"$NEXT"*.tar.gz) >> SHA256SUMS )
# The version in the manifest of the program installed at $1 (its lib/librarian-*.jar)
jar_version() { unzip -p "$1"/lib/librarian-*.jar META-INF/MANIFEST.MF | tr -d '\r' | sed -n 's/^Implementation-Version: *//p'; }
# what a server on port $1 says it is
served_version() { curl -s --max-time 3 "http://127.0.0.1:$1/v1/status" | sed -n 's/.*"version":"\([^"]*\)".*/\1/p'; }
serves() { [ "$(served_version "$1")" = "$2" ]; }
PORT=$(( 20000 + RANDOM % 20000 ))
python3 -m http.server "$PORT" --bind 127.0.0.1 --directory "$W/www" >/dev/null 2>&1 & HTTP_PID=$!
within 10 curl -s -o /dev/null "http://127.0.0.1:$PORT/SHA256SUMS"
export RESEARCHZOSHO_DOWNLOAD_BASE="http://127.0.0.1:$PORT"
echo "== install $VER from the one-liner (local server)"
check "install" env RESEARCHZOSHO_VERSION="$VER" sh "$INSTALL"
Z="$RESEARCHZOSHO_PREFIX/bin/researchzosho"; [ -x "$Z" ] || Z="$(ls "$RESEARCHZOSHO_PREFIX"/researchzosho*/bin/researchzosho 2>/dev/null | head -1)"
ROOT="$RESEARCHZOSHO_PREFIX/share/researchzosho"
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
expect "questions budget" "1 research run" "$Z" questions budget 1
echo "== a database: the SQLite driver in this platform's tarball keeps only this platform's native code, so open one for real"
echo "H4sIAAAAAAACA+3XMQrCQBAF0Nk12Mna2U6pIDZeIFEWCUbRdQtTbjBCwCjK4h28lKeysnIFbWzEVv5j/oeBucCsllnlS94eTrXzPKQ2CUExMxHJV95ESPSxfyNpcL60nsfqSmEAAAAAAAAA4GexaHaUEkfvil3pvRwbnVjNNhllmj13qw2nc6sn2vDCpLPE5DzVeZ/3ri7Z6rXtPX9zqW6k7qEAAAAAAAAA4G9EskGqiERo9wCEpOPDACAAAA==" | base64 -d 2>/dev/null | gunzip > "$W/fixture.db" || echo "H4sIAAAAAAACA+3XMQrCQBAF0Nk12Mna2U6pIDZeIFEWCUbRdQtTbjBCwCjK4h28lKeysnIFbWzEVv5j/oeBucCsllnlS94eTrXzPKQ2CUExMxHJV95ESPSxfyNpcL60nsfqSmEAAAAAAAAA4GexaHaUEkfvil3pvRwbnVjNNhllmj13qw2nc6sn2vDCpLPE5DzVeZ/3ri7Z6rXtPX9zqW6k7qEAAAAAAAAA4G9EskGqiERo9wCEpOPDACAAAA==" | base64 -D | gunzip > "$W/fixture.db"
expect "db add (sqlite)" "Added fixture (SQLite)" "$Z" db add fixture "$W/fixture.db"
expect "db query" "| 2 |" "$Z" db query fixture "SELECT count(*) AS n FROM t"
expect "db refuses a write" "Only queries that read" "$Z" db query fixture "DELETE FROM t"
expect "db drivers" "postgres  PostgreSQL — in the box" "$Z" db drivers
check "db remove" "$Z" db remove fixture
expect "shelf add" "every 3 days" "$Z" shelf add gears "antikythera gears cutting" 3
expect "shelf park" "parked" "$Z" shelf park gears
expect "tonight shows parked" "parked" "$Z" tonight
expect "shelf unpark" "back in the rotation" "$Z" shelf unpark gears
expect "inbox empty" "Nothing is waiting for you" "$Z" inbox
expect "refresh" "refreshed" "$Z" refresh
expect "update status" "$VER" "$Z" update status
expect "embed status" "embeddings server" "$Z" embed status
echo "== the pages"
PORT2=$(( 20000 + RANDOM % 20000 ))
"$Z" serve --host 127.0.0.1 --port $PORT2 --log "$W/serve.log" >/dev/null 2>&1 & SERVE_PID=$!
within 40 curl -s -o /dev/null "http://127.0.0.1:$PORT2/"
expect "page /questions" "Open questions" curl -s "http://127.0.0.1:$PORT2/questions?show=all"
expect "page /questions grouped" "Park" curl -s "http://127.0.0.1:$PORT2/questions"
expect "page /inbox" "No claims are waiting" curl -s "http://127.0.0.1:$PORT2/inbox"
expect "page /jobs pause button" "Pause research" curl -s "http://127.0.0.1:$PORT2/jobs"
expect "csp allows the pick form's script" "unsafe-inline" curl -sI "http://127.0.0.1:$PORT2/questions"
expect "http frontier filters" "Antikythera" curl -s -X POST "http://127.0.0.1:$PORT2/v1/frontier" -H 'Content-Type: application/json' -d '{"op":"list","q":"gears"}'
expect "http inbox" "items" curl -s -X POST "http://127.0.0.1:$PORT2/v1/inbox" -H 'Content-Type: application/json' -d '{"op":"list"}'
expect "http serials parked flag" "parked" curl -s -X POST "http://127.0.0.1:$PORT2/v1/serials" -H 'Content-Type: application/json' -d '{"op":"list"}'
expect "the server records its pid in the work folder" "^$SERVE_PID\$" cat "$W/home/.researchzosho/researchzosho.pid"
kill "$SERVE_PID" 2>/dev/null
if within 15 sh -c "! kill -0 $SERVE_PID" && ! curl -s -o /dev/null --max-time 3 "http://127.0.0.1:$PORT2/"; then pass "the pages server stopped"; else fail "the pages server stopped" "pid $SERVE_PID or port $PORT2 still answers"; fi
SERVE_PID=""
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
expect "update now swaps in $NEXT (own runtime)" "$NEXT is in place" env RESEARCHZOSHO_CONFIG="$W/config-rt" RESEARCHZOSHO_LIBRARY="$W/lib-rt" "$RT/bin/researchzosho" update now "$NEXT" --no-restart
expect "the installed jar is $NEXT (own runtime)" "^$NEXT\$" jar_version "$RT/share/researchzosho"
expect "the runtime is still there after the update" "java" ls "$RT/share/researchzosho/jre/bin"
expect "version after the update (own runtime)" "researchzosho $NEXT" env JAVA_HOME=/nonexistent "$RT/bin/researchzosho" --version
echo "== the service, and the update path with a restart"
# never on a box that already runs the service: the unit name is shared, and an uninstall here would remove the live one
LIVE=""
case "$(uname)" in Darwin) launchctl print "gui/$(id -u)/org.researchzosho.librarian" >/dev/null 2>&1 && LIVE="the researchzosho service already runs here" ;; *) systemctl --user cat researchzosho >/dev/null 2>&1 && LIVE="the researchzosho service already runs here" ;; esac
[ -z "$LIVE" ] && curl -s -o /dev/null --max-time 3 "http://127.0.0.1:4649/" && LIVE="something already answers on the service's port 4649"
if [ -n "$LIVE" ]; then echo "  skip service: $LIVE; the update swap is tested without a restart"
  expect "update now swaps in $NEXT (no service)" "$NEXT is in place" "$Z" update now "$NEXT" --no-restart
  expect "the installed jar is $NEXT" "^$NEXT\$" jar_version "$ROOT"
  expect "the swapped-in program answers" "researchzosho $NEXT" "$Z" --version
else
  if [ "$(uname)" != Darwin ]; then
    # systemd reads user units only from ~/.config/systemd/user: link the work folder's unit there for the pass
    UNIT_LINK="$HOME/.config/systemd/user/researchzosho.service"; mkdir -p "$(dirname "$UNIT_LINK")"
    ln -s "$W/home/.config/systemd/user/researchzosho.service" "$UNIT_LINK"
  fi
  check "service install" "$Z" service install
  if within 60 serves 4649 "$VER"; then pass "the service serves $VER"; else fail "the service serves $VER" "port 4649 says '$(served_version 4649)'"; fi
  expect "update now swaps in $NEXT" "$NEXT is in place" "$Z" update now "$NEXT"
  expect "the installed jar is $NEXT" "^$NEXT\$" jar_version "$ROOT"
  expect "the swapped-in program answers" "researchzosho $NEXT" "$Z" --version
  if within 60 serves 4649 "$NEXT"; then pass "the service came back serving $NEXT"; else fail "the service came back serving $NEXT" "port 4649 says '$(served_version 4649)'"; fi
  # a restart: a new server process, serving again
  OLD_PID="$(cat "$W/home/.researchzosho/researchzosho.pid" 2>/dev/null)"
  expect "service restart" "Restarted the service" "$Z" service restart --yes
  if within 60 sh -c "[ \"\$(cat '$W/home/.researchzosho/researchzosho.pid' 2>/dev/null)\" != '$OLD_PID' ]"; then pass "the restart started a new server (was pid $OLD_PID)"; else fail "the restart started a new server" "the pid is still $OLD_PID"; fi
  if within 60 serves 4649 "$NEXT"; then pass "the service serves $NEXT after the restart"; else fail "the service serves $NEXT after the restart" "port 4649 says '$(served_version 4649)'"; fi
  check "service uninstall" "$Z" service uninstall
  if within 20 sh -c "! curl -s -o /dev/null --max-time 2 http://127.0.0.1:4649/"; then pass "the service's server stopped"; else fail "the service's server stopped" "port 4649 still answers"; fi
  [ -n "$UNIT_LINK" ] && { rm -f "$UNIT_LINK"; systemctl --user daemon-reload; UNIT_LINK=""; }
fi
kill "$HTTP_PID" 2>/dev/null; wait "$HTTP_PID" 2>/dev/null; HTTP_PID=""   # wait: reaped quietly, or bash 3.2 announces "Terminated"
# the work dir goes with a clean pass (a day of passes left 14 GB on one box); a failed one keeps it for reading
if [ "$F" -eq 0 ]; then rm -rf "$W"; echo "  $P passed, $F failed"; else echo "  $P passed, $F failed   (work dir kept: $W)"; fi
[ $F -eq 0 ]
