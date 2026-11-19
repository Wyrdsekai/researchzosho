# A library's archive

A service that serves several accounts ([ACCOUNTS.md](ACCOUNTS.md)) gives the person who keeps a library the whole of it as one
file, to take home: `<name>.rzlib.zip`. It is an ordinary zip file, which every system opens. ResearchZosho on a person's own
computer opens it as a library of their own:

```
researchzosho take ann-example.rzlib.zip                 # into a new folder beside the settings folder: ~/researchzosho-ann-example
researchzosho take ann-example.rzlib.zip ~/libraries/ann  # or into a folder you name, which must not be there yet
```

A service takes the same file in for one of its accounts:

```
researchzosho accounts take ann-example.rzlib.zip --for "Ann Example"
```

## Getting one

- On the account page, under "Your libraries": "Take it home", beside each library you keep.
- A program, with a key made for the library: `GET /v1/library/archive`, answered `application/zip`. Signed in, a browser names
  the library: `/v1/library/archive?library=<library id>`.

Only the person who keeps a library takes it home. Someone let into a library does not; a suspended account, or one being
deleted, takes none. Each archive taken is written to the service's audit log.

The archive is written as it is sent, never kept on the service's disk. When the service keeps copies of its libraries
(`accounts_copies`), the archive is made from the library's copy, brought up to date at that moment, and the library goes on
answering meanwhile; its search index is the one its last sleep left. Without copies, the library is put to sleep while it is
packed, and anyone who asks for it meanwhile is told "This library is being packed for download; it opens again in a minute."
Its running jobs stop where they are, and go on from their kept work when it starts again.

## What is in it

The entries, in this order:

1. `researchzosho-library.json`, always first: what the archive is.
2. `library/…`: the library's folder. Its findings, the pages it saved, its reports and investigations, its articles, its catalog
   (the shelves, the subjects, the sources, the records of its jobs), and its search index.
3. `home/config`, when the library has settings of its own: one `KEY = value` a line. Only the library's own settings: the language
   it writes in (`language`), how long its reports are and how they cite (`report_length`, `citation_style`), where a translation's
   doubts go (`translate_notes`), the market of its home searches (`market`), and what its nights research
   (`explorer_per_night`, `explorer_types`).

Not in it:

- the service's settings and keys: its model, its search keys, its caps;
- the people let into the library and the keys of their programs (the service keeps those, not the library);
- the readers and the keys of programs a library of a person's own kept before it came to a service (`catalog/accounts.json`,
  `catalog/keys.json`, `catalog/patrons.md`, `catalog/access-requests.md`): their hashes, but other people's;
- the webhooks people subscribed to the library's changes (`catalog/webhooks.md`): their addresses and secrets;
- the conversations of the people who read the library (`catalog/chat/readers/`): theirs, not its keeper's;
- the work a running job keeps to resume (`catalog/jobs/work/`), logs (`*.log`), locks (`*.lock`, `.lock*`, `serve.lock*`), process
  numbers (`*.pid`), and anything under a folder named `tmp`.

## The manifest

```json
{
  "format": 1,
  "library_id": "lib_f3440eb7adf91615",
  "name": "ann-example",
  "title": "Ann Example",
  "made_at": "2026-11-02T08:14:03Z",
  "packed_at": "2026-11-18T09:40:12Z",
  "from": "https://research.example.org",
  "version": "0.8.0",
  "files": 1824,
  "bytes": 48213307,
  "counts": { "catalog": 312, "findings": 640, "raw": 801, "investigations": 61, ".index": 10 },
  "settings": { "language": "Japanese", "report_length": "medium" }
}
```

| field | what |
|---|---|
| `format` | the archive's format: 1. A ResearchZosho that reads a lower number refuses a higher one and says to update |
| `library_id` | the library's id, which it keeps wherever it goes |
| `name`, `title` | its short name under the service, and its title |
| `made_at`, `packed_at` | when the library was made, and when this archive was |
| `from` | the address of the service that packed it |
| `version` | the ResearchZosho that packed it |
| `files`, `bytes` | how many files follow under `library/`, and their bytes uncompressed, `home/config` not counted |
| `counts` | the files under each folder at the top of the library |
| `settings` | the library's own settings, as `home/config` holds them, by their short names |

## Taking one in

`researchzosho take` and `accounts take` read the manifest first and refuse, saying why, and leave nothing behind:

- a file that is not a whole zip, or does not begin with the manifest;
- a format they do not read;
- an entry that names a place outside its folder (an absolute path, `..`), or anything but `library/…` and `home/config`;
- more bytes than the manifest says, or fewer files or bytes: an archive that is not whole;
- a library whose id is not the manifest's;
- not enough room on the disk;
- a folder that is there already: an existing library is never written over.

`researchzosho take` makes the library the computer's own library when there is none yet (`researchzosho serve` then opens it);
otherwise the library already there stays, and it says the line that serves the new one. It prints the archive's own settings
with the line that sets each; it changes none of the computer's settings by itself. `accounts take` gives the library to the
account named, with its own settings; under a service that had it before, it goes back to the account that kept it. Under the
service, an entry an export never carries (other people's accounts and keys, `patrons.md`, a job's kept work, a lock) is counted
against the manifest and not written: an archive made by hand cannot plant them.
