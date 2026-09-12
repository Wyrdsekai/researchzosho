# @wyrdsekai/researchzosho-mcp

Starts [ResearchZosho](https://researchzosho.org), the research librarian for the rest of us, as an MCP server
over stdio. Any MCP client that can run `npx` can run it:

```json
{ "mcpServers": { "librarian": { "command": "npx", "args": ["-y", "@wyrdsekai/researchzosho-mcp"] } } }
```

```
claude mcp add --scope user librarian -- npx -y @wyrdsekai/researchzosho-mcp
```

There is no Java in this package and no copy of the program. The launcher finds an installed ResearchZosho
and starts `researchzosho mcp`. When none is installed it fetches the release of the same version from GitHub,
checks it against the release's own `SHA256SUMS` (no sums, no install), unpacks it under `~/.researchzosho/launcher`,
and starts it: the small tarball when Java 21 or newer is on the machine, otherwise the build for this platform
that carries its own runtime, so nothing has to be installed first. A machine with no library gets one made at `~/researchzosho-library`; `researchzosho setup`
names a model server and installs the service, and the pages at http://127.0.0.1:4649/ come with it.

Nothing but the MCP stream is written to stdout. Every message from the launcher goes to stderr.

| variable | meaning |
|---|---|
| `RESEARCHZOSHO_PREFIX` | where the one-line installer put the program (default `~/.local`, or `%LOCALAPPDATA%\Programs`) |
| `RESEARCHZOSHO_LIBRARY` | the library folder (default `~/researchzosho-library`) |

The version of this package is the version of ResearchZosho it starts. Docs:
[LIBRARIAN_HOWTOUSE.md](https://github.com/Wyrdsekai/researchzosho/blob/main/docs/LIBRARIAN_HOWTOUSE.md),
[LIBRARY_PROTOCOL.md](https://github.com/Wyrdsekai/researchzosho/blob/main/docs/LIBRARY_PROTOCOL.md).
License: Apache 2.0.
