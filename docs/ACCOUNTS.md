# Several accounts on one ResearchZosho

One ResearchZosho can serve several people, each with an account and a library of their own. A person lets others into their
library to read it, or to read and write. A program signs in with a key. This is off unless you turn it on: with it off,
ResearchZosho is one library and one keeper, as it always was. Several accounts run on Linux only in 0.8.0; the one-library
program runs on Linux, macOS and Windows as before.

## Turn it on

```
researchzosho settings set accounts on
researchzosho accounts add "Your Name" --admin      # prints a link; open it and choose your password
researchzosho serve                                  # the account service, at http://127.0.0.1:4649
```

`serve` is now the account service. It starts each account's library the first time that account asks for it, and stops them
when it stops. Your own library, the one this computer had before, is not served while accounts are on. Nothing in it is
changed. To go back to it, run `researchzosho settings set accounts off` and start the service again. A computer set up only to
serve accounts needs no library of its own: the three commands above are all it takes. Its models are added there the same way
(`researchzosho models add <name> <host> <model id>`, or `researchzosho settings set drive gemini` with the word of a host), and
no library is made by either.

## A public service, set up and updated

For people you do not know, on a server of your own with Docker Engine and Docker Compose:

```
researchzosho accounts setup public --domain research.example.org
cd researchzosho-research.example.org && docker compose up -d
docker compose exec service researchzosho accounts add "Your Name" --admin     # prints a link; open it and choose your password
docker compose exec service researchzosho models add <name> <host> <model id>  # the model the libraries use
```

The setup writes a folder (`--folder` names it) with:

- `compose.yml`: Caddy in front, which gets the certificate for the name by itself the first time it is asked (point the name at
  the server first); PostgreSQL, with a password made for it; a proxy to Docker; and the service. Each library runs in a
  container of its own, with no network. Only Caddy has ports on the server, 80 and 443 (`--http-port`, `--https-port`). Caddy
  and the service are on a network of their own, Caddy at a fixed address that Docker gives no other container (`RZ_FRONT_NET`,
  `RZ_CADDY_AT`, `RZ_FRONT_RANGE` in `.env`: 10.249.73.0/29, 10.249.73.2 and 10.249.73.4/30; change them, and `trusted_proxy` in
  `data/config`, if that network is in use on the server); the service and PostgreSQL are on another.
- `.env`: the name, the image, the folder of the data, the user the service runs as, and the database's password. It is readable
  by its owner alone.
- `Caddyfile`: the name, and the way to the service.
- `data/config`: the service's settings: accounts on, each library in a container, sign-up as `--signup` says (`invited`, unset:
  you make each account; `domain`; `anyone`), the address people reach it at, and Caddy's address as the one proxy whose
  forwarded address is believed. The caps and the rate limits hold at their defaults, which are those of a service open to the public. Change a setting
  with `docker compose exec service researchzosho settings set <name> <value>`.
- `data/`: the accounts' libraries, the file of the key that seals the persons' own keys (`data/keys`), and the libraries' sockets.
  The service sees it at the same path as the server, because Docker is handed these paths for each library's container.

**Docker's socket.** The service manages its libraries' containers through Docker. Docker's own socket is root on the server, and
only a proxy (`tecnativa/docker-socket-proxy`) holds it. The proxy lets through the calls on containers and images and refuses the
rest (volumes, networks, the swarm, the system). It has no network: it listens on a socket in `data/docker` that the user who ran
the setup alone opens, and only the service's container has that folder. Caddy, PostgreSQL and the libraries cannot reach it.

The proxy does not make the service less than root on the server. Making a container is as strong as root: a container can be
made privileged, or with the server's own folders in it, and the proxy lets every container call through. Whoever can make the
service do what it should not can do that too. Run the service on a server of its own.

**Two machines.** With `--nodes 2 --private <this server's private address> --node-address <the second's>`, the service and
PostgreSQL run on this server, and a node on the second makes and runs the libraries, each on its own disk. The setup writes
`compose.node.yml` and `node-data/config` beside the rest; copy them with `.env` to a folder of the same path on the second
server, and start the node there with `docker compose -f compose.node.yml up -d`. The database, the service and the node answer
each other on the private network (`--db-port`, `--service-port`, `--node-port`; 5432, 4649 and 4690 unless set), never on a
public one. A new library is made on the node with the fewest libraries. The second server's proxy to Docker listens on a socket in
`node-data/docker`, as the first's does, and has no port on that server either.

The node counts and caps by the service's settings, not by its own file: the spending plug-in's address, the paid search places'
prices, the model entries with their prices, and the model the libraries use. The service writes them into the store every minute
and the node reads them from there, so set them on the service (`docker compose exec service researchzosho …`). Keys are not
among them: the keys of the model's host and of the paid search places are set on each machine that runs libraries.

**Updating.** In the folder:

```
researchzosho accounts update 0.8.1
```

It fetches the image of that version (or uses the one the server has, when it is not published), writes it into `.env` and into
the settings that name an image, makes the service's container again from it and waits until it answers. The store's steps are
only ever added, so what still runs the version before keeps working meanwhile. With one machine, the libraries stop with the old
service and start again from the new image at their next request. With two, the command says the line to run on the second machine
after it: the node is made again there, and its libraries start again from the new image at their next request (a node's libraries
run the image its `.env` names, unless `accounts.image` in its `node-data/config` names another). A running job goes on from the
work it kept. Nothing is changed when the image can be found nowhere. When the service cannot be made again from the new image, or
does not answer within three minutes, `.env` and both settings files are put back as they were and the service is made again from
the image it ran before; the command says which.

## Accounts

```
researchzosho accounts                                 # the accounts and their libraries
researchzosho accounts add "Ann Example" [--mail ann@example.org]
researchzosho accounts link "Ann Example"              # a new link, for a forgotten password
researchzosho accounts suspend "Ann Example"           # frozen at once; nothing deleted (unsuspend gives it back)
researchzosho accounts remove "Ann Example" [--grace]  # deleted at once, with its libraries; --grace: frozen, removed after 30 days
researchzosho accounts keep "Ann Example"              # a deletion called off within its 30 days
researchzosho accounts handover ann-example "Ben Example"           # a library given to one of its people
researchzosho accounts audit ["Ann Example"] [--days 30]            # what was done
researchzosho accounts library "Ann Example" "Harbour history"      # another library kept by Ann
researchzosho accounts member ann-example "Ben Example" read        # or write, or remove
researchzosho accounts key "Ann Example" --write "her agent"        # a key, shown once
```

`accounts add` makes the account and its library and prints a join link. The link works once, for seven days. The person opens
it and chooses a password; from then on they sign in with their name and that password.

**Through your identity provider.** Set `oidc_issuer`, `oidc_client_id` and `oidc_client_secret` as [the cloud guide](CLOUD.md)
says, with the callback `https://<your address>/login/oidc/callback`. The sign-in page then offers "Sign in with <provider>".
`accounts_signup` says who gets an account by signing in for the first time:

| `accounts_signup` | who |
|---|---|
| `invited` (as shipped) | nobody. You make each account. A person whose account has their mail address is signed in to it. |
| `anyone` | whoever the provider signs in, with a verified mail address |
| `domain` | people of the mail domains in `oidc_allowed_domain` |

An invitation into a library lets its person into that library, whatever `accounts_signup` says. The account it makes for someone
new has a library of its own only where the sign-up rule would have given one: under `anyone`, or under `domain` for a mail
address the provider vouched for in an allowed domain. Under `invited`, and for anyone else under `domain`, it opens the library
it was for and nothing beside it.

A sign-in is finished only in the browser that began it: the browser keeps a short-lived cookie with a hash of the sign-in's
state, and the provider's answer must come back with it. A sign-in the service refuses goes back to the sign-in page, which says
why: the provider did not vouch for the mail address, the address's domain makes no account here, or the service makes accounts by
hand. A note on the sign-in or account page is shown only when the service wrote it (the page's address carries the service's mark
of it).

**Signing out.** "Sign out" is a button (a link from elsewhere signs nobody out). A browser signed in through the provider is sent
on to the provider's own sign-out when its discovery document names one (`end_session_endpoint`, as Keycloak's does), with the
sign-in's identity token and the way back to `<your address>/login`; register that address with the provider as one it may send
the browser back to. A provider that names none (Google) is not signed out of: the page says that the person may still be
signed in there on this computer.

**Showing it is you again.** Making a key for a program, keeping a key of your own, deleting a library and deleting the account
ask for your password when your last sign-in or proof is more than five minutes old; an account that signs in only through the
provider signs in there again (the service asks the provider for a new sign-in and checks the time it says it was made), then
does it again. A key of your own is never sent back in that page: it asks for it again beside your password. A key is made on
the account page, never through a library's own page.

## Suspending and deleting an account

**Suspend** freezes an account at once, and deletes nothing. Within a minute each library it is let into stops sending it changes (its webhooks), as it does for someone taken out of a library. Its browsers are signed out. Its program keys and own keys are kept,
and refused with the sentence "Whoever runs this service has suspended this account. Ask them." Its running work stops after the
call it is making and waits, kept, as at a cap. The libraries it keeps open for nobody, the people let into them included, and
go to sleep as soon as they are quiet; their nights do not run. `accounts unsuspend` gives everything back as it was; the person
resumes the stopped work.

**Delete.** A person who deletes their own account on their account page has a grace of 30 days (`accounts_delete_days`):
the account is frozen the same way, they can sign in, and their account page shows the day and "Keep my account". Whoever
runs the service deletes an account at once (`accounts remove`, or Delete on the operator page), as a moderation. They can
give it the grace instead (`accounts remove "<name>" --grace`, or "Delete with 30 days of grace instead" on the page); in
those days the person cannot keep the account, and only the operator can, with `accounts keep` or Keep it on the page. On a person's own page, deleting
asks them to type their sign-in name, and for each library they keep with other people in it, whether to hand it to one of them
(who keeps it from then on, and its storage counts on theirs) or let it go with the account. A library is never handed over
without its keeper's word; whoever runs the service can hand one over with `accounts handover`.

**What a removed account leaves behind.** On its day, the account's libraries are removed, with their copies and anything set
aside for them. Its name, sign-in name, mail address, password, sessions, program keys, own keys, join links, sign-ins through an
identity provider, sign-in attempts, the counts that held its rate limits and its caps are deleted, and its place in other
people's libraries ends. What stays is only what
the service's bills need:

- the account's id (a string of letters and digits) and the day it was made, under the name "a deleted account";
- the record of each use it made (what kind, which model, when, what it cost), and what it spent each month, under that id. These
  hold no name, no mail address and no address of the computer it came from. They stay as long as whoever runs the service keeps
  its records; the program removes none of them by itself;
- its entries in the audit log, with the act, the day and the id, and nothing else: the address and any detail are cleared at
  removal. They go when the log removes its old entries, 400 days after each was written (`accounts_audit_days`).

With a spending plug-in, what is waiting in the outbox for it keeps the account's id and loses its name; what the plug-in was
told before is the plug-in's.

## The audit log

The service writes down, one entry an act: sign-ins and failed sign-ins (with the address they came from; a name that is nobody's
is not written down), signing out everywhere, program keys made and revoked, own keys set and taken away, passwords changed,
people let into a library, changed and taken out, caps changed, accounts made, suspended, let back, deleted, kept and removed, and
libraries made, moved, taken in, released, deleted, restored, handed over and packed to take home, and limits an account reached (below), at most once a
minute for each account. Each entry says when, who did it (an account, the
command line, or the service itself), how (a browser, a key, the command line), to which account and library, and from which
address. Nothing changes an entry once written, except clearing a removed account's addresses and details.

Whoever runs the service reads it whole with `researchzosho accounts audit` (one account's with its name; `--days` for how far
back). A person sees what was done with their own account in the last 90 days under "Recent activity" on their account page.
Entries are kept 400 days (`accounts_audit_days`).

## Rate limits

The service limits how often one address or one account may ask it. Each limit is a setting, `limit.<name>`, written as a number
per period: `120 per minute`, `10 per 10 minutes`, or `120 per minute, burst 30` (up to 30 at once, then 2 a second). `off` turns
a limit off. The single-person program has none of these.

| setting | what it counts | unset |
|---|---|---|
| `limit.signin` | failed sign-ins from one address | 10 per 10 minutes |
| `limit.signup` | accounts made from one address by signing up through the identity provider; and the invitations one account makes into its libraries | 5 per hour |
| `limit.join` | join links opened from one address | 20 per hour |
| `limit.changes` | changes to one account's password or own keys, a wrong current password counted | 20 per hour |
| `limit.address` | requests from one address with no account signed in | 120 per minute |
| `limit.api` | API and MCP calls with one account's keys | 120 per minute, burst 30 |
| `limit.pages` | requests from one account's browsers: the pages, and what they ask for by themselves | 300 per minute |

A name tried wrongly 10 times in 10 minutes from one address also waits 10 minutes there, whatever `limit.signin` says: wrong
passwords from somewhere else do not lock its person out.
The service's own files (`/static/`, the icon), `/healthz`, and what its libraries and nodes ask of it are not counted.

**Where the counts are kept.** The first four are counted in the store, one row an act, so every process of the service reads the
same count. Those rows hold the address or the account's id they count, and are removed within a day. The other three are counted
in each process's memory, each process holding its share: the limit divided among the service's processes seen in the last minute
(each writes itself down in the store every 20 seconds, and takes itself out when it stops). With a router that spreads requests
across the processes, the whole limit holds; one that keeps an account on one process gives it that process's share.

**The address.** Behind a proxy named in `trusted_proxy`, the address the proxy forwards counts; from anywhere else, the
connection's. A trusted proxy that forwards no address counts as one address. An IPv6 address counts by its first 64 bits, the
network one household or one machine is given.

**What a refused request is told.** HTTP 429 with `Retry-After` in seconds, and one sentence, such as "Too many requests from this
account. Try again in 40 seconds.":

- a program gets the protocol's error: `error.code` `rate_limited`, the sentence in `error.message`, the seconds in `error.retry_after`;
- an MCP call gets a JSON-RPC error with `data.code` `rate_limited` and `data.retry_after`, answered 200 with `Retry-After` as
  every MCP error of the library is, so the host's model reads the sentence (an MCP client told 429 reports a broken connection);
- a browser sees the sentence on the page it was on: the sign-in page, the account page, or for a library's page a page of its own
  with a link to try again; in the chat, in place of the reply.

A refused request does nothing: no password is checked, no join code is looked at, nothing is changed. A limit an account reached
is written to the audit log as `limit_reached` with the limit's name, at most once a minute for each account. A limit reached by an
address with no account is not written down.

## What a person sees

After signing in, a person is in their own library: the same pages as any ResearchZosho. "Account" in the header opens their
account page: this month against their caps, their own keys, the libraries they can open, the keys of their programs, and for each
library they keep, its people.

- **Let someone in.** On the account page, under the library's people: a name for them and "read" or "read and write". You get a
  link to send them; the name is only for you to know the link by, and is matched against no account, so the page is the same
  whoever it is. Whoever opens the link is let in, once, within seven days: someone signed in here with one button; someone not
  signed in signs in first, or makes an account with a name and a password of their own choosing, or signs in through the
  identity provider. The links not taken yet are listed under the library's people, each with **Withdraw**. Opening your own link
  lets nobody in.
- **Open another library.** A person let into a library opens it from their account page. Their browser shows one library at a
  time.
- **Remove someone.** Their next request to that library is refused, and their keys for it stop working.
- **Make a library.** Under their libraries: a title, and it is theirs, up to `libraries_per_account` (3 unless set). What all their
  libraries hold together counts against `storage_mb`. **Let it go** beside a library they keep takes it out of service after a
  page that asks; it is kept aside for 30 days, when whoever runs the service can bring it back, and then removed.
- **Take it home.** Beside each library a person keeps: the whole library as one file, `<name>.rzlib.zip`, with its shelves, its
  reports, its jobs' records, its search index and its own settings. ResearchZosho on their own computer opens it with
  `researchzosho take <file>`. A program with a key made for the library gets the same file at `GET /v1/library/archive`. The
  format and what is left out are in [LIBRARY_ARCHIVE.md](LIBRARY_ARCHIVE.md).

## Programs

A key is for one account and one library, and reads it or reads and writes it. A person makes one on their account page, sees it
once, and gives it to the program:

```
curl -H 'Authorization: Bearer rz_…' https://<your address>/v1/status
claude mcp add --transport http zosho https://<your address>/rpc --header 'Authorization: Bearer rz_…'
researchzosho connect https://<your address> rz_…        # the command line of another computer
```

Connected, a person has the library's people and their own keys at the command line too:

```
researchzosho access                                   # who keeps the library, and who is let in
researchzosho access invite "Ben Example" read         # or write; prints a link to send
researchzosho access remove "Ben Example"
researchzosho access keys
researchzosho access key new "a script" --read         # or --write; shown once
researchzosho access key revoke K-1a2b3c4d
```

The routes are the library's own ([the protocol](LIBRARY_PROTOCOL.md)). `/v1/access` lists a library's people, lets someone in,
removes them, and makes and revokes the caller's keys.

## What a library under the service does not do

The person who keeps a library here does not own the computer it runs on. So a library under the service:

- reads no file by its path on the service's computer. A person adds files from their own computer on the Add page, or gives a
  web address;
- starts no program a person names: no other MCP server, no `git clone` of a repository (so no `repo` and no survey of one), no
  tunnel (`share`);
- asks no address on the service's own computer or network, by any road: a page, a podcast's feed, an episode's audio, a
  notification's address;
- shows no path of the service's computer: a file you added is shown by its name;
- reads no database;
- does not change the model, the search keys or any other setting of the service. Its keeper sets how its reports are written
  (`language`, `report_length`, `citation_style`, `market`) and what its nights do, on the Keep page or the Settings page, or by
  saying so in the chat. Those pages show the library's own settings and nothing else; its people and the keys of their programs
  are on the account page.

Every library uses the model and the search keys the service is set to. When that model does not answer, a library's first page
says so, and that whoever runs the service has to look; there is nothing there to connect.

## Where things are kept

| setting | what | unset |
|---|---|---|
| `accounts_root` | the folder the accounts' libraries are under | `accounts` in the settings folder |
| `accounts_store` | where the accounts are kept: a PostgreSQL address (`postgresql://user:password@host/database`) | the file `accounts.db` under the root |
| `accounts_address` | the address people reach the service at; join links are written with it | the address it listens on |
| `accounts_first_port` | the first port of the loopback a library is given; each takes the next free one | 4700 |
| `accounts_sleep_after` | after how many minutes with nothing to do a library is stopped; `off`: never | 15 |
| `accounts_nights` | whether the libraries have nights (their nightly tasks, which use your model): `on` or `off` | on |
| `accounts_nights_at_once` | how many libraries' nights run at once | 2 |
| `accounts_library_heap` | the most memory the Java heap of each library's process takes (`512m`, `2g`); a container may use this and 768 MB more | `1g` |
| `accounts_runner` | what each library runs in: `process` or `container` (below) | `process` |
| `accounts_image` | with containers: the image each library's container runs | `ghcr.io/wyrdsekai/researchzosho` at this version |
| `accounts_library_cpus` | with containers: how many processors each library's container may use (`1`, `1.5`) | 2 |
| `accounts_copies` | the folder each library's copy is kept in, best on another disk or a mounted bucket | no copies |
| `accounts_service` | the address the libraries and the nodes reach the service at (private network); the same for every process of the service | the address it listens on |
| `accounts_node` | `off`: `serve` runs no libraries itself, only nodes do | on |
| `accounts_node_host` | this machine's address its node and its libraries listen on | 127.0.0.1 |
| `accounts_node_port` | the port this machine's node answers on | any free one |
| `accounts_takeover_after` | minutes of a machine's silence before its libraries come back from their copies elsewhere; `off` | 15 |
| `accounts_copy_after` | after how many minutes a copy is brought up to date at a quiet moment when something changed | 10 |
| `accounts_delete_days` | how many days a deleted account waits, frozen, before it is removed | 30 |
| `accounts_audit_days` | how many days the audit log keeps an entry | 400 |
| `accounts_keys_file` | the file of the key that seals the persons' own keys; outside the root, and kept apart from the store's copies | `accounts-own-keys.key` in the settings folder |
| `accounts_sockets` | with containers: the folder the node makes each library's sockets in, a short path (a socket's path holds at most 107 bytes) | a folder in the temporary folder |
| `accounts_sockets_host` | with a node in a container: the same folder as the server Docker runs on sees it | the same path |
| `accounts_spending` | the address of a spending plug-in ([SPENDING_PLUGIN.md](SPENDING_PLUGIN.md)) | the built-in caps |

Each library is a folder of its own under the root, with its own settings folder. A backup of the root is a backup of every
account and every library.

Behind a router that ends TLS (Caddy, a load balancer): send everything to the service, name the router in `trusted_proxy`, and
set `accounts_address` to the public address.

## Asleep and awake

A library runs only while it has something to do. It is started by the first request for it, which waits a second or two
while it starts. After 15 minutes with nothing to do it is stopped, and the next request starts it again. A library is not
stopped while a request to it is open (a chat answer being written, an upload), while a job runs or waits to run, while a
chat turn is in progress, or when work of its own is due within ten minutes. A chat page left open in a browser tab does not
keep a library awake.

A library takes about 150 MB when it starts and several hundred more as its pages are used; asleep it takes none.

**Nights.** Each library's nightly tasks start at a minute of its own between 01:00 and 05:00 by the service's clock, the same
minute every night, whether the library is asleep or awake. Two nights run at once (`accounts_nights_at_once`); the others
wait their turn, so with many libraries the nights run on into the morning. A night missed because the service was not
running is made up the same day. `accounts_nights off` gives the libraries no nights at all: the nights use your model.

**Paused research.** A run that waits because the library's keeper paused research does not keep the library awake.

**An update of the program.** Start the service on the new version. The libraries the old version started keep running
until each has a quiet moment (no request open, no job, no chat turn); then the service stops it and starts it on the new
version, where its repairs run. A person with a page open notices nothing but one request that waits while the library
starts. If the service is away for longer than 30 seconds, the libraries stop by themselves and their jobs go on from
where they were at the next start.

**A folder held by something else.** The service stops a process only when it has proved the process is one of its own starts of
that library: the program's library daemon, started under this service for that library, before the library's lease file was last
written, with the lease still current. A lease file can outlive a crash and the system uses process numbers again, so the number in
the file alone proves nothing. When a process the service cannot prove holds a library's folder, nothing is stopped: the library is
not started, the person is told whoever runs the service has to look, the service's log names the process, and the hold ends by
itself within five minutes.

## Copies, moves and removal

Set `accounts_copies` to a folder on another disk, and each library has a copy there. A copy is brought up to date when the
library goes to sleep, after each job it ends (its night is a job), before a move, and at a quiet moment (no request open, no job,
no chat turn) when something changed and the copy is older than `accounts_copy_after` minutes. Only what changed is written. So
what a lost disk can cost is a few minutes of work. A copy made while the library runs keeps the search index of the last copy made
while it was stopped, since an index cannot be copied while it is written to. There is one copy per library, not a history.

```
researchzosho accounts                                   # each library, with the time of its copy
researchzosho accounts restore "<library>"               # brought back from its copy; its page says which copy, for a week
researchzosho accounts move "<library>" /mnt/disk2/rz    # its folder moved under another root of this machine
researchzosho accounts delete "<library>"                # out of service; removed with its copy after 30 days
researchzosho accounts delete "<library>" --now          # removed with its copy at once
researchzosho accounts take ~/researchzosho-library --for "<name>"   # a library of this machine's own, taken in
researchzosho accounts take ann-example.rzlib.zip --for "<name>"     # a library's archive, taken in
researchzosho accounts release "<library>" ~/harbour     # released: an ordinary library again, at that folder
```

Each of these takes the library out of service first: the service stops it (within a few seconds) and nothing runs it while its
folder changes. Someone who asks for it meanwhile is told it is being moved. A move copies the folder, then sets the old one aside
(under `<root>/aside/`) for 30 days. A removed library's folder is set aside the same way, and goes with its copy when the 30 days
are over; until then `accounts take` brings it back.

**Taking in a library of your own.** `accounts take` moves the folder under the service (it is copied when it is on another disk,
and the original is then left where it was). The library keeps its id, its shelves and its jobs. Its own settings of how reports are
written come with it. Its own readers and the keys of their programs do not open it under the service: the command lists them, and
you let the people in again on the account page. Nothing of theirs is removed from its files, so `accounts release` gives back an
ordinary library in which they sign in as before.

## Several machines

On one machine `serve` runs the libraries itself. With more machines, each runs a **node**: the same program, started with
`researchzosho node`, which keeps the libraries of its disk, starts and stops them, and answers the service's calls.

```
# on each machine that runs libraries
researchzosho settings set accounts_store postgresql://rz:…@10.0.0.2:5432/rz   # the service's store
researchzosho settings set accounts_service http://10.0.0.2:4649               # where its libraries reach the service
researchzosho settings set accounts_root /srv/rz                                # its own disk for the libraries
researchzosho settings set accounts_node_host 10.0.0.3                          # its address the service reaches it at
researchzosho node
```

The service, the nodes and the libraries call each other at these addresses: the store, `accounts_service`, each node's port and
each library's port. They belong on a private network between the service and its machines, never on a public one. Nothing
between them is encrypted in this version; what is checked is that each call carries a statement the service signed.

A new library is made under the service's `accounts_root`; set it to a node's root to make it there. The store writes down which
node's disk holds each library's folder, and the library runs on that node. Every machine may have the same root path (`/srv/rz`
above): a folder at the same path on another machine's disk is not the library's, and that machine does not serve it. When a
machine stops answering for half a minute, the libraries that were awake on it are started on another: at once from their folder
when the other machine sees that machine's own disk (its root holds that machine's `node.id`), otherwise from their copies after
`accounts_takeover_after` minutes (15; `off`: only when you say so, with `accounts restore "<library>" <a live machine's root>`,
or its id as `accounts` lists it when several machines have the same root path). A takeover brings a library from the copy that
the service and the other machine both see, so put `accounts_copies` on a disk that every machine mounts at the same path; a copy
kept on the silent machine's own disk waits with it. A library brought from its copy is written down for the machine that has it
now. A job goes on from the work it kept. A library brought back from its copy says so on its page. The machine that comes back
serves none of them again. A request that comes for a library while its machine has stopped answering waits until that machine's
hold on it has run out (half a minute at most; the library there has stopped itself before), and is then served by another machine
that reaches its folder, or told why none can.

`accounts take` on a service that runs no library itself sends the archive to the machine with the fewest libraries, which takes
it in under its own root; a folder of the service's machine is packed as an archive first (the folder is left as it is, and what
an export leaves out, such as its own readers, stays there).

A library's folder is changed by the machine that holds it: `accounts move`, `delete`, `restore`, `release` and `take`, the
operator's page and an account's removal ask that machine's node, which does it on its own disk; `move` and `release` name a
folder of that machine. `accounts restore "<library>" <root>` with the root of another live machine (or its id) brings the library
from its copy onto that machine, and the machine it was on sets its folder there aside. When the machine it was on answers, its
copy is brought up to date there and fetched from it first (the machine it goes to needs `accounts_copies` set); when it does not,
the copy in that machine's own `accounts_copies` is used. What is set aside is written down for the machine whose disk holds it,
which removes it when its days are over. A library whose folder this machine cannot see, and that no machine is written down as
holding, is not changed at all.

One copy pass of a library, or one change of its folder, at a time, whatever process makes it: each holds the library in
`<accounts_copies>/.holds/`, and taking a library out of service waits for a pass under way. A library stops by itself two thirds
of a lease (20 seconds) after it last heard from its node that it is still its own, by the clock that goes on while a machine
sleeps too, so it has stopped before another machine may take it over; a node says so only after the store renewed the lease.

With two processes of the service (behind a router, both on one PostgreSQL store), give both the same `accounts_service`. A sign-in
begun at one ends at the other, a sign-out at one holds at the other, failed sign-ins are counted across both, and one of them
runs the nights.

**What the service stops.** It stops a process only when it has proved the process is one of its own starts of that library, from
the process's command line: read from `/proc` on Linux, from `ps` on macOS and from the system's process list on Windows. Where
none can be read, nothing is proven and nothing is stopped. A start of a library that the service could not take back is still not
in anyone's way: the next start of that library takes the folder over at once, and the earlier one stops by itself when it next
looks, within half a minute. A folder held by anything else (a library started outside the service, or by another service) keeps
the library from starting until that hold ends: within five minutes of the other process ending, or never while it runs.

## Each library in a container

```
researchzosho settings set accounts_runner container
researchzosho serve
```

With `accounts_runner container` each library runs in a Docker container of its own instead of a process. It needs Docker
Engine on Linux, a user for the service who may run `docker`, and the image `ghcr.io/wyrdsekai/researchzosho` at the service's own
version. The service fetches the image the first time if it is not there; `docker pull ghcr.io/wyrdsekai/researchzosho:<version>`
does it beforehand. `accounts_image` names another image.

A library's container:

- sees its library folder at `/library`, its settings at `/config`, its temporary folder and its home, and no other folder of the
  machine;
- runs as the user who owns the library's folder, under a group of the library's own (a number from 2,000,000,000 up, made from
  the library's id, that no program of the machine runs under), with no capabilities, no way to gain any, and a root file system it
  cannot write;
- may use `accounts_library_heap` and 768 MB more of memory, no swap, `accounts_library_cpus` processors and 1024 processes;
- has no network at all: no way to the internet, to this machine, to the network around it or to another container.

The node is its only way in or out. The node and the library meet in a folder of the library's own that the container sees at
`/run/rz`, through Unix sockets in it. The service's requests come to the library through one; the library reaches the service and
each server its settings name on this machine (the model, SearXNG) through one each; and it reaches the web through the node's
gate. The service reaches the library at a port of the loopback, as it reaches a process.

The library can write in that folder, so before the node writes a byte of a request to the library's socket, it makes sure the
socket is the library's own: the path must be a socket and not a link, and the kernel must say the process listening on it runs as
the folder's owner under the library's own group. Anything else (a link to Docker's socket, to another library's, to the node's
own) is refused, and the service's log says so. The library's way to the service goes to where the node itself reaches the
service: its own loopback when the node is part of the service's process, else the service's address. The web's gate never takes a
library to the service.

The gate is an HTTP proxy the node runs for each library. It looks up a name itself and refuses the request when any of its
addresses is this machine, a private network (10/8, 172.16/12, 192.168/16, 100.64/10, fc00::/7), a link-local address (where a
cloud's metadata service answers, 169.254.169.254 and its IPv6 forms), multicast, or another address nothing is reached at. It
connects to the address it checked. It is the same list the fetcher has. A server on the private network that the service's own
settings name (a model on another machine) is let through by its name and port. The library's Java and every program it runs (git
among them) are given the gate. A request that does not go through it goes nowhere. The service's log names each library whose
gate refused something, and counts each library's connections every ten minutes.

A library put to sleep has its container stopped and removed; the next request starts a new one, in about a second.

The service starts, stops and removes a container only by the name it gave it (`researchzosho-<library id>`) and the labels it put
on it. A container of that name without those labels is left alone, and that library does not start while it is there; the
service's log names it.

**A service open to people you do not know should run its libraries in containers.** As processes, the libraries are kept apart
only by ResearchZosho's own checks, and all of them run as the user who runs the service: a fault in those checks, or in a program
a library runs on a file someone gave it, reaches every library's files and that user's own. In a container the kernel shows a
library no folder but its own, and no network but its node's gate, so such a fault reaches one library and the open web.

Which wall each form has:

| | as a process (the default) | in a container |
|---|---|---|
| another library's files, the service's files | ResearchZosho's own checks | the kernel: not there |
| this machine, the private network, the cloud's metadata | the fetcher's own list (`fetch_private`, on under the service) | the node's gate, by the same list, for everything the library runs |
| another library's process | its port takes nothing without the service's statement | the same, and no network to reach it by |

The settings a library is given are in its container's description, which anyone who may run `docker` can read. They hold no
key: the model's hosts and the paid search places are reached through the node's gates, which add the keys, and a library is
given a word in place of each key.

## Use and caps

Each account's use is counted, and capped. A use is a research run, a chat turn, a translation, a library's night, a page added
by its address, or a document sent from a person's computer. Before one starts, its library asks the service whether the account may spend on it. On a no, the person is told
why in one sentence, the same on every page and in the API, and nothing starts. When it ends, the library tells the service. The
person who asks pays for what they ask; the library's keeper pays for its nights.

A library keeps the end of each use in its folder until the service has taken it, and tells it again until then, across its own
restarts. The service closes a use nobody will end, as failed: one that is not a job's (a chat turn, a page's call, a page added)
after two hours, and a job's after a day with nothing counted under it for six hours (a job whose library stopped goes on from its
record under the same use when it starts again). What a use came to is written to the spending plug-in's outbox in the same step
that closes it. What is counted under a use after it closed (a call that began before) is added to it, and told to the plug-in again.

| cap | what | default |
|---|---|---|
| `month_dollars` | a month's dollars of the service's model and paid searches (work on the person's own key is not counted against it) | $5.00 |
| `searches` | a month's searches through the service's paid search places | 300 |
| `fetches` | a month's connections to the web | 3000 |
| `jobs_at_once` | research runs and translations going at once (counted and started in one step, so two asked at one moment do not both pass); a page's or a program's own call outside any run is not a job | 1 |
| `job_minutes`, `job_dollars` | the most one job may take: the gates' watch stops a job past its minutes as at a cap (and a plug-in's `ceiling_minutes` the same way) | 30, $1.50 |
| `storage_mb` | what the libraries an account keeps hold, together | 2048 |
| `libraries_per_account` | how many libraries a person may keep by making them on their account page; whoever runs the service may make more for them. It holds with a spending plug-in too | 3 |

The defaults suit a service open to the public. Set them for the whole service, or for one account:

```
researchzosho accounts caps                              # the service's caps
researchzosho accounts caps month_dollars 20             # for every account without its own
researchzosho accounts caps "Ann Example" searches off   # one account's own; "default" puts it back
researchzosho accounts usage                             # this month, every account
researchzosho accounts usage "Ann Example"               # one account's month, its caps and its uses
```

### A cap reached midway

When a use reaches its cap (its own ceiling, or its account's month), the library stops the work after the call it is making and
keeps what it did. The node's gate refuses anything past the cap and a tenth more ($0.25 at least) meanwhile. A research run then
waits, stopped at its cap: the runs strip, the run's page and `library_job` say why. Resume (the button, or `library_job` with
`op=resume`) asks the service again. While the cap holds the answer is its sentence and the run keeps waiting; once the cap is
raised, or the month turns, the run goes on from where it stopped and finishes. A run stopped this way has two uses, one each
side of the cap.

A search cap reached stops the searches, not the run: it goes on without them. A connection cap reached refuses the connections
the same way. A chat turn the gate refuses ends with the gate's sentence as its reply.

### An account's month, wherever it is asked

The same rows, with the same numbers, on every surface: the account page; `GET /v1/account/usage` at the service with the
account's key (`Authorization: Bearer rz_…`), as JSON with the rows, their values and the month in words; `library_spend` in any
of the account's libraries, through the API or MCP, which asks the service; and `researchzosho accounts usage "<name>"` for whoever
runs the service. A refusal is the same sentence in each: a page, an API's error message, an MCP tool's error, a run's line.

### The account page and the operator's page

**This month**, on each person's account page: the service's dollars they spent (the model and the paid searches), their
searches, their connections to the web and what their libraries hold, each against its cap with a bar and what is left; the
minutes of speech turned into text and of the helpers' time; and what their own keys spent, which is theirs. A line says how many
of the month's model calls were counted from their size, when any were. With a spending plug-in the caps are the plug-in's, and
the page shows what was used.

**The operator's page**, `/operator`, for the accounts that run the service (made with `accounts add … --admin`, marked "runs the
service" in `accounts list`). Nobody else opens it. It has five parts:

- **Accounts:** every account's month against its caps, and whether it is suspended or to be removed. Make an account (its join
  link is shown once), suspend one, let it back, delete it after its days of grace or at once, keep one being deleted, or give it
  a new join link.
- **Libraries:** each library's keeper, where it runs (awake on which machine, or asleep), its size as last measured and the age of
  its copy. Make a library for an account, move one under another folder, hand it to one of its people, release it to a folder outside the service,
  delete it after its grace or at once, or bring it back from its copy. Libraries out of service are listed with the day they go,
  and a deleted one can be taken back until then. A library's folder or archive on the service's machine is taken in for an
  account.
- **Machines:** each node's address, folder and version, when it last wrote itself down, and how many libraries are awake on it
  and kept on its disk.
- **The caps:** the service's caps, and one account's own.
- **The audit log:** the newest first, a hundred entries a page, by account, act and days.

Each act on an account or a library is asked on a page of its own first, which says what it will do; nothing is done until it is
confirmed there, and each is written to the audit log. Your own account is suspended or deleted from the command line, not from
this page. The same acts are on the command line: `accounts`, `accounts add`, `suspend`, `unsuspend`, `remove`, `keep`, `link`,
`library`, `move`, `handover`, `release`, `delete`, `restore`, `take`, `caps`, `usage` and `audit`.

### A person's own keys

A person can keep a key of their own for the model's API the service uses (when it is an API on the internet, such as OpenRouter
or OpenAI) and for each paid search place. From then on their work there goes on their key: the gate sends it in place of the
service's, writes what it spent as theirs, and holds none of the operator's caps for it. A use on the model with their key is
theirs as it starts, so the month's dollars do not stop it. Pages fetched, the helpers and storage stay the operator's.

The store keeps each key sealed (AES-256-GCM, bound to the account and the host). The key that seals them is the file
`accounts_keys_file`, made when the first key is kept, readable by whoever runs the service alone, and refused inside the root.
Only the node's gates open a key, for the call they pass on. A library never has one, and no log writes one. A person sees that
their key is set and when, never the key.

Keep a copy of that file apart from the store's copies: one copy of both gives the keys away. When the file is lost, a new one is
made and the keys sealed with the old one cannot be opened. Each person's account page then says to set theirs again, and their
work goes on with the service's keys meanwhile. Nothing else is lost. With nodes on other machines, each needs the same file.

### A spending plug-in

A service run for pay can answer whether each use may start, and be told what each came to, from a program of its own:
`accounts_spending` names its address. [SPENDING_PLUGIN.md](SPENDING_PLUGIN.md) is the whole protocol. With it, the plug-in sets
each use's ceiling and holds the month itself: the gates hold each use's ceiling and leave the built-in month caps alone. A use
on the person's own key still has a ceiling on what the operator pays inside it (searches without their key, helpers):
`accounts_own_use_ceiling`, $1 unless set, `off` for none, when the plug-in sets none. A `spent` the plug-in refuses with a 4xx five
times is set aside on the operator's page ("Send it again" puts it back in its place) and what comes after it is sent. An
account removed is told to the plug-in (`account_deleted`).

### What is counted, and where

Every call of a library that costs goes through its machine's node: the model servers the settings name (`drive`, `judge_drive`,
`vision_drive`, `job_drives`, `embed`, `rerank`) through the node's model gate, the paid search places (Brave, Serper, SerpApi,
Exa, Tavily, Firecrawl) through its search gate, the heavy helpers (speech-to-text, LaBSE, MetricX-24, the likeness and comic
helpers) through its helper gate, and every other request to the web through the library's web gate. A library has no key, as a
process or in a container. It is given the gates' addresses in place of the servers', and in place of each paid place's key a word
that says the place may be used. The gate adds the key (for the helpers, the helpers' secret), passes the call on, and counts it
against the use it belongs to. A call to a model or a search place that belongs to no use is refused, and so is a call with a use
of another library: each library is given the gates' addresses under its own id. The model gate passes only the calls the program
itself makes: an answer, embeddings and a reranking under a use, and a look at the server (its models, its properties, its slots,
its health) without one. Anything else (batches, files, pictures, speech, fine-tuning) is refused, so the service's key does nothing
it was not given for. A call on the service's key must name a model that one of the service's model entries names at that server
(or the embeddings or reranking model the settings name); the server is matched by its origin and its path written plainly, so
`/./v1` or a host in capitals is the same server and is counted as such. The search gate passes only the calls the program makes
at each place: Brave's web and picture searches, Serper's search and resolve, SerpApi's search, Exa's and Tavily's searches and
Firecrawl's scrape. A place's account, usage and credit calls, and its calls that take many credits at once (a crawl, a web set),
are refused. Work on a person's own key is not stopped by the operator's month; its own key's provider bills it.

| kind | counted at | what is counted | dollars |
|---|---|---|---|
| model | model gate | tokens read, cached and written, from the server's answer (streamed or not, OpenAI's form or Anthropic's); when it says nothing, worked out from the call's size and the answer's text at four bytes a token, and marked so. An answer cut short (the work stopped, a time limit, the server broke off) is counted from what passed, and marked so | at the model entry's prices |
| search | search gate | each search | at the place's price, below |
| fetch | web gate | each connection the library opens to a site (a page, or several pages of one site over one connection), counted as it opens, and its bytes both ways when it closes | none |
| helper | helper gate | the helper's seconds, each call that does work | none |
| speech | helper gate | the seconds of speech the transcription says it heard | none |
| storage | the node, once a day | each library's folder, charged to its keeper: a row a day, so the month holds megabyte-days | none |

An embeddings or rerank server is priced by a model entry that names its address and model (`researchzosho models add`); without
one its calls are counted at no dollars. A call to an API on the internet that charges (OpenRouter, OpenAI, Anthropic and the
like) for a model whose entry has no prices is counted at $3 and $15 a million tokens read and written, marked as an estimate, and
the operator's page says which address and model until the entry has its prices. Tokens Anthropic writes to its cache are counted
at a quarter more than reading, or at an entry's fourth price. A search place's account calls (its usage, its credits) count
are refused (see above). `accounts usage` says how many of the month's
model counts were worked out from sizes.

Each model call puts aside what it may cost before it is passed on (what it sends, at four bytes a token, and the most it may
write, at its entry's prices) against its use's ceiling and its account's month, and gives it back once what it cost is written:
calls made at once see each other, so together they cannot pass the cap by more than one call.
A use may spend its ceiling and a tenth more ($0.25 at least), and an account its month and a tenth more, so the work's last unit
can finish. Past that, the gate refuses the next model call or search with a sentence and the work stops there. Searches are also
held to the `searches` cap, and connections to the web to the `fetches` cap: past it the web gate refuses them, and no new
research run or page added by its address starts. While the libraries an account keeps hold as much as `storage_mb` lets them, as last measured,
nothing more is added to them, whoever asks: no page by its address, document, research run, translation or picture. Reading and
chat go on.

A request to the web carries the use it is part of. One that carries none, the library's own (a check for a newer version, its
help pages), is its keeper's, written as background: counted with its bytes, and not against the fetches cap. In the process
form the library's web gate is a port of this machine's loopback, and the library's own rule for what it fetches holds as well. A helper's call
that carries no use is its keeper's too. A server the service's settings name (a search server or a helpers machine on the private
network) is the service's own, and is not counted as a fetch.

The other keys in the service's settings (the data sources': Census, PubMed, YouTube and the rest) are not handed to a library at
all; a library under the service reads those sources without a key where it can. Amazon Bedrock is not offered to a library under
the service in this version: a model setting that is not an address is left out of what a library is given.

### What a search costs

Each search is counted at its place's price, `search_price.<place>` in dollars (`researchzosho settings set search_price.brave
0.004`). Unset, it is the place's published price, read from each place's own page on 17 November 2026:

| place | page | what it said | price a search |
|---|---|---|---|
| Brave | brave.com/search/api | Search plan: $5 per 1,000 requests ($5 of free credits every month) | $0.005 |
| Serper | serper.dev | Starter: $50 for 50k credits, 50,000 queries ($1.00/1k) | $0.001 |
| SerpApi | serpapi.com/pricing | Starter: $25 a month, 1,000 searches | $0.025 |
| Exa | exa.ai/pricing | `auto` search: $7 per 1k requests, up to 10 results | $0.007 |
| Tavily | tavily.com/pricing, docs.tavily.com (API credits) | pay as you go $0.008 a credit; a basic search 1 credit, an advanced one 2 | $0.008 |
| Firecrawl | firecrawl.dev/pricing | Hobby: $19 a month, 5,000 credits; a page 1 credit, a search 2 credits per 10 results | $0.0038 |

Exa's answer says what each search cost, and that is what is counted. A Tavily advanced search and a Firecrawl search count two
credits. The prices are the smallest paid plan's where a place sells plans; a service on a larger plan pays less a search, and sets
its own. Brave's free credits are not taken off.

### Two lanes at each model server of one's own

At a model server of one's own (this machine or the private network), the gate gives the server as many calls at once as it says it
answers: llama.cpp says so on its props page, also through llama-swap, and a server that says nothing is given 4. A model entry
can set its own (`researchzosho settings set entry.default.calls 8`). An API on the internet (OpenAI, Anthropic, OpenRouter and the
like) has no limit at the gate unless its entry sets `calls`: the provider takes the load, and a chat turn there does not wait for
another account's run.

Of a server's slots, one is kept for a person who waits (a chat turn, a question asked on a page): `entry.<name>.kept`, 1 unless
set, 0 for none; never all of them. Runs, translations and nights share the rest, and none of theirs starts while a person's call
waits for a slot. One account's work holds at most three slots, and the accounts waiting take turns. The count is kept in the
store, so every node of the service keeps it together.

What a kept slot costs, measured on a server of two slots at two seconds a call, with one account's three research runs going and
another account's chat turn timed eight times:

| | the chat turn | the runs' calls a minute |
|---|---|---|
| one slot kept (the default) | 2.05 to 2.10 s (the call itself) | 29.6 |
| none kept (`entry.default.kept 0`) | 2.59 to 3.18 s | 49.5 |
| no lanes | 2.93 to 3.01 s | 50.0 |

The kept slot keeps a person's wait at their own call's time, and halves what the runs get from a server of two slots, whether
anyone is chatting or not. With none kept, the runs get the whole server and a person's call goes next, after the one call ahead
of it ends: their wait is at most one call's time, however many of the runs' calls wait. Without lanes it is behind every call
that waits. With slow calls (a long answer from a local model takes a minute) each of those waits is that much longer.

A month is the calendar month by this machine's clock. The service keeps what was spent in its store, one row each, and adds it to
the account's month in the same write: a cap is checked against the month. If the service cannot answer whether an account may
spend, nothing starts.

## Limits of this version

- Between the service, its nodes and its libraries nothing is encrypted: their addresses belong on a private network.
- As processes (the default), the libraries of different accounts run as one user of the operating system, and what keeps an
  account out of another's library is ResearchZosho's own checks. Run them that way for people you would let onto the computer;
  for anyone else, run them in containers.
- Every library is at the service's one address, so a browser treats them all as one site. A page of one library that could run
  a script of its own (a fault in how a page shows what a library holds) could act in the name of whoever opens it, everywhere on
  the service: open their other libraries, change who may read them. The four acts that would hurt most ask the person to show it
  is them again when their last sign-in or proof is more than five minutes old: making a key for a program, keeping a key of their
  own, deleting a library, and deleting the account. A key is made on the account page only, never through a library's page. A
  name of its own for each library (a subdomain) is for a later version.
- Several accounts run on Linux only (with processes or with Docker). The one-library program is not changed by this and runs on
  Linux, macOS and Windows.
- A person's own keys are for the service's model API and its paid search places. A person cannot bring a model of their own,
  and a server of the operator's own (on this machine or the private network) takes no person's key.
- Amazon Bedrock is not offered to a library under the service: the gate does not sign its requests yet.
- A fetch is a connection: several pages of one site fetched over one connection count once.
- Speech and the helpers' time are counted, not priced or capped. An embeddings or rerank server is priced only when a model entry
  names it.
- The free data sources' keys (Census, PubMed, YouTube and the rest) are not handed to a library under the service.
- YouTube's video route goes through its own proxy on this machine, not the web gate, and is not counted.
- The storage cap holds against the last measure, taken once a day: a library can grow past it within that day.
- A run stopped at its cap resumes when a person asks; it does not resume by itself when the month turns.
- The person who keeps a library sets its language and report settings with `settings` (the API or MCP); the Settings and Keep
  pages of a library under the service are not for them yet. People and keys are on the account page.
- Passkeys and one-time codes are for a single library's own sign-in, not for the account service.
- The setup runs one process of the service. More than one, behind one router, are set up by hand (the same store and the same
  `accounts_service` for each).
- The setup's files are for Linux with Docker Engine. The second machine is set up by copying its files there and starting it;
  the setup does not reach another machine.
