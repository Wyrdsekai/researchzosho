# Files the pages load from the library itself

The daemon serves every file in this folder at `/static/<name>` (`Pages.staticFile`), to anyone, with a cache life of a year:
a file's name carries its version, so a new version is a new name.

## mermaid-11.17.2.min.js

Mermaid 11.17.2, the browser build `dist/mermaid.min.js` of the npm package `mermaid`
(https://registry.npmjs.org/mermaid/-/mermaid-11.17.2.tgz, published 2026-08-25; the tarball's sha512 matched the registry's integrity field).
MIT; the licence is `mermaid-LICENSE.txt` beside it. Taken as data, never run here.

    sha256  581ed7d74bd9048d0e3a91363927d72ef22942d7722546b27f7cc29e35390eb8
    size    3572661 bytes

Served at `/static/mermaid-11.17.2.min.js`; `Pages.MERMAID_VERSION` names the version, and a page that holds a ```mermaid block loads it
(`Pages.page`) and draws the block in the browser (`Pages.diagramJs`), strict, in the page's light or dark look.

To update: fetch the new release's tarball, check its sha512 against the registry, copy `package/dist/mermaid.min.js` here as
`mermaid-<version>.min.js` and `package/LICENSE` as `mermaid-LICENSE.txt`, write the version, the sha256 and the size here, change
`Pages.MERMAID_VERSION`, remove the old file, and run `DiagramTest`, which reads the checksum from this file.
