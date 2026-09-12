#!/bin/sh
# The library is made on first start; every argument goes to the program (serve by default, mcp for stdio).
set -e
mkdir -p "$(dirname "$RESEARCHZOSHO_CONFIG")"
[ -d "$RESEARCHZOSHO_LIBRARY/catalog" ] || researchzosho init >&2
exec researchzosho "$@"
