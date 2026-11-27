# Releasing ResearchZosho

How a release is built, checked and published, and what the release workflow does after that. The
short form: the bytes people install are built and tried on real machines; CI signs what was
published and builds nothing.

## What a release carries

| asset | made by |
|---|---|
| `researchzosho-X.Y.Z.tar.gz` | `scripts/release-build.sh` — the program for Linux, macOS and Windows, Java 21 or newer needed |
| `researchzosho-X.Y.Z-<platform>.tar.gz` for linux-x64, linux-arm64, macos-x64, macos-arm64, windows-x64 | `scripts/package-runtime.sh`, run by the release build — the same program with its own Java runtime (Temurin 21, trimmed by jlink to the modules the jars use), nothing to install first |
| `SHA256SUMS` | the release build — every tarball, bare file names |
| `<asset>.sigstore.json` | the release workflow, after publication |
| `ghcr.io/wyrdsekai/researchzosho:X.Y.Z` | the release workflow, from the attested tarball |
| `@wyrdsekai/researchzosho-mcp@X.Y.Z` on npm | the release workflow, from `npm/` |
| `io.github.Wyrdsekai/researchzosho` in the MCP Registry | the release workflow, from `server.json` |

The version lives in `build.gradle.kts`; `npm/package.json`, `server.json` and `docker-compose.yml`
must name the same one, and `release-build.sh` refuses to build when they do not. The workflow
checks them against the tag again.

## Build and check

```
scripts/release-build.sh                         # dist/: the tarball, the five runtime builds, SHA256SUMS
scripts/verify-platform.sh dist X.Y.Z scripts/install-remote.sh   # Linux and macOS, on each box
powershell -File scripts\verify-platform.ps1 -Dist <dir> -Ver X.Y.Z   # Windows
cd npm && LAUNCHER_TEST_DIST=../dist npm test    # the npm launcher against the built release
```

Before it builds, `release-build.sh` runs `researchzosho model check`. For every file the program downloads by a pin
(the model rows and their picture files, the embeddings model, the comic helper's models, fonts and word lists) it asks
Hugging Face for the file at the pinned revision and compares the sha256 and size Hugging Face gives with the pin; a file
on GitHub is compared by its size, and each llama-swap and llama.cpp build by the sha256 in its release's listing. A pin
that does not resolve or does not match stops the release. A file whose `main` has a newer upload is said in a `note`
line and does not stop it: that is how a newer quantization is learned of, and a pin moves only after the new file is
measured. On 2026-11-26 the 27B's `main` carried such a note (its file was replaced on 2026-11-11); before 0.8.4 the check
only asked whether each file at `main` answered, so it passed while every install of the 27B failed.

The platform pass installs through the one-line installer from a local server, drives the verbs on a
fresh library, fetches the pages, installs the service, installs a fake next version beside the
running one through `update now`, installs the runtime build and updates it too, and uninstalls. On a Mac it also
quarantines every file of the runtime build and runs it, which is what a browser download meets:
the runtime's binaries carry Adoptium's Developer ID signature and notarization, and jlink copies
them unchanged, so no signing of ours is involved. Run the pass on every platform the release
claims; the tests are `./gradlew :librarian:test :client:test`, the same command CI runs.

### The pinned models' chat templates

```
python3 scripts/chat-templates.py --sums    # one line per model file `model install` fetches
python3 scripts/chat-templates.py           # each template in full
```

A model file is numbers, except for its chat template: a small text the model server follows to lay a conversation out for
the model. `model install` pins each file to a revision of its repository and by its checksum, so a template cannot change
without the pin failing; the script reads each file at its row's revision. When a row of `ModelServer.ROWS` is added, or a
row's revision or checksum changes, read that file's template before the release. It should hold
nothing but the laying out of a conversation (`if`, `for`, `set`, `macro`, the string helpers) and no address.

As read for 0.8.1 on 2026-11-20 (six files, five templates; the two Gemma files share one):

| row | characters | SHA-256 of the template |
|---|---|---|
| qwen3.8-27b | 9,993 | `12827f24b742ea4e80cdc12dbcf9622227056b9f797252a3149263d4f9aaadce` |
| qwen3.6-35b-a3b | 8,057 | `55d4931433fe502b794226ee7f4d206a6bdd436ac9f80eb7d8ebb4c639f9ea0c` |
| gpt-oss-20b | 17,221 | `70da0d2348e40aaf8dad05f04a316835fd10547bd7e3392ce337e4c79ba91c01` |
| qwen3.5-9b | 7,816 | `7f0e529032c25183bcd66c7f238da2d377f43be754a94e2725a58c4e16d2ed67` |
| gemma-4-e4b, gemma-4-e2b | 18,808 | `241c50d86bdfe5e43307da87f559cd2416aacd67a8de46c15acc0105ef2200b7` |

## Publish

Create the GitHub release with every file in `dist/` attached. That fires
`.github/workflows/release.yml`, which downloads each asset, checks it against the release's own
`SHA256SUMS`, attests it with Sigstore, and uploads the bundle beside it. Then, from the attested
tarball: `image` pushes the container image, `launcher` publishes the npm package, and `registry`
publishes `server.json` to the MCP Registry. Released artifacts are immutable: a bad one is a new
version, never a re-upload.

Verify from any machine with the GitHub CLI:

```
gh attestation verify researchzosho-X.Y.Z.tar.gz --repo Wyrdsekai/researchzosho \
  --predicate-type https://researchzosho.org/attestation/release/v1
```

## Set up once, by hand

The workflow publishes to npm and to the registry without tokens, through GitHub's OIDC identity.
Both need a first step by a person:

1. **npm.** The `@wyrdsekai` scope belongs to the maintainer's npm account. The first version of
   the launcher is published by hand, after the GitHub release exists, because npm's trusted
   publishing is configured on a package that already exists:
   ```
   cd npm && npm login && npm publish --access public
   ```
   Then, on npmjs.com, the package's settings: Trusted publisher → GitHub Actions, organization
   `Wyrdsekai`, repository `researchzosho`, workflow `release.yml`. From the next release the
   workflow publishes; a version already on npm is left alone.
2. **MCP Registry.** `io.github.Wyrdsekai/*` is granted to an owner of the GitHub organization.
   The first publish is by hand from the repository root: `mcp-publisher login github`, then
   `mcp-publisher publish`. In the workflow, `mcp-publisher login github-oidc` needs nothing
   further. The registry is in preview and may reset; a reset means publishing again from the tag.
3. **ghcr.io.** Nothing: the workflow's own token pushes the image under the organization.

## The site

`researchzosho.org` serves `scripts/install-remote.sh` as `/install` and `scripts/install.ps1` as
`/install.ps1`, copied at deploy time. Neither pins a version — both resolve the latest release
when run — so a release does not need them edited. The download page lists every asset with its
checksum.
