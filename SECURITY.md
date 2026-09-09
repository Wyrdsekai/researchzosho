# Security

## Reporting

If you find a security problem in ResearchZosho, write to security@researchzosho.org. Say what you found
and how to see it. You will get a reply, and a fix or an explanation, before anything is made public.

## What a release is, and how to check one

A release is one file, `researchzosho-X.Y.Z.tar.gz`, published on GitHub with a `SHA256SUMS` file
beside it. The one-line installers download both from the same release and refuse to install
anything the checksums do not match.

After a release is published, the project's own GitHub workflow signs the tarball with Sigstore and
uploads `researchzosho-X.Y.Z.tar.gz.sigstore.json` beside it. The signature means "the ResearchZosho
release workflow, at this tag, blessed these exact bytes". It is not a statement that the workflow
built them; the artifact is built and tried on real machines first, and the signature says so.

To check a download yourself, with the GitHub CLI (2.49 or newer):

```
gh attestation verify researchzosho-X.Y.Z.tar.gz --repo Wyrdsekai/researchzosho \
  --predicate-type https://researchzosho.org/attestation/release/v1
```

Without `--predicate-type` the command looks for build provenance, which this project does not
produce, and prints its failure only to a terminal. Always pass it.

## What the program reaches out to

- The model server you name, and only that one. Your API key, if you set one, is sent to that
  server's host and nowhere else.
- The web, when you ask it to research or when a saved search runs. It refuses to fetch addresses on
  your own machine, link-local addresses, and the addresses of its own services, and it can be told
  to refuse your whole private network with `RESEARCHZOSHO_FETCH_PRIVATE=deny`.
- Public record services when a document carries a DOI, an arXiv id or a PubMed id, to look up its
  citation.

Everything it keeps is in the library folder on your disk. Nothing is sent anywhere to be stored.
