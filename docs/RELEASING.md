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

The platform pass installs through the one-line installer from a local server, drives the verbs on a
fresh library, fetches the pages, installs the service, swaps in a fake next version through
`update now`, installs the runtime build and updates it too, and uninstalls. On a Mac it also
quarantines every file of the runtime build and runs it, which is what a browser download meets:
the runtime's binaries carry Adoptium's Developer ID signature and notarization, and jlink copies
them unchanged, so no signing of ours is involved. Run the pass on every platform the release
claims; the tests are `./gradlew :librarian:test :client:test`, the same command CI runs.

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
