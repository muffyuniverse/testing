# Upgrade report — regression pass over the boundary report, plus the new tools

Written by the agent in project `testing`, branch `main`, 2026-11-15.
Everything below was run and observed in this session, not inferred. Where I did not
retest something I say so. The previous round's findings are in
[boundary-report.md](boundary-report.md); this file answers "what changed?".

---

## 1. Bugs from the boundary report: fixed

| # | old bug | now |
|---|---|---|
| 2 | `.gitignore` not honoured | **fixed.** Appended `probe/ignored.txt`, wrote the file, made a bare `commit`: it listed 17 changed paths and the ignored file was not one of them. `list` also skips it. |
| 3 | `list {total:true}` ignored | **fixed.** Returns `count:0, total:29, items:[]` — a count, not paths. |
| 4 | `browse` success on failure | **fixed.** `https://nonexistent-probe-9f3k2.invalid/page` → status 1, `net::ERR_NAME_NOT_RESOLVED`. |
| 5 | `shot` blank page | **fixed.** First capture of `https://example.com` rendered its full multilingual content; confirmed with `look`. |
| 6 | SQL ran only the first statement | **fixed, and refused cleanly:** "Only one SQL statement may be run per query; nothing ran." |
| 8 | `diff` called small text files "binary" | **fixed.** A 1-line→1-line change renders `+1 −1` with the file at `agent/git/`. |
| 9 | sealed archive could not be opened | **fixed for the important part.** `seal:false` + `key` opens it; a wrong key fails as "Authentication failed: this key does not open it, or the sealed file was altered." See the naming caveat in §2. |
| 11 | cannot seal a folder, unhelpful error | **fixed as a message:** "`app` is a folder. `archive` it first, then seal the zip — sealing a folder would have to invent a file." |
| 15 | `mcp` listing and invocation disagreed | **fixed for the flat form only.** `mcp/deepwiki.jsonc` listed and `read_wiki_structure` invoked; a `{"servers": {…}}` wrapper is still listed as configured and then fails with "has no `url` field" (bug-list #18). |
| 16 | `mcp` errors echoed raw remote body | **improved, not fixed.** MCP-level failures are clean ("Repository not found…", "MCP error: Unknown tool: …"), but an HTTP-level failure still pastes the remote body into the context — a 405 from example.com put its page HTML there (bug-list #17). |
| 17 | a one-line file could not be paged | **fixed.** `read {char_from, char_count}` pulled 300 characters out of a 769 kB single-line JSON. |

Not retested, so still **unknown**: 1 (`push` claiming success without a commit — I did not
push, see §5), 12 (wrong refusal text for `agent/` writes), 13 (`http` private-address text).

## 2. Bugs that are still there

1. **`json` extraction cannot read an `http` step's result.** In a `pipe`, a normal `http`
   step's body is the human summary (`GET … → 200` plus prose), so
   `{"step":"s1","json":"rate.limit"}` falls back to `default` while
   `{"step":"s1","regex":"\"limit\":([0-9]+)","group":1}` on the same step returns `5000`.
   **Workaround:** give the http step `direct:true`; then `json` works (`5000`).
   `json` itself is healthy — it resolved `total` (29), `count`, `complete`, `truncated`
   and `paths.0` from a `list {format:json}` step, numbers and booleans included.
2. **A sealed archive cannot be unsealed while its source still exists.** Unsealing
   `probe.zip.enc` derives the output name `probe.zip`, which is the file that was sealed,
   and fails with "The output probe.zip already exists; it was not overwritten. Choose a
   different `name`" — but `unseal` has no `name` argument, so the advice is a dead end.
   Deleting the source first makes the round-trip work. A suffix such as
   `probe.unsealed.zip` would fix it.
3. **Unpacking into an existing folder still forks it** (old bug 10): `unpack probe.zip`
   with `probe/` present produced `probe 2/`. No destination argument exists to say
   "merge here" or "overwrite".
4. **`http` malformed documents still mangle the input** (old bug 14):
   `this is not a request` → `"THIS" is not a method this app can send`.
5. **Bare `PRAGMA` is still refused** (old bug 7), now with a clearer text that still
   describes a read as write access: "the person's database is read-only here". The
   `SELECT * FROM pragma_table_info('agent_rows')` route works.
6. **`forget` still cannot be used early** (old danger 8). At ~46k tokens it answers
   "under the 100k clear-up point, so nothing needs to go".
7. **`browse` reports a single-page app's loading state as its content.** The served test
   page fetches GitHub after load; `browse` returned "reading github.com…" / "build:
   unknown", while a screenshot of the same URL showed the loaded repository data. `wait`
   defaults to `done`, which is not network-idle. Quote `browse` text for SPAs only after
   checking a `shot`.

## 3. The new tools, exercised

- **`pipe` — the toolchain.** Verified: steps with `id`, a later step consuming an earlier
  step's body (`{"step":"s1"}`), `{"template": …, "vars": {…}}` with `json` dot-paths,
  `regex` + `group`, `trim` and `default`; `allow_failure:true` on a step; `stop` inside a
  pipe with `{"step_status":"s1"}` + `return` (the following step's write really did not
  happen — checked on disk); `stop` at top level with `sequence:1` (skipped exactly one
  call, marked `interrupted: skipped by stop`, then ran the next). Intermediate results
  never entered the conversation; only the final step's body did.
- **`serve`** — static root, one custom `GET /api/ping` route (returned exactly
  `{"pong":true,"from":"route"}`), unmatched path → clean `404` with headers,
  `capture:true` logging every request to `agent/serve/<server>/requests.jsonl` including
  a `POST` body, and `stop` per server.
- **`shot`** — PNG of the served page, and a 3000 ms / 8 fps **MP4** of it. `look` read
  both: the still showed the loaded page, the video came back as a contact sheet with
  timestamps (00.000–02.552 s).
- **`app_rpc`** — `list` correctly reports no approved apps; `template` generated a
  `PechkaDebugProvider` + manifest snippet for `com.example.myapp` under `agent/rpc/`.
  A live call is **untested** (no debug APK installed, no app approved).
- **`archive`** — pack folder, seal single file, unseal, wrong-key auth failure, the
  folder-seal refusal. Not tested: `tar.*`, `xz`.
- **`query`** — a read-write SQLite namespace (`db:"probe"`: `CREATE`, `INSERT … RETURNING`
  returning `id,name / 1,gamma / 2,delta`), and the request-log rollups
  (`roll_hour`: 406 requests, 0.9525 USD, deepseek-v4.1-flash-1017).
- **`background`** — a job ran beside the conversation and its file was readable.
- **`list {history}`** — saved versions per path with commit messages.
- **`tag`** — `list` shows 6 local tags.
- **`ssh` / `sftp`** — only the refusal path: a missing secret answers with the real names
  available (`github-muffyuniverse`, `openrouter`) and the binding rule. No credentials for
  any SSH host exist here, so **no transfer was tested**.
- **`mcp`** — tool listing with schemas, and one invocation.

## 4. What this means for working here

- A probe folder is not a safe scratchpad: it got committed by a bare `commit` (that was
  the point of the test). `.gitignore` now works, so put throwaway files there first.
- Use `direct:true` whenever an `http` result must be parsed inside a `pipe`.
- `char_from/char_count` makes big single-line JSON readable — no more unreadable files.
- Serve + shot + look is a genuine preview loop for anything built in `app/`.

## 5. Not done on purpose

- **No `push`.** Commit `2da0c51` and its probe paths exist only on this phone; the probe
  files are deleted in the follow-up commit, so history still records the probe round.
  Sending is the person's call.
- **`ssh`/`sftp` transfers, `app_rpc` live calls, push conflicts, whole-project `restore`,
  `serve` tunnels** — each needs a credential, an installed app, a remote commit, or is too
  destructive to try unasked.
