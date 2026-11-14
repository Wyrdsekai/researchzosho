# ResearchZosho on a server

One library is one folder with one daemon on it. On a server that is one container with one volume, and
many libraries are many containers behind one router, each at its own name: `alpha.example.com`,
`beta.example.com`. The router ends the encryption and adds the forwarded headers; each library believes
them from the router's network and from nowhere else. Scaling is adding servers and moving libraries
between them; nothing is shared between two libraries, so nothing has to be.

## On one machine, to see it

```
RZ_DOMAIN=localhost docker compose -f docker-compose.cloud.yml up -d    # the router, the embedder, the first library
scripts/fleet.sh create beta                                             # a second library, routed
curl -H 'Host: alpha.localhost' http://127.0.0.1:8080/healthz            # {"ok":true,"version":…,"library_id":…}
scripts/fleet.sh list                                                    # every library and where it is
```

Open `http://alpha.localhost:8080/` in a browser (every browser sends `*.localhost` to this machine). The
first visit makes the keeper's account; `scripts/fleet.sh backup alpha` writes the library and its settings
as one dated tar under `backups/`.

The model is not in the compose: name where it runs in `RZ_DRIVE` (a llama.cpp or Ollama on a machine with a
card, `http://host.docker.internal:8080` for one on the same machine), or a rented one with `researchzosho
models add` inside the library, as the models page says.

## On a real domain

Point `*.example.com` at the server, then:

```
RZ_DOMAIN=example.com RZ_HTTPS=1 docker compose -f docker-compose.cloud.yml up -d
RZ_DOMAIN=example.com RZ_HTTPS=1 scripts/fleet.sh create alpha
```

The router gets a certificate for each name itself. The sign-in cookie is marked Secure, passkeys work,
and the pages say which library they are.

## Who may sign in

Each library keeps its own people. The person who keeps it makes their account at the first visit and
turns the sign-in on (`researchzosho web signin on` inside the container, or the Keep page). Readers are
invited by name, or let in when they ask.

**Through your identity provider.** Register the library at Google Workspace, Entra, Okta or any OpenID
Connect provider as a web client with the callback `https://alpha.example.com/login/oidc/callback`, then
set, for that library:

```
RESEARCHZOSHO_OIDC_ISSUER=https://accounts.google.com     # the provider's issuer address
RESEARCHZOSHO_OIDC_CLIENT_ID=…
RESEARCHZOSHO_OIDC_CLIENT_SECRET=…                        # when the provider gave one
RESEARCHZOSHO_OIDC_ALLOWED_DOMAIN=example.com             # whose people may sign in for the first time, as readers
```

The sign-in page offers "Sign in with <provider>". Someone already listed by their mail is that reader;
someone new from an allowed domain becomes a reader the keeper may raise; anyone else is told to ask.

**Programs.** A signed-in person makes a key on the Keep page, on their account page or with
`researchzosho keys new "<label>" --read|--write`, sees it once, and gives it to the program, which sends
`Authorization: Bearer rz_…` to the library's address or its MCP route (`/rpc`). A read key never writes.

**The proxy.** `RESEARCHZOSHO_TRUSTED_PROXY` names the router's network; the compose sets it to the private
networks Docker uses. Behind another proxy or a cloud load balancer, set it to that proxy's address or
network, or the library takes every browser for the proxy and no one for https. A form on a library's pages is
carried out only when the browser says it was sent from that library's own address, which behind the router is the
host and scheme the router forwards (`X-Forwarded-Host`, or the `Host` it passes on, and `X-Forwarded-Proto`), so a
page on another site, another library's among them, cannot post one in a reader's name. A proxy on the same
computer as a library that listens on 127.0.0.1 is named in `RESEARCHZOSHO_TRUSTED_PROXY` too, or the library refuses
what it forwards under the public name.

**Your own network stays closed.** A library behind a trusted proxy does not fetch addresses on a private network,
so a question or a page cannot send it to the router, another library or anything else inside your network. The
compose and the fleet script also say it outright with `RESEARCHZOSHO_FETCH_PRIVATE=deny`. A library at home
fetches them, so it can read a wiki or a NAS on your own network.

**What each library spends.** Each library counts its own searches and pages read through the paid services, in its own
settings volume, and a monthly limit (`serper_monthly_limit`, `serpapi_monthly_limit`, `brave_monthly_limit`,
`firecrawl_monthly_limit`) holds for that library alone: set it in the library's settings, or in the compose's
environment as `RESEARCHZOSHO_SERPER_MONTHLY_LIMIT` and the like for every library at once.

## Backups and moving

A library is its volume, and one daemon holds a lease on it. Stop a container with `docker stop`, never with a
kill: a daemon killed outright leaves its lease in the volume, and the next one on that volume waits until the
lease lapses, a few minutes, saying so in its log. A second daemon started on the same volume while the first runs is refused
the lease and stops. A daemon that finds another live process holding its lease (it was paused past the lease, and another
took the volume) stops working on the library at once: it takes no new job, stops its running ones without writing anything
of them, so they resume in the process that holds the library or at the next start, answers every request that would write
with 503 and the sentence `/healthz` gives, and ends with one line that names the holder and a code that is not zero. A lease
that only ran out, with nobody else holding it, is taken again and work goes on. Each library's settings volume, `/config`, holds its settings file (`/config/config`), the addresses of
its databases, and its settings folder (`/config/home`, `RESEARCHZOSHO_HOME`): the month's count of searches and pages
through the paid services, the helpers' secret and the helpers server found, the shared-link settings, the update's state,
the log files when the log is not stdout, and the caches of the help pages, the site list and the name spellings. The
image sets `RESEARCHZOSHO_HOME`, and both compose files and the fleet script say it too. An image older than 0.7.5 keeps
that folder inside the container and loses it when the container is made again. `scripts/fleet.sh backup <name>` is a tar of the library and its settings; a
snapshot of the disk under the volume is the same thing at the cloud's level, and a nightly snapshot is
enough. `scripts/fleet.sh move <name> <user@host>` stops the library here, carries its volumes to the other
server over ssh, starts it there with the same image and settings, and points the router at it; the route
table under `deploy/caddy/routes/` is the record of what runs where.

## One server on AWS

`deploy/terraform/` makes one VM with Docker, a public address, a disk per library and a firewall that
opens the router's ports:

```
cd deploy/terraform
terraform init
terraform apply -var 'libraries=["alpha","beta"]' -var domain=example.com -var key_name=my-key
```

It prints the address to point `*.example.com` at and the disks to mount. On the VM, mount each disk under
`/srv/researchzosho/<name>`, check out this repository there, and run the compose and `fleet.sh create` as
above. A second VM is a second copy of this with its own libraries; the first router's route table names
where each library is. Snapshots of the disks are the backups.

## Scoring translations

A checked translation scores every line with LaBSE and MetricX-24. The image runs neither: it has no Python and no
Docker, so it sets `RESEARCHZOSHO_LOCAL_HELPERS=off` (both compose files and the fleet script say it too), and a library
in it never tries to set them up. The libraries score on a helpers machine instead, one with a graphics card: run
`researchzosho helpers install` there, and `researchzosho helpers secret` there prints the secret it serves with. Point
each library at it in its own settings, which live on its `/config` volume and move with it, then restart the library
so it reads them:

```
docker exec rz-alpha researchzosho settings set helpers http://<helpers machine>:8217
docker exec rz-alpha researchzosho settings set helpers_secret <the secret>
docker restart rz-alpha
```

Without a helpers machine, a checked translation runs without scores and its plan says so.

A comic is read and lettered by the same machine's comic helper, which `helpers install` sets up there with the
others; no comic is translated in the image without one, and a comic's plan says what to run.

## The health check, the log, what is missing

`GET /healthz` answers 200 with the version and the library's id when the library opens and the daemon
holds its lease, 503 with the reason otherwise, with no sign-in: point the load balancer's check at it. A daemon whose
lease another process has taken answers 503, names the holder and ends; one whose lease only ran out while it was paused
takes it again and answers 200. The
container writes its log to stdout (`RESEARCHZOSHO_LOG` says otherwise), and at start it names each
setting that is missing, in one sentence each: no model answering, no account for the keeper, pages open
with the sign-in off, no trusted proxy, no embeddings server, no web search, a half-set identity provider.
`docker logs rz-alpha` shows them.

## What holds today: one library, one process, one machine

The lease, the id counter and the other locks between processes are the operating system's file locks, and the lease's
check that its holder is alive looks the process up on this machine. Both hold on one machine, on a local disk or a Docker
volume. They do not hold on every network file system, so a library's volume is used by one machine at a time.

The spend ledger (`catalog/spend.jsonl`), the crews log (`catalog/crews.log`) and the search log are appended to without a
lock between processes. One process writes them; two processes writing one library could mix their lines.

A process keeps these for itself, so one process serves one library:

- the settings: one settings file, read once and kept in memory; a setting another process changes is read again only by
  the few settings read live, so restart the library after `settings set`;
- the settings folder (`RESEARCHZOSHO_HOME`): the month's count of paid searches and pages, the helpers' secret, the
  update's state, the reading preferences and the taste records;
- the library that a model call outside a chat turn or a job is counted against;
- the MCP tools it offers and its trust in the caller a key or a session proved, and where the command line streams an answer;
- the helpers machines found and the machines found not answering, the queue of jobs and the set of running ones, the
  chat pages open now, and the locks between the threads of one process.
