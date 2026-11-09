#!/bin/sh
# The fleet: one library per container, one container per volume, the router's route table as the list of what runs where.
#
#   scripts/fleet.sh create <name>              a new library: its volumes, its container on the router's network, its route
#   scripts/fleet.sh list                       every library the router knows, and where it is
#   scripts/fleet.sh backup <name> [dir]        the library and its settings as one tar, dated, in dir (default backups/)
#   scripts/fleet.sh move <name> <user@host>    stop it here, carry its volumes to that server (same layout), start it there, route to it
#   scripts/fleet.sh remove <name>              stop and remove the container and the route; the volumes stay until you remove them
#
# RZ_DRY_RUN=1 prints every command instead of running it. RZ_DOMAIN (localhost), RZ_IMAGE, RZ_DRIVE, RZ_MODEL,
# RZ_HTTPS=1 (a route without http://, so the router gets a certificate) are read as docker-compose.cloud.yml reads them.
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
routes="$here/deploy/caddy/routes"
domain=${RZ_DOMAIN:-localhost}
image=${RZ_IMAGE:-ghcr.io/wyrdsekai/researchzosho:0.7.1}
drive=${RZ_DRIVE:-http://host.docker.internal:8080}
model=${RZ_MODEL:-}
trusted=${RZ_TRUSTED_PROXY:-10.0.0.0/8,172.16.0.0/12,192.168.0.0/16}
project=${RZ_PROJECT:-$(basename "$here" | tr -c 'a-z0-9\n' '-' | sed 's/-*$//')}
network="${project}_default"
router="${project}-router-1"

run() { if [ "${RZ_DRY_RUN:-}" = 1 ]; then echo "+ $*"; else "$@"; fi; }
say() { echo "$*"; }
scheme() { if [ "${RZ_HTTPS:-}" = 1 ]; then echo ""; else echo "http://"; fi; }
name_ok() { case "$1" in ''|*[!a-z0-9-]*) echo "A library's name is lower-case letters, digits and dashes: $1 is not." >&2; exit 2;; esac; }
reload() { run docker exec "$router" caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile || say "The router did not reload; it reads the routes at its next start."; }

case "${1:-}" in
  create)
    name=${2:-}; name_ok "$name"
    [ -f "$routes/$name.caddy" ] && { echo "A library named $name is routed already ($routes/$name.caddy)." >&2; exit 1; }
    run docker volume create "rz-$name-library" >/dev/null
    run docker volume create "rz-$name-config" >/dev/null
    run docker run -d --name "rz-$name" --network "$network" --restart unless-stopped \
      -v "rz-$name-library:/library" -v "rz-$name-config:/config" \
      -e "RESEARCHZOSHO_DRIVE=$drive" -e "RESEARCHZOSHO_MODEL=$model" -e RESEARCHZOSHO_EMBED=http://embed:80 \
      -e "RESEARCHZOSHO_TRUSTED_PROXY=$trusted" -e RESEARCHZOSHO_FETCH_PRIVATE=deny -e RESEARCHZOSHO_LOG=stdout \
      --add-host host.docker.internal:host-gateway "$image" serve --host 0.0.0.0 --port 4649
    if [ "${RZ_DRY_RUN:-}" = 1 ]; then echo "+ write $routes/$name.caddy"; else
      mkdir -p "$routes"
      printf '# %s: the library in the container rz-%s on this server, made by scripts/fleet.sh on %s.\n%s%s.{$RZ_DOMAIN} {\n    reverse_proxy rz-%s:4649\n}\n' "$name" "$name" "$(date -u +%Y-%m-%d)" "$(scheme)" "$name" "$name" > "$routes/$name.caddy"
    fi
    reload
    say "The library $name runs in rz-$name and answers at $(scheme)$name.$domain. Its first start makes the library; sign in there and make the keeper's account."
    ;;
  list)
    [ -d "$routes" ] || { say "No libraries are routed yet."; exit 0; }
    for f in "$routes"/*.caddy; do [ -f "$f" ] || continue
      n=$(basename "$f" .caddy); at=$(grep -oE 'reverse_proxy [^ ]+' "$f" | head -1 | cut -d' ' -f2)
      state=$(docker inspect -f '{{.State.Status}}' "rz-$n" 2>/dev/null || echo "not on this server")
      say "$n  at $(scheme)$n.$domain  ->  $at  ($state)"
    done
    ;;
  backup)
    name=${2:-}; name_ok "$name"; dir=${3:-$here/backups}
    stamp=$(date -u +%Y-%m-%dT%H%M%SZ)
    run mkdir -p "$dir"
    run docker run --rm -v "rz-$name-library:/library:ro" -v "rz-$name-config:/config:ro" -v "$dir:/out" alpine \
      tar czf "/out/rz-$name-$stamp.tar.gz" -C / library config
    say "Backed up: $dir/rz-$name-$stamp.tar.gz (the library and its settings; restore by untarring into fresh volumes)."
    ;;
  move)
    name=${2:-}; name_ok "$name"; to=${3:-}
    [ -n "$to" ] || { echo "usage: scripts/fleet.sh move <name> <user@host>" >&2; exit 2; }
    say "Stopping rz-$name here; its library goes to $to as a tar, and starts there with the same image and settings."
    run docker stop "rz-$name"
    if [ "${RZ_DRY_RUN:-}" = 1 ]; then
      echo "+ docker run --rm -v rz-$name-library:/library:ro -v rz-$name-config:/config:ro alpine tar czf - -C / library config | ssh $to 'docker volume create rz-$name-library && docker volume create rz-$name-config && docker run --rm -i -v rz-$name-library:/library -v rz-$name-config:/config alpine tar xzf - -C /'"
    else
      docker run --rm -v "rz-$name-library:/library:ro" -v "rz-$name-config:/config:ro" alpine tar czf - -C / library config \
        | ssh "$to" "docker volume create rz-$name-library >/dev/null && docker volume create rz-$name-config >/dev/null && docker run --rm -i -v rz-$name-library:/library -v rz-$name-config:/config alpine tar xzf - -C /"
    fi
    run ssh "$to" "docker run -d --name rz-$name --restart unless-stopped -p 4649 -v rz-$name-library:/library -v rz-$name-config:/config -e RESEARCHZOSHO_DRIVE=$drive -e RESEARCHZOSHO_MODEL=$model -e RESEARCHZOSHO_TRUSTED_PROXY=$trusted -e RESEARCHZOSHO_FETCH_PRIVATE=deny -e RESEARCHZOSHO_LOG=stdout $image serve --host 0.0.0.0 --port 4649"
    host=${to#*@}
    port=$(if [ "${RZ_DRY_RUN:-}" = 1 ]; then echo 4649; else ssh "$to" "docker port rz-$name 4649" | head -1 | sed 's/.*://'; fi)
    if [ "${RZ_DRY_RUN:-}" = 1 ]; then echo "+ write $routes/$name.caddy -> $host:$port"; else
      printf '# %s: the library on %s, moved there by scripts/fleet.sh on %s.\n%s%s.{$RZ_DOMAIN} {\n    reverse_proxy %s:%s\n}\n' "$name" "$host" "$(date -u +%Y-%m-%d)" "$(scheme)" "$name" "$host" "$port" > "$routes/$name.caddy"
    fi
    reload
    run docker rm "rz-$name"
    say "The library $name answers at $(scheme)$name.$domain from $host now. Its volumes here stay until you remove them: docker volume rm rz-$name-library rz-$name-config"
    ;;
  remove)
    name=${2:-}; name_ok "$name"
    run docker stop "rz-$name"   # stopped, never killed: a daemon killed outright leaves its lease in the volume, and the next one on it waits until the lease lapses
    run docker rm "rz-$name"
    run rm -f "$routes/$name.caddy"
    reload
    say "The container and the route for $name are gone. The library itself stays in its volumes until: docker volume rm rz-$name-library rz-$name-config"
    ;;
  *)
    sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 2
    ;;
esac
