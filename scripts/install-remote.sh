#!/bin/sh
# ResearchZosho one-line installer.
#
#   curl -fsSL https://researchzosho.org/install | sh
#
# Downloads the release from GitHub, checks it against the release's own SHA256SUMS, and installs it.
# Only this script comes from wherever you fetched it. The program and the checksums both come from
# the same GitHub release, so this script cannot hand you something those checksums do not match. If
# you would rather read it before running it, that is a good habit: fetch it, read it, then run it.
#
#   RESEARCHZOSHO_VERSION=0.1.0   install a specific release instead of the latest
#   RESEARCHZOSHO_PREFIX=~/.local where to install (default ~/.local, or /usr/local when run as root)
set -eu

REPO="Wyrdsekai/researchzosho"
BASE="${RESEARCHZOSHO_DOWNLOAD_BASE:-}"        # test hook; empty means GitHub
PREFIX="${RESEARCHZOSHO_PREFIX:-}"
[ -n "$PREFIX" ] || { [ "$(id -u)" = "0" ] && PREFIX=/usr/local || PREFIX="$HOME/.local"; }

die() { printf 'researchzosho: %s\n' "$*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

# Java is the one thing not included. Say so before downloading something you cannot run.
if ! have java; then
    if have apt; then
        die "java not found. ResearchZosho needs Java 21 or newer.
  On Debian or Ubuntu:  sudo apt install default-jre-headless   then run this again."
    fi
    die "java not found. ResearchZosho needs Java 21 or newer on PATH (https://adoptium.net)"
fi
JV=$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')
[ "${JV:-0}" -ge 21 ] 2>/dev/null || die "java $JV found. ResearchZosho needs 21 or newer"
have curl || die "curl not found"
have tar  || die "tar not found"

VER="${RESEARCHZOSHO_VERSION:-}"
if [ -z "$VER" ] && [ -z "$BASE" ]; then
    VER=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" \
          | sed -n 's/.*"tag_name": *"v\{0,1\}\([^"]*\)".*/\1/p' | head -1)
    [ -n "$VER" ] || die "could not find the latest release. Set RESEARCHZOSHO_VERSION"
fi
[ -n "$BASE" ] || BASE="https://github.com/$REPO/releases/download/v$VER"

TAR="researchzosho-$VER.tar.gz"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT INT TERM

printf 'researchzosho: downloading %s\n' "$TAR"
curl -fsSL "$BASE/$TAR" -o "$TMP/$TAR" || die "download failed: $BASE/$TAR"
curl -fsSL "$BASE/SHA256SUMS" -o "$TMP/SHA256SUMS" || die "no SHA256SUMS in the release. Refusing to install something unchecked"

# A download that does not match is not installed: a broken download and a swapped one look the same until you check.
EXPECT=$(sed -n "s/^\([0-9a-f]\{64\}\)  *\.\{0,1\}\/\{0,1\}$TAR\$/\1/p" "$TMP/SHA256SUMS" | head -1)
[ -n "$EXPECT" ] || die "$TAR is not listed in SHA256SUMS"
if have sha256sum; then ACTUAL=$(sha256sum "$TMP/$TAR" | cut -d' ' -f1)
elif have shasum;   then ACTUAL=$(shasum -a 256 "$TMP/$TAR" | cut -d' ' -f1)
else die "no sha256sum or shasum found. Cannot check the download"; fi
[ "$EXPECT" = "$ACTUAL" ] || die "checksum mismatch for $TAR. Refusing to install
  expected $EXPECT
  got      $ACTUAL"
printf 'researchzosho: checksum verified\n'

LIBDIR="$PREFIX/share/researchzosho"
mkdir -p "$TMP/x" "$PREFIX/bin" "$(dirname "$LIBDIR")"
tar xzf "$TMP/$TAR" -C "$TMP/x"
rm -rf "$LIBDIR"
mv "$TMP/x/researchzosho" "$LIBDIR"

# A wrapper, never a symlink: on Windows under MSYS a symlink becomes a copy, and the launcher
# works out its own location from where it was called.
for NAME in researchzosho zosho; do
    printf '#!/bin/sh\nexec "%s/bin/%s" "$@"\n' "$LIBDIR" "$NAME" > "$PREFIX/bin/$NAME"
    chmod +x "$PREFIX/bin/$NAME"
done

printf 'researchzosho: installed %s/bin/researchzosho (and zosho, the short form)\n' "$PREFIX"
case ":$PATH:" in
    *":$PREFIX/bin:"*) ;;
    *) printf 'researchzosho: %s is not on your PATH. Add it:\n      export PATH="%s/bin:$PATH"\n' \
              "$PREFIX/bin" "$PREFIX" ;;
esac
printf '\nnext:  researchzosho setup      # a few questions, then your first document and your first answer\n'
