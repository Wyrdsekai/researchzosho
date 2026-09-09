# Contributing

ResearchZosho is small on purpose. A change that keeps it small is welcome. A change that adds a
dependency, a framework, or a second way to do something that already works will get an inquiry:
what does this let a person do that they could not do before?

## Building and testing

You need Java 21 or newer.

```
./gradlew :librarian:test :client:test
bin/researchzosho help
```

`bin/researchzosho` builds on first use and again whenever a source file changes, so there is no
separate build step to remember. On Windows it is `bin\researchzosho.cmd`. The Python client's live
tests need a running service: `python -m pytest sdk/python/tests`.

## The one thing that must not break

Sections 1 to 6 of `docs/LIBRARY_PROTOCOL.md` are the contract other programs rely on. Be Careful
in suggesting changes that could affect others.

## What makes a change easy to take

- It says what it does, in the code and in the commit message, in plain words.
- It comes with a proper set of tests - positive and negative tests.
- It leaves `client/` and `sdk/python/` free of dependencies. Other programs embed those.
- It has no machine names, home paths and credentials.
- It is a change for the person using the library.  The README and the guide are for people who
  are not programmers. If a sentence needs a programmer to understand it, it belongs in the protocol
  document.

## Reporting a problem

Open an issue with the command you ran, what you expected, and what happened instead. If an answer
or a write-up was wrong, include the id of the finding or the investigation.
