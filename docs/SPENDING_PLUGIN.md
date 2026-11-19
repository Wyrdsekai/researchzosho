# The spending plug-in

A ResearchZosho account service asks, before each use, whether the account may start it, and says, after it, what it came to.
Unless told otherwise it answers itself from its caps ([ACCOUNTS.md](ACCOUNTS.md), "Use and caps"). A service run for pay can
answer from a program of its own instead: the plug-in. This page is the whole protocol; a plug-in can be built from it alone.

```
researchzosho settings set accounts_spending https://billing.example.org/researchzosho
```

Restart the service after setting it. Unset, or `off`, the built-in caps answer again.

## Two calls

| call | when | the plug-in answers |
|---|---|---|
| `POST <accounts_spending>/may-start` | before each use starts | yes or no, within 5 seconds |
| `POST <accounts_spending>/spent` | after each use has ended | that it has taken it |
| `POST <accounts_spending>/account-deleted` | after an account is removed | that it has taken it (404 or 405: a plug-in without it) |

`<accounts_spending>` is the setting exactly as written, without a slash at its end. Each request's body is a JWT (below), sent
with `Content-Type: application/jwt`. Use HTTPS between the service and the plug-in unless both are on one private network.

## The signature

Every body is a JWT in compact form, `base64url(header) "." base64url(claims) "." base64url(signature)`, base64url without
padding.

- The header is `{"alg":"ES256","typ":"rz-spend+jwt","kid":"<kid>"}`. The type keeps these tokens apart from the statements
  the same key signs for libraries.
- The signature is ECDSA on P-256 with SHA-256 over the ASCII bytes of `base64url(header) "." base64url(claims)`, written as
  `r || s`, 32 bytes each (64 bytes, as JWS writes ES256).
- The keys are the service's own, the ones its statements to its libraries are signed with, published at
  `<the service's address>/.well-known/jwks.json` as `{"keys":[{"kty":"EC","crv":"P-256","x":"…","y":"…","kid":"…","alg":"ES256","use":"sig"}]}`.

A plug-in checks each call, and answers 401 to one that fails any of these:

1. The header's `alg` is `ES256`, and its `kid` is a key of the service's JWKS. Keep the JWKS, and fetch it again when a `kid` is
   not in it.
2. The signature checks against that key.
3. `iss` is the service's name: `accounts_service` when it is set, else the address the service listens on.
4. `aud` is the plug-in's own address, as `accounts_spending` has it.
5. `exp` has not passed, and `iat` is not ahead of the plug-in's clock by more than a minute. A call is good for 300 seconds.

In Python with PyJWT:

```python
import jwt
keys = jwt.PyJWKClient("https://research.example.org/.well-known/jwks.json")
claims = jwt.decode(token, keys.get_signing_key_from_jwt(token).key, algorithms=["ES256"],
                    audience="https://billing.example.org/researchzosho", issuer="https://research.example.org")
```

## The claims both calls carry

| claim | what |
|---|---|
| `iss`, `aud`, `iat`, `exp` | as above; `iat` and `exp` in seconds since 1970 |
| `jti` | the use's id, `use_…`: the same on every call about that use (a `spent` told again with what came later has its own, below) |
| `kind` | `may_start` or `spent` |
| `use` | the use, below |

`use` is an object:

| field | what |
|---|---|
| `id` | the use's id, `use_…` (the `jti`, but for a `spent` told again) |
| `account` | the account's id, `acct_…`, which does not change |
| `account_name` | the name the account signs in with, as it is now |
| `library` | the library's id, `lib_…` |
| `kind` | `research`, `translation`, `picture`, `other` (jobs); `chat` (a chat turn); `nightly` (a library's night, the keeper's); `add` (a page added by its address); `upload` (a document sent from a person's computer); `page` (a page's or a program's own call outside any run, not a job) |
| `entry` | the model entry the use runs on (`default` for the service's model) |
| `payer` | `operator`, or `own` when the person's own key carries the use's model |
| `estimate_dollars`, `estimate_minutes` | what the service expects the use to take, from this library's past uses |
| `job` | the job's id in the library, for a job; else empty |

## may-start

The claims also carry `lang` (the person's language, `en`, `ja`…), `month` (`2026-11`, by the service's clock) and
`month_totals`, the account's month so far, a list of
`{"kind":"model|search|fetch|background|helper|speech|storage","payer":"operator|own","quantity":…,"tokens":…,"bytes":…,"dollars":…,"estimated":…}`.
`quantity` is calls for `model`, searches, connections to the web for `fetch` (the account's work) and `background` (a library's
own, such as a check for a newer version), seconds for `helper` and `speech`, and megabyte-days for `storage`; `estimated` counts the model calls whose tokens were worked out from their size.

The answer is a 200 with a JSON object:

```json
{"yes": true, "said": "About $0.40; $3.10 left on your plan.", "ceiling_dollars": 2.5, "ceiling_minutes": 20}
{"yes": false, "said": "Your plan has no research left this month."}
```

| field | what |
|---|---|
| `yes` | true or false; required |
| `said` | the sentence the person is shown, in their language (`lang`); optional. On a no it is the reason, on a yes a line before the work |
| `ceiling_dollars` | the most the use may spend of the operator's dollars; -1 or missing for no limit |
| `ceiling_minutes` | the most minutes a job may run; -1 or missing for no limit |

**What no answer means.** Anything else is a no: no answer within 5 seconds (the connection included), an answer that is not
a 200, a body that is not JSON, or JSON whose `yes` is not true or false. The person is then told "The service could not check
this account's spending just now, so nothing was started. Ask again in a minute." A no without `said` is told "Whoever runs this
service does not let this start now." Nothing starts on a no, whatever its reason.

A use whose `payer` is `own` (the person's own key carries its model) still pays the operator for some of its calls (searches
without their key, helpers). When the answer gives it no `ceiling_dollars`, the service holds those to `accounts_own_use_ceiling`
($1 unless set, `off` for none); give it one of your own to hold them otherwise. A job past its `ceiling_minutes` is stopped by the
gates' watch as at its ceiling.

Once a use has started, the node's gates hold it to its ceiling: a call paid by the operator is refused once the use has spent
its ceiling and a tenth more (at least $0.25), so the work's last step can finish. With a plug-in the gates hold only that: the
built-in month, searches, fetches and storage caps are the plug-in's to decide in its answers. Work on a person's own key is
theirs and is not held to the ceiling.

## spent

The claims also carry `actual`:

| field | what |
|---|---|
| `dollars` | what the use came to, every payer |
| `model_tokens`, `searches`, `fetches` | counts under it |
| `estimated` | true when some of its model calls were counted from their size |
| `totals` | by kind and payer, in the form of `month_totals` |

Any 2xx is taken. Anything else, or no answer within 10 seconds, is not taken.

**The outbox.** When a use ends, what it came to is written in the service's store, at the end of the outbox, before anything is
sent. The service sends the outbox in the order it was written, one at a time. When one is not taken it is sent again after 5
seconds, then after 10, 20, 40, and so on, never more than 10 minutes apart, until it is taken; nothing written after it is sent
meanwhile. One the plug-in refuses with a 4xx five times (it will never take it) is set aside: the operator's page lists it with
the answer it got and "Send it again", and what was written after it is sent. Nothing is dropped. One process of the service sends
at a time. The operator's page says how many wait.

So the same `spent` can come more than once, when the plug-in's 2xx was lost on the way back. Keep the `jti`s taken, and answer
2xx again to one already taken. On each try `iat` and `exp` are new; `jti` and everything else are as they were.

**What came later.** A call that began before its use ended can be counted after it. The service then tells the use's `spent` again,
with a `jti` of its own (the use's id, `~`, and the millionths of a dollar it came to now) and the whole of the use in `actual`. It
replaces what was told of that `use.id` before. The service closes a use that nobody ends (a library stopped in the middle of it) as
failed, and tells its `spent` the same way.

## account-deleted

When an account is removed, its open uses are closed first, each with its `spent`, so those come before the notice. The service
then writes a notice at the end of the outbox, sent as the rest are: `kind` `account_deleted`,
`jti` `del_` and the account's id, and `account`, the account's id (no name: the service has forgotten it). Forget what you keep of
that account and answer 2xx. A plug-in without this call answers 404 or 405, and the notice counts as taken.

## What the plug-in is not asked

How much a call costs: the service prices model calls at its model entries' prices and searches at `search_price.<place>`, and
says the dollars. Whether a person may open a library: that is the service's. The plug-in answers about money and use only.
