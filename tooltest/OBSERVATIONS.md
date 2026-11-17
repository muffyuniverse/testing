# Tool testing observations

Session log of systematic tool/argument testing in project `testing`, branch `main`.
Each section: what was tried, expected, observed. Fixtures live in `tooltest/data` and
`tooltest/w`; everything here is disposable.

Environment at start: branch `main`, remote set, token set, browser available
(com.google.android.webview 157.0.8103.38), `http`/`mcp`/`ssh`/`sftp`/`app_rpc` enabled,
foreground limit 180 s, background limit unlimited, pipe steps 64, search depth 8.

---

## 1. write

| call | result |
|---|---|
| `write` new path (creates parent folders) | 0 — "made <path> (lines 1-N, N lines total)" |
| `write` with `append:true` | 0 — appends at EOF, no separator inserted, "changed (lines 2-2, ...)" |
| `write` with `original` | 0 — exact single-match replacement |
| `write` with `edits:[{original,text},...]` | 0 — both replacements applied in one atomic call |
| `write` with `grep` | 0 — every match replaced on every line |
| `write` with `from`/`to` | 0 — whole lines replaced by `text` |
| `write` with `from` only (to omitted) | 0 — replaces from that line to EOF |
| `write` whole-file `text` on a nonempty file | **1** — refused: "Replacing a nonempty file needs `overwrite:true`" |
| same with `overwrite:true` | 0 — replaced wholesale |
| `original` text not present | **1** — "Nothing in <path> says that, so there is nothing to replace." |
| `if_sha256` with a wrong digest | **1** — "The file changed since its SHA-256 was measured; nothing was changed." |
| `grep` with `not:true` | 0 — replaces the content of each **non**matching line |
| `grep` + `multi:true` (pattern spans lines) | 0 — the whole matched span replaced by `text` |
| `grep: ".*"` with `text: ""` | 0 — line contents emptied (file kept, still "2 lines total") |

Notes:
- The "lines" counter in the answer counts the trailing empty segment after the final
  newline, so a 4-line file with a trailing newline reports "5 lines total".
- A write with no selector at all is refused on a nonempty file even if `text` is `""`.
- Nothing is written when a guard (`if_sha256`, missing `original`) fails: verified.

## 2. read

| selector | observed |
|---|---|
| whole file | 0 — raw text, no line numbers |
| `from`/`to` + `numbers:true` | 0 — `3: Gamma Up` style, inclusive bounds |
| `from:-3` | 0 — last 3 lines (negative counts from the end) |
| `tail:3` | 0 — last 3 lines |
| `grep` | 0 — `1: alpha` / `8: ALPHA again`; case-insensitive by default |
| `grep` + `case_sensitive:true` | 0 — only the lowercase hit |
| `grep` + `context:1` | 0 — match plus one line either side, blank context lines shown |
| `grep` + `not:true` | 0 — nonmatching lines only |
| `grep` + `literal:true` | 0 — `NEEDLE` matched literally |
| `grep` + `word:true` | 0 — whole-word only |
| `grep` + `multi:true` | 0 — reports a whole range `1-10:` when the pattern spans lines |
| `grep` with no match | **1** — "nothing in <path> matches that selection" |
| `char_from:5, char_count:12` | 0 — 12 characters starting at character 5 |
| `char_from` past EOF | **1** — "nothing from character 500: the file has 93 characters" |
| `char_from:1, char_count:20000` | **1** — count is capped at 16000 |
| `paths:[a,b]` | 0 — `=== path ===` header before each body |
| `info:true` | 0 — JSON `{path, bytes, kind, sha256}` |
| `text:"..."` + `from:2` | 0 — reads supplied text instead of a file |
| binary file (zip) | **1** — "could not be read (not a text file)" |
| missing file | **1** — "There is no file at <path>" |
| `revision:b320aa1` | 0 — the committed version of the file |

Field projection (`delimiter` / `fields` / `quote` / `out_delimiter` / `trim_fields`):
- `fields:[2,3]` on CSV → `name,score` etc. Works, keeps the header row.
- `fields:[1,-1]` + `out_delimiter:" | "` → negative indices count from the end.
- `quote:""` disables CSV quoting, so `"Bob, Jr."` splits into `"Bob` — confirms quoting is on by default.
- `split_regex:"\\s+"` + `fields:[1,2]` on a file whose first line has only one field →
  **1** "Row at line 1 has 1 field(s), so field 2 is out of range." One short row aborts the
  whole read; there is no per-row fallback.

## 3. list

| argument | observed |
|---|---|
| `path` regex | case-insensitive regex on the path; `^~/tooltest/data/` also works |
| `format:text` | `~/path` rows, one per line |
| `kind:directory` | only folders |
| `kind:text` | only UTF-8 text files |
| `grep` | `~/path:line: text`, one row per path with further matches counted |
| `grep` + `not:true` | files with no matching line |
| `grep` + `literal:true` / `word:true` | both honoured |
| `count:5` + `next_cursor` | paging works; the cursor is opaque base64 and survives to the next page |
| invalid `cursor` | **1** with `matched:null`, `complete:false`, error text |
| `total:true` | returns `count:0`, `items:[]` and just the `total` number — it is a count-only mode |
| `depth:1` | only the folder itself |
| `root:"tooltest/w"` | searches inside that folder |
| `all:true` | searches the whole workspace (`~/mcp/` found) |
| `ignored:true` | includes gitignored paths (archives, sealed files) |
| `history:true` | adds `path@sha7` rows with an excerpt; current paths appear separately |
| default cap | "stopped at 10 paths; narrow `path`, lower `depth`, or use `format:json`" |

## 4. move / delete

- `move` to a new full path → rename. `into` an existing folder (trailing `/`) → keeps the name.
- `copy:true` leaves the source and works for a whole folder too (`tooltest/w` → `tooltest/wcopy`).
- Destination exists → **1** "There is already a \"alpha.txt\" there".
- Missing source → **1** "There is nothing at <path>".
- `delete` file → 0. `delete` folder → recursive, 0. `delete` missing → **1**.

## 5. archive

- `pack` zip and tar.gz both work; the answer lists the members.
- Packed archives land in the project root, **not** next to the source.
- `name` must be a simple filename (letters, digits, dots, hyphens, underscores, ≤160):
  `tooltest/unpacked-zip` is refused even for `unpack`.
- `unpack:true` with no `name` derives the output folder from the archive (`data.tar.gz` → `data/`).
- Packing and sealing **automatically appended the output names to `.gitignore`**
  (`data.zip`, `data.tar.gz`, `secret-*.enc`). The unsealed `plain-*.txt` outputs were not ignored.
- `seal:true` with `aes-256-gcm` (default) and `chacha20-poly1305` → 80-byte files for a 19-byte payload.
- `openssl-aes-256-cbc` (48 bytes) and `legacy` (76 bytes) both seal and open, each with a
  "does not authenticate" warning.
- `seal:false` with the right key → opens to the given filename; with a wrong key →
  **1** "Authentication failed: this key does not open it, or the sealed file was altered."
- `seal:false` on a path not ending in `.enc` → **1** refused before any key check.
- Existing destinations are refused for both pack output names and unsealed output names.

## 6. graph

- `bar` from a CSV `path`, `x:"id"`, `y:["score"]` → `agent/plots/scores-bar.svg` + Markdown tag.
- `line` with two series from inline `data` and both axis labels → fine.
- `scatter` and `area` with default `x`/`y` → fine.
- Non-numeric cell → **1** "Line 2 column 1 (`a`) is not a finite number."

## 7. git tools

- `status` lists Staged / Unstaged / Untracked / Conflicted and a "Git state: SAFE" line.
- `log` newest first; `log path` narrows to a path and prints its versions.
- `diff` with no args = last commit vs working tree, including untracked additions.
- `diff from:<sha>` narrows to one path; "nothing changed in ... against <sha>" when clean.
- `commit paths:["tooltest"]` committed 20 paths and left the `.gitignore` edit alone.
- `tag list` shows local tags with their commits; `tag create` makes a local tag only.

## 8. query

- `SELECT COUNT(*), SUM(size) ... FROM agent_rows` works with no WHERE (own conversation only).
- `direct:true` returns CSV inline instead of writing `agent/sql/query-*.csv`.

---

## 9. query — SQLite namespaces

- `db:"t1"` creates `agent/sql/db/t1.sqlite` on first write; the answer is
  `agent/sql/db/t1.sqlite: processed N row(s), N affected (last_insert_rowid=...)`.
- Bulk binding with `?` / `CAST(? AS INTEGER)` + `data` + `fields:[1,2]` works.
- `path:` imports a file directly (no pipe size cap): `tooltest/data/beta.csv` with
  `header:true`, `delimiter:","`, `quote:"\""` and `fields:[2,3]` imported 3 rows and kept
  `"Bob, Jr."` as one field.
- `trim:true`, `comment:"#"`, `null_if:["NULL"]`, `empty_as_null:true` all behave as documented
  (verified `msg IS NULL` = 1 for the `NULL` row).
- A row error rolls back the **whole** transaction, including the `schema` DDL: a NOT NULL
  violation on line 2 left `strict_t` non-existent afterwards.
- `UPDATE ... RETURNING` works; `DELETE` answers `affected,last_insert_rowid`.
- `ATTACH` is refused: "`ATTACH` is not permitted in workspace databases."
- `db` accepts a bare namespace or a project-relative `.db/.sqlite/.sqlite3` path;
  `db:"tooltest/data/beta.csv"` is refused with that rule spelled out.
- Reserved-word slip (`AS isnull`) surfaces the raw SQLite error and code.

## 10. serve

- `start` picks a port when `port:0` and answers the URL, root, route count and capture log path.
- Routes must be keyed by plain paths: `"GET /api/ping"`, `"POST /api/echo"`, `"/api/*"`.
  A key written with literal quotes (`"\"/api/ping\""`) silently never matches — 404.
- `GET /api/ping` → 200 `pong`; `POST /api/echo` → 201 with the custom `X-Test: yes` header;
  `/api/*` wildcard answered; `/` served `index.html` from the root without a `fallback`.
- Unmatched write methods answer **204** while `capture:true`.
- Static files get `Accept-Ranges: bytes`, `Content-Length`, `ETag`, `Content-Type` with charset.
- `capture` writes JSONL per request under `agent/serve/<server>/requests.jsonl`; the `cookie`
  header is stored as `"***"`. `clear_requests` empties it, `list` shows the running server and
  its request count, `stop` reports how many servers it stopped.
- Server URLs are reachable from `http`, `browse`, `shot` and `mcp`.

## 11. http

- Document format `METHOD url` / headers / blank line / body works; a missing blank line or a
  bare `GET not-a-url` fails with "Only http and https can be requested".
- `{{secret:github-muffyuniverse}}` was expanded for `api.github.com` and refused elsewhere by
  design; the GitHub call returned the authenticated user JSON.
- `response_headers:true` prefixes `HTTP <code>` and every header, then the body.
- Non-2xx still returns status **1** with the body (404 from the local server).
- `@tooltest/ping.http` runs a saved request file and returned `pong`.

## 12. browse / shot / look

- `browse` one-shot read answers the page title, readable text and the path of the saved page text.
- `session:"s1"` keeps a live tab; `output:"snapshot"` adds compact `[e1] button "Click me"` /
  `[e2] input[text]` refs plus content.
- `click` on `e1`, `type` into `e2`, `scroll` (`bottom` → "Scrolled (0px)" because the page fits),
  `press` (`Enter` → "Dispatched synthetic Enter; browser default actions are not guaranteed")
  all work and echo the page afterwards.
- `wait_for` with `target:"text:clicked"` returns when the text appears.
- `eval` is an argument, **not** an action: `action:"eval"` is refused with the list of actions.
  Use `action:"read"` (or snapshot) + `eval` + `output:"eval"`.
- `eval` code is evaluated as an expression: a top-level `return` throws
  "SyntaxError: Illegal return statement"; `({title: ...})` and an async IIFE both work.
  A thrown error is echoed into the page's console buffer and shown on later reads.
- `tabs` lists `[s2] http://... (webview)`; `close` ends the session.
- `open` is refused outside Firefox: "`open` requires Firefox, a URL and no session".
- Bad host → **1** "The page could not be loaded: net::ERR_NAME_NOT_RESOLVED".
- `shot` writes PNG under `agent/browser/`; with `name:"page-full"` the file has **no extension**
  (still detected as an image by `read info` and `look`), `full:true` captured 768×1365.
- `shot` with `session` and no `url` photographs the live tab without reloading.
- `duration_ms:2000, fps:5` recorded `agent/browser/clip.mp4` (76 KB) and the `eval` ran at
  recording start (repeated clicks visible in the frames).
- `look` on the mp4 without `at_ms` returned a 3-frame contact sheet stamped `00:00.000`,
  `00:01.056`, `00:02.112`; with `at_ms:1000` it returned one full-size frame.
- `look` attaches the picture to the **next** message, so the image arrives one turn later.

## 13. mcp

- `mcp` with no args lists the configured servers (`mcp/*.jsonc`): cloudflare-docs, context7,
  deepwiki, exa, exa-advanced, huggingface, mslearn.
- `server:"context7"` lists tools with full descriptions and JSON schemas.
- `server:"context7", tool:"resolve-library-id"` returned real results and saved them to
  `agent/mcp/context7-resolve-library-id-*.txt`.
- Unknown server → **1** with the list of available names.

## 14. app_rpc / ssh / sftp

- `app_rpc list` shows one on-device app (`ai.pechka.factory`) with its authority and cert SHA-256.
- `methods` on it → **1** "not approved for `app_rpc`. Install its debug build and switch it on in
  Settings under On-device app debugging."
- `ssh exec` and `sftp ls` without a vault-bound credential → **1** "SSH needs `key` ... or
  `password` ... bound to `example.com`." No SSH/SFTP host is configured in this project, so
  those tools cannot be exercised further without the person adding a secret.

## 15. pipe

- Source forms all verified: `{"step":"s1"}`, `{"step_status":"s1"}`, `{"step_error":"s1"}`,
  `{"toolcall_body":N}`, `{"file":"path"}`, `{"row":N}`, `{"tool":"read","args":{...}}`,
  `{"template":"id={{n}}","vars":{...}}`, plus `json`, `regex`+`group`, `trim`, `default`.
- A `row` source reads that conversation row's body — row 6 (the `env` GOT) yielded `branch  main`
  through `regex`; a CALL row's body is its `{}` arguments.
- `json:"items.1.name"` on a JSON file gave `two`; `regex:"ALPHA (\\w+)"` + `group:1` gave `again`;
  `default` covered a miss; `template` merged two vars (`id=1 ok=true`).
- Unhandled failure halts the pipe: "step 1 (`bad`): There is no file at ...".
- `allow_failure:true` + `{"step_error":"1"}` wrote the error text; `when:{status:{"step_status":"bad"},
  equals:1}` branched on it. `when:{status:{"step":"bad"}}` is refused: "Step `bad` failed; its body
  cannot be piped."
- `when:{value:{step:"a"}, matches:"^alpha"}` ran one step and skipped the other; the skipped step
  leaves no file.
- `stop` with `{value, matches, return}` exited the pipe with that text; with `sequence:1` it skipped
  exactly the next call and let the one after run; `sequence:0` (default) skipped the rest.
- A step **cannot** be a bare `{steps:[...]}` object; nest with `{"tool":"pipe","args":{"steps":[...]}}`,
  which works and returns only the inner final result.
- Top-level `stop` (outside pipe) with `{"toolcall_status":1}` answered "skipping all remaining calls"
  and the skipped call came back "interrupted: skipped by stop".

## 16. background

- `background` with `tool:"pipe"` and steps answers `agent/background_results/<name>` immediately;
  the file starts as `running` and later holds the `0`/`1` status line plus body (`0\nbeta`).
- `action:list` returns JSON records with `state:"finished"`, `started_at`, `finished_at`, `status`.

## 17. paths outside the branch

- `list all:true` and `read ../shared/onboarding/00-index.md` work.
- Writing `../shared/user/...` is refused: "is in the shared folder, which belongs to the person
  and to every project."
- `agent/` writes are allowed and are not committed.

---

## 18. read/query size limits

- A `read` whose selection would be about 10.5k tokens is **not** performed; the answer is
  status **0** with "That read is about 10514 tokens, above the 6000 limit. ... read smaller
  character slices". No file is created; the original is untouched.
- `char_count` is capped at 16000 per call, so a large file can be stitched together with a
  `pipe` of char slices + `append` writes (verified: 36 KB rebuilt byte-for-byte, same SHA-256).
- `query` CSV export caps at **1000 rows**: a 20000-row recursive CTE wrote a 1002-line file and
  the answer said "1000 rows of 20000 (capped at 1000)".
- `direct:true` together with `name` both returns the text inline and files it
  (`read` → `agent/background_results/`, `query` → `agent/sql/`).

## 19. archive: single-file formats

- `gz`, `bz2` and `xz` pack a single file (`alpha.txt` 93 B → 100 / 120 / 152 B) and unpack to the
  given `name` (no extension needed); all three round-trip to identical content.
- `unpack` of a plain `.gz` names the output exactly as given, not `alpha.txt`.

## 20. restore

- `restore path` + `revision` writes that version back.
- Observed: **both** `keep:false` (default) and `keep:true` leave the change **staged** and create
  no commit; `log` never grew a restore entry and `status` showed `M <path>` under Staged in both
  cases. The documented difference ("keep leaves the restored text staged rather than committed")
  was not observable. Filed as an advisory bug report.
- Restoring content identical to HEAD leaves a clean tree in both modes.

## 21. protected paths

- `delete tooltest/w/.gitkeep` → **1** "is an app-managed folder marker and cannot be changed."
  (`.gitkeep` files are created automatically for new folders and cannot be removed.)
- `write agent/project-prompt/probe.md` → **1** "agent/project-prompt/ is read-only for agents;
  only the person can edit project instructions." `list root:agent/project-prompt` → nothing listed.
- `write ../shared/user/...` → **1**, shared folder belongs to the person.

## 22. git remote

- `push` → "Pushed main to main at fc196d9." (5 local commits sent, no force).
- `tag push v0.0.1-tooltest` → "Sent tag `v0.0.1-tooltest`."; `tag list remote:true` then showed it.
- `pull` after push → "Already up to date".

## 23. notify / report_bug

- `notify` with a title, message and two links (one branch path, one `http://127.0.0.1` URL) →
  "Notification submitted; Android controls its display." and echoed both links.
- `report_bug` → advisory id returned and a line pointing at
  `Download/Pechka agent reports/agent-report-*.log.txt`; it is outside the workspace.

## 24. browser session state leaks between calls

- A `setInterval` started through `shot`'s `eval` (recording a 2 s video) **kept running in the
  live session** afterwards: a later read reported "clicked 990 time(s)".
  A session is a real live tab — script side effects persist until `close`.
- `scroll` with a pixel amount (`200`) on a page shorter than the viewport reports
  "Scrolled (0px)" — it does not error.
- `serve stop` with `server:"*"` stopped every server in the branch.

## 25. summary of refusals seen (all with clear messages)

`write` nonempty without `overwrite`; `original` not present; wrong `if_sha256`; `read` missing
file, binary file, past EOF, `char_count` > 16000; `list` bad cursor; `move` to an existing
destination or from a missing source; `delete` missing path and app-managed `.gitkeep`;
`archive` bad `name` shape, existing destination, non-`.enc` unseal, wrong key; `graph`
non-numeric cell; `http` non-http URL; `browse` `action:"eval"`, `open` outside Firefox, bad host;
`mcp` unknown server; `app_rpc` unapproved app; `ssh`/`sftp` without a vault-bound credential;
`query` `ATTACH`, bad `db` shape, SQL errors; `write` into `shared/` or `agent/project-prompt/`;
`pipe` step with a bare `steps` key, `when` on a failed step's body, `stop` with `value` and no
`matches`.
