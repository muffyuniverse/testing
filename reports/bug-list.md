# Bug list — round two, all known bugs

Compiled 2026-11-15 by the agent in project `testing`, branch `main`.
Every entry below was reproduced in this session or in the round recorded in
[boundary-report.md](boundary-report.md). Bugs that the upgrade fixed are **not** listed here;
they are in [upgrade-report.md](upgrade-report.md) §1.

Legend: **new** = found in this round · **open** = reported before, still reproduces ·
**partial** = improved but not fixed.

---

## 1. `pipe` refuses `name` — open, new

The documented contract says a result may be filed under a predictable name ("other results
use `agent/background_results/`"), but `pipe` answers:

> `pipe` does not take `name`. It takes only `args`, `steps`, `tool`.

Repro: `pipe {"name":"pipe-out.txt","steps":[{"tool":"write",…}]}`. The whole call fails, so
the steps do not run either. A long chain has to end in a `read`/`write` step to expose its
result instead.

## 2. The hand-written-credential guard gives invalid remediation — open, new

`http` now refuses literal credentials in `Authorization`, `Cookie` and `X-Api-Key`
(good), but the advice is wrong for two of them:

> `Cookie` cannot be written by hand. … write `Cookie: Bearer {{secret:<name>}}`
> `X-Api-Key` cannot be written by hand. … write `X-Api-Key: Bearer {{secret:<name>}}`

A cookie header is `Cookie: <name>=<value>`; `Bearer` is not valid there, and `X-Api-Key`
carries a bare key. Following the advice produces a malformed request. The `Authorization`
message is correct.

## 3. Cross-host redirects are followed silently — open

`GET https://httpbin.org/redirect-to?url=https://example.com/` answers `200` with
example.com's body and example.com's headers (`Server: cloudflare`), and nothing in the answer
or in `response_headers` names the final address or the hop. The typed address is the only one
the agent can see, so "I fetched X" cannot be verified. Host binding is checked against the
typed address only.

## 4. `Content-Length` is rendered twice — open, new

Every `response_headers` answer for a served file repeats the value:

```
Content-Length: 647, 647
Content-Length: 50, 50        (206 partial)
Content-Length: 1987, 1987
```

Cosmetic, but it makes a header table unparsable for a reader that expects one value.

## 5. `http` blames "browsing" for a scheme it will not fetch — open

`GET file:///etc/passwd` → "Only http and https can be browsed, and that is file."
Same family as the old private-address message; the tool is `http`, not `browse`.

## 6. `serve` advertises a capture log before it exists — open, new

`serve {capture:true}` prints "Captured requests log: `agent/serve/sec/requests.jsonl`", but
reading that path before the first request answers "There is no file at …". Reading it as an
empty log, or creating it at start, would match the promise.

## 7. `json` extraction needs `direct:true` on `http` steps — open

A normal `http` step's body is the human summary, so a later step with
`{"step":"s1","json":"rate.limit"}` falls back to its `default`, while
`{"step":"s1","regex":"\"limit\":([0-9]+)","group":1}` on the same step returns `5000`.
`json` itself is healthy: it resolves numbers, booleans and `paths.0` from a
`list {format:json}` step. **Workaround:** `direct:true` on the http step.

## 8. A sealed archive cannot be unsealed while its source exists — partial

`seal` leaves the source in place, and `unseal` derives the output name by stripping `.enc`:

> The output probe.zip already exists; it was not overwritten. Choose a different `name`.

`unseal` has no `name` argument, so the advice cannot be followed. Deleting the source first
makes the round-trip work, and a wrong key now fails cleanly
("Authentication failed: this key does not open it…"). The naming is the remaining defect.

## 9. Unpacking into an existing folder forks it — open

`unpack probe.zip` with `probe/` present produced a folder literally named `probe 2`. There is
no destination argument to say "merge" or "overwrite", and the forked copy is easy to miss.

## 10. `http` mangles a malformed request document — open

`this is not a request` → `"THIS" is not a method this app can send.` The first word is
uppercased, so the error quotes text the agent never wrote.

## 11. Bare `PRAGMA` is refused as a write — open

`PRAGMA table_info(agent_rows)` → "Only SELECT and WITH queries are supported; the person's
database is read-only here." The gate is keyword-based and misdescribes a read as write
access. `SELECT * FROM pragma_table_info('agent_rows')` works.

## 12. `forget` cannot be used before the clear-up point — open

At ~46k tokens: "Your context is about 46k tokens, under the 100k clear-up point, so nothing
needs to go." An agent cannot prune its own context while it still fits, so a long
exploration has to fill up before it can be trimmed.

## 13. `browse` returns a fetch-after-load page's loading state — open

The test page fills itself from the GitHub API after load. `browse` returned
"reading github.com…" and "build: unknown" — the pre-fetch DOM — while a `shot` of the same
URL showed the loaded repository data. `wait` has `dom`/`load`/`idle`/`done`, none of which is
network-idle. `browse {eval: "…"}` can force a wait and did return the loaded state.

---

## Confirmed healthy in this round (not bugs)

- Selectors: ambiguous `original` (named the count and the fix), out-of-range lines
  ("the file has 5 lines"), `char_from` past the end ("the file has 18 characters").
- `read {info:true}` on a PNG returns kind/bytes/sha256; `look` rejects a `.md` by naming the
  formats it takes; `read {paths:[…]}` batches with headers.
- `move` onto an existing file refuses; `delete` on `agent/` now says the right thing
  ("agent tools cannot change it directly") — old bug 12 fixed.
- `shared/` write refusal names the owner exactly.
- `serve`: custom routes, SPA fallback, custom headers, byte ranges (`206` + `Content-Range`),
  capture of POST bodies, `stop` per server and `*`, `list` with captured counts.
- `query`: `ATTACH` refused, one statement per call, read-write namespaces with
  `INSERT … RETURNING`, `direct` + `name` writes a CSV, `roll_hour` spend.
- `list`: `total`, cursor paging (`next_cursor`, `complete`), `not:true`, `history`.
- `background`: `list` with states, and a failing job keeps status `1` in its file.
- `archive`: zip, tar.gz, single-file gz, seal/unseal, wrong-key auth failure.
- `diff` between two revisions; `restore {keep:true}` on a deleted path; local `tag create`.
- `mcp`: listing, invocation, `format:json`; `app_rpc` template; `ssh`/`sftp` refusals name
  the secret type and binding they need.
