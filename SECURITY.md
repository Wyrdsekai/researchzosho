# Security

## Reporting

If you find a security problem in ResearchZosho, write to security@researchzosho.org. Say what you
found and how to see it. You will get a reply, and a fix or an explanation, before anything is made
public.

## What a release is, and how to check one

A release is one file, `researchzosho-X.Y.Z.tar.gz`, published on GitHub with a `SHA256SUMS` file
beside it. The one-line installers download both from the same release. They refuse to install
anything the checksums do not match.

After a release is published, the project's own GitHub workflow signs the tarball with Sigstore. It
uploads `researchzosho-X.Y.Z.tar.gz.sigstore.json` beside the tarball. The signature means "the
ResearchZosho release workflow, at this tag, approved these exact bytes". It does not say the workflow
built them. The artifact is built and tried on real machines first, and the signature says so.

To check a download yourself, with the GitHub CLI (2.49 or newer):

```
gh attestation verify researchzosho-X.Y.Z.tar.gz --repo Wyrdsekai/researchzosho \
  --predicate-type https://researchzosho.org/attestation/release/v1
```

Without `--predicate-type` the command looks for build provenance. This project does not produce
that. The command then prints its failure only to a terminal. Always pass the flag.

## What leaves your machine

Your library is a folder on your disk. Nothing is sent anywhere to be stored. This section says what the program, and the
programs it starts for you, send out, and to whom.

### A model on your own machine

Measured on 2026-11-20 with version 0.8.0 on Linux and a Qwen model: in two 25-minute sessions of research and chat, one of
them a research run over a private document, the model server sent no packet to any address outside the machine and looked
up no name.

A model file is numbers, with one exception: its chat template, a small text the model server follows to lay a conversation
out for the model. `model install` pins every file it fetches by its checksum, and the template of each pinned file is read
before a release (`scripts/chat-templates.py` prints them). A template can shape the text the model is given; the server
gives it no way to open a file or an address.

Installing a model fetches its file from Hugging Face and the model server from GitHub's registry.

From version 0.8.1 this does not rest on the measurement alone. On Linux, where the model server and the embeddings server
run in containers, `researchzosho model install` sets both up with no way out to the network. Their containers are on a
Docker network that has no route to anything but your machine, with no port of your machine published and without the right
to write raw packets; the library reaches them at their own addresses on that network. So nothing a model file, a chat
template or a fault in the model server does can send anything off your machine. The install tries this before it
finishes: the model has to answer through it, and a request to an outside address, which reaches its target from an
ordinary container, has to fail to connect from inside. When that does not hold on a machine (a firewall, another
container runtime, no free address range), the install sets the server up the earlier way and says so.

`researchzosho model status` says which way your model server is set up, and why. One set up by an earlier version stays as
it is until you run `researchzosho model install` again. `researchzosho settings set model_network open`, then `model
install`, chooses the earlier way on purpose, for a model server that has to reach a network.

What this leaves: the containers still reach your machine itself, so a program on your machine that listens on every
address and passes things on (a proxy, a name server) would be a way out. On macOS and Windows the model server is an
ordinary program, not a container, and nothing keeps it from the network.

A model cannot send anything itself. What it writes can make the library act: a search, a page opened. That is what the
parts below are about.

### The embeddings server

On an NVIDIA card the embeddings server is Hugging Face's Text Embeddings Inference, in a container. From version 0.8.1
`researchzosho model install` and `researchzosho embed start` fetch its model themselves, six files that are each checked
against a recorded checksum, and give the server their folder, read-only. Started that way it asks nobody for anything: its
answers are the same numbers, and it sent no packet to any outside address. Set up by `model install` on Linux it also has
no way out, like the model server (above).

Before 0.8.1 the server was started by its model's name, and each time it started it made one connection to
`huggingface.co` and asked for its model's configuration files by name. That request carries the model's name and your
machine's address, and no text of your library: none has reached the server before it has started. After that it sent
nothing. A server set up by an earlier version goes on doing this until you run `researchzosho model install` again; one
that `embed start` made under an earlier version, until its container is made again. On AMD and Intel cards the embeddings
server is llama.cpp, which sent nothing at all. The container `embed start` runs by itself, without `model install`, has
a port of your machine published and is not kept from the network.

### The search service

The search service (SearXNG, in a container on your machine) sends the words of each search to the search engines that are
switched on, from your machine's address. With the settings this version writes those are Seznam (Czech Republic), Naver
(South Korea), Yahoo (United States) and Wikipedia. When it starts it fetches a list of tracking parameters from
`rules1.clearurls.xyz`, which it uses to clean the links in results, and looks up the servers of a radio directory. After
that it sends nothing until the first search.

In its requests to Wikipedia, Wikidata and OpenStreetMap the search service names this project's site, researchzosho.org, as
its contact, and no person: the current SearXNG does not load those engines without one.

The program fetches the search service's image once. `researchzosho search status` says when that image is more than 90 days
old, and `researchzosho search update` fetches the current one: the engines change, and an old image's readers of them stop
getting answers.

Engines based in the places your library avoids (below) are not loaded at all, so no search reaches them, and neither does
a shortcut typed into a search. The words of a search are sent as words: a search cannot choose its engine.

To put an engine back, edit `~/.researchzosho/searxng/settings.yml`: take the engine's line out of the list under
`remove:`, add it under `engines:` with `disabled: false`, then run `researchzosho search stop` and `researchzosho search
start`. A settings file you have edited is yours, whether you changed a line, added one or only took one out: the file
carries a check of its own text in a comment at its top, and once the text no longer answers to it the program does not
write the file again. It uses that engine's results like any other's; `researchzosho search status` says which engines of
an avoided place the file loads.

With a key for a search service set (Brave, Serper, SerpApi, Exa, Tavily), searches go to that service first, and the
search service on your machine is the fallback. `researchzosho search status` says the order.

### The library itself

These are the hosts the library's own process connected to in the two sessions measured on 2026-11-20, and why.

| host | what for |
|---|---|
| `web.archive.org` | the Internet Archive's copy of a page that did not open |
| `api.crossref.org`, `api.openalex.org` | the search for papers, and the check of each cited DOI |
| `doi.org` | a DOI resolved to its publisher's page |
| `en.wikipedia.org` | the built-in fallback search, or a page a run opened |
| `api.github.com`, `raw.githubusercontent.com` | the program's own documentation, fetched once per version; with updates on, the newest release's number |
| `nsfw.oisd.nl` | the public list of adult-content sites the page check uses, fetched at most once a day |
| the sites of the pages a run opened | 27 hosts in the two sessions, each named by a search result |

The other places the library can ask are the services its search places are written for (PubMed, Open Library, the Library
of Congress and the like), each with the words of the search and nothing else of your library.

The library refuses to fetch addresses on your own machine, link-local addresses and the addresses of its own services,
whoever asks. `RESEARCHZOSHO_FETCH_PRIVATE=deny` makes it refuse your whole private network too.

The list from `nsfw.oisd.nl` (`https://nsfw.oisd.nl/domainswild2`, about 8 MB) goes into the ResearchZosho folder
(`~/.researchzosho/site-list/`), not into a library. Nothing is sent with the request but the program's name. When a
download fails, the list already on disk goes on being used; until the first download arrives, a smaller list that ships
with the program is used. `RESEARCHZOSHO_SITE_LIST=off` stops the download.

### What a model can make the library ask

While the library carries out something a model asked for, a request is sent only when it is one of these:

- **an address that was given**: one you wrote, or one that a search, a page or a tool returned. The request is made in the
  spelling and with the scheme it was given in, whichever way the model wrote it: a slash after it, `www.` before the host
  added or left off, a letter as an escape, `http` for `https` all become the given address again. An address with its scheme's own port written out (`:443` after
  `https`, `:80` after `http`) is the same address as the one without it; any other port is another program's door, and a
  page given at one port does not open another. An address you wrote without `http://` counts too: a name
  (`example.org/page`), and a machine by its numbers, alone or with a port or a path (`192.168.1.1`, `192.168.1.20:8096/web`).
  Numbers are taken as an address only from your own words, never from a page's. You named no scheme for them, so they are
  asked by `https`, or by plain `http` when the model writes it so, which is what a router or a media server at home answers;
- **a service this program is written for, or one of yours**: the model's words go there as the words of a search, never as
  an address. Your machine itself and a machine named in your settings count as yours at any port, when the model's words
  do not name them: the model server, the search service and the helpers answer there;
- **where a server sends a request on**. A request for a given address holds nothing of the model's, so its server may send
  it anywhere, as a link on its page would: to `www`, to `https`, to a new address, to another site. A request to a
  service may hold the model's words, so it is followed only to another such service, within the same site, or to a
  given address. A DOI is followed to its publisher when the DOI was given.

A page's address handed to a service that reads pages for the library (a web archive's saved copy, a search service's page
reader) has to be a given address too.

You can name a thing without its address. The library builds the address itself for a preprint by its arXiv number, a work by
its DOI, a repository by its owner and name on GitHub, a Wikipedia article by its title, and the front page or `robots.txt` of
a site you gave a page of. The name or the number has to stand in your words or in something a search or a page returned.

Anything else is refused before it is sent, and the refusal is written into the run's trace or the chat's record with the
tool, the host and the rule. Two tools have to ask hosts they find while they run, and are held to rules of their own: the
records lookup asks the data portals of a street's county, with a street and a city that you gave or that a page stated,
never ones the model made up, and a state that is a real one and does not contradict the one you gave (the city and the
state may be written with a comma between them or without, and a city's short form with its full stop: St.Louis,
Washington D.C.); the job board lookup
reads an employer's own careers page, with no word of the model's.

What this leaves:

- The words of a search are the model's to choose, and they go to the search engines.
- A model chooses which of the given addresses is opened. To somebody who could watch those sites, each choice among sixteen
  addresses says four bits, as each choice among N says log2 N.
- The site of a page that is opened learns your machine's address and the time, as with any browser.
- The records lookup sends the street you asked about to the data portals it finds for that place. When you name no state,
  the model names it, out of the fifty.
- An address built from a name or a number goes out with the name as the model spelled it: its capitals and the marks
  between its words. It goes to arXiv, the DOI resolver, GitHub or Wikipedia, and nowhere else.

### The pages of the web app

A page of the web app loads nothing from anywhere but the library itself. Its scripts are the library's own files. A picture
found on the web is fetched by the library and passed on, so your browser asks no other site for it; it is passed on to the
reader whose own page showed it, and to no other reader of the same library; a page the library writes with no reader
asking shows its pictures to nobody. Writing a page looks no name up: whether an
address in a model's words leads to this machine is read off the address as written. A tag that got into a
page could not run and could not call out: the page's policy allows no script and no handler written into a page, and no
picture, form or request to any other address.

A link a model wrote in a report shows the host it leads to. A link a model wrote that leads back into the library is a
link only to something the library holds for reading: an entry, a run's page, a kept picture, an exported file. An address
of a page that starts work or sends words out stays plain words, however it is written. A picture a model wrote is drawn
only when it is a picture the library keeps. Nothing changes because an address was opened: signing out, keeping a language and starting a translation are
buttons of the library's own pages, and the same request from another site is refused.

A report or a note written out as Markdown for another program (an export, the vault) holds no picture of the web and no tag
that could load one, in code samples too: there a picture's mark loses its `!` and a tag with an attribute is written with
`&lt;`.

### A hosted model

When you set a hosted model, everything the library asks the model goes to that host. `researchzosho models` says for each
model where that is.
Your API key is sent to that server's host and nowhere else.

Through OpenRouter a model is served by one of several providers, and OpenRouter picks. Every request the library sends
tells it which providers to leave out: those based or running in a place your library avoids, by OpenRouter's own list of
providers, and those owned by a company based in such a place, by a dated table that ships with the program and names a
public source for each. Checked against OpenRouter on 2026-11-20 with a model whose cheapest provider was in China:

| the request said | answer |
|---|---|
| only Baidu | served by Baidu |
| only Baidu, and leave Baidu out | refused: no provider left |
| leave the listed providers out (five requests) | served each time by a provider that is not on the list |

When every provider of a model is left out, nothing is sent, and the chat or the run says so with the setting that allows
them. `researchzosho settings set avoid_by_owner off` lets the providers that are left out for their owner alone serve;
`researchzosho settings set avoid_places none` lets every provider serve.

Hugging Face's router picks a provider too, and a request to it cannot say which places to leave out. `models add` says so
when you add such a model, and how to keep it to one provider.

### The places your library avoids

Unless you say otherwise: China with Hong Kong and Macau, Cuba, Iran, North Korea, Russia and Venezuela. These are the
countries of concern of the United States Department of Justice's rule on access to sensitive personal data (28 CFR 202.601,
in effect since 2025-04-08; 28 CFR 202.208 defines China with Hong Kong and Macau). The list has a stated basis so that it is
not one picked by hand; it is yours to change:

```
researchzosho settings set avoid_places "CN, HK, MO"     # other places
researchzosho settings set avoid_places none             # no place
```

The one setting covers both the providers of hosted models and the search engines. In the chat a model can ask to change
it; nothing changes before you say yes, and the question says in plain words what a yes would let in.

### Other MCP servers

What the library sends to an MCP server you add goes to that server. The library reads what the server answers; whether it
may open the addresses in those answers is your switch for each server (`researchzosho mcp links <name> on`). It is on for
the search engines' servers the library knows by name, and off for a server of your own until you switch it on, because a
server that computes on what it is sent could hand a model any address.
