# Stress, limits, gaps and ambiguities

Second phase of testing: deliberate overload, adversarial inputs, race conditions and
boundary probing. Companion to `OBSERVATIONS.md` (which records ordinary behaviour).

Format: what was attempted → what happened → what guardrail (if any) exists or is missing.

## S1. Size and step budgets

- `write` with `text:{"file":...}` from a **404,734-byte** file → **1**
  "The file ... is too large for expansion; read it in ranges instead."
  The same source at **367,940 bytes** copied fine. Boundary lies between those two, consistent
  with `source_chars = 400000` from `env limits`.
- `pipe` with 65 entries → **1** "`steps` has 65 entries, and 64 is the limit." (static check)
- Two nested `pipe` steps of 33 steps each (66 executions, only 2 outer entries) → **1**
  "`pipe` exceeded the maximum of 64 step executions." So the budget is **global across nesting**;
  nesting does not buy more work per turn. (runtime check)
- Nesting depth 6 (pipe inside pipe … ) works and returns the innermost body.
- Trap: a nested `pipe` step's `id` must sit **beside** `args`, not inside it. Putting it inside
  `args` gives "`pipe` does not take `id`" — a message that blames the pipe tool rather than
  pointing at the misplaced key.
- `write` to `tooltest/stress/.gitkeep` → **1** "is an app-managed folder marker and cannot be
  changed"; the folder marker is created automatically when the folder is first made.

## S2. Paths, names, traversal

Accepted and normalised: `tooltest/../tooltest/...`, a leading `/tooltest/...`, `../testing/...`,
`~/tooltest/...`, `./tooltest/...`. `%20` stays literal (no URL decoding).

Refused (good guardrails, all with specific messages):
- `tooltest/stress/../../shared/user/x.txt` and `../shared/onboarding/x.txt` →
  "is in the shared folder, which belongs to the person and to every project."
- `tooltest//stress//x.txt` → "That path has an empty name in it".
- names containing `"`, `:`, `|`, control characters, a trailing full stop, or a Windows reserved
  device name (`CON.txt`) → refused with a Windows-portability reason.
- writing to an existing folder path, reading a folder.

Surprises:
- **A trailing space is silently trimmed**: `tooltest/stress/trailing space ` created
  `tooltest/stress/trailing space`. A path that genuinely ends in a space is therefore
  unreachable, and the caller is never told.
- **Backslash is treated as a separator**: `tooltest/stress/back\slash.txt` created the folder
  `back/` and the file `slash.txt`. On Android a backslash is a legal filename character, so this
  is a silent rewrite, not an error.
- **Case-sensitive duplicates are allowed**: `ToolTest/Data/ALPHA.txt` was created as a second
  path next to `tooltest/data/alpha.txt`. On a case-insensitive checkout (Windows/macOS) those
  collide. Nothing warns about it.
- **A refused write can still create folders.** Writing `.../name with spaces and 'quotes' and
  "dq".txt` was refused ("A name cannot contain \"") but left an empty directory
  `tooltest/stress/name with spaces and 'quotes' and/` behind — the prefix before the quote. The
  quote appears to terminate the path during parsing, and the parent is made before validation.
- A 262-character filename → "That path could not be read": accurate outcome, unhelpful message
  (nothing says the name is too long).
- Unicode/emoji names work (`файл-😀.txt`); hidden dotfiles are listed by default.

## S3. Depth limit hides files from search

- `write` happily created a file at 64 path components and another at 123 components; `read` by
  exact path works (`deep`, `deeper`).
- `list` **cannot see them**: `depth` is capped at 16, and `list` reports `matched:false` for
  `path:"file\.txt$"` with `depth:16`. Files deeper than 16 components are invisible to `list`
  and therefore to `list grep` — content search cannot find them at all.
- `depth` is measured **from the branch root when `path` is a regex**, not from the matched
  prefix: `path:"^tooltest/stress/deep3/" depth:16` returned exactly the directories down to
  `tooltest/stress/deep3/l01/.../l13/` (16 components including `tooltest`). With `root:` set,
  depth counts below that root (`root:"tooltest/stress/deep" depth:3` → three levels).
  This is easy to get wrong: the documented "folder levels below the search root" only matches
  the `root` form.
- `depth:17` and `depth:64` are accepted without complaint and silently clamped to 16.
- Guardrail gap: either refuse to create paths deeper than the search can see, or let search go
  deeper than 16.

## S4. Regex backtracking is an unbounded resource hole

File: one line of N `a`s followed by `b`. Pattern `(a+)+$` never matches, so the engine
backtracks exponentially. Timings measured from `background action:list` (`started_at`/`finished_at`):

| line length | steps | wall time |
|---|---|---|
| 25 | 2^25 ≈ 33 M | **2,346 ms** |
| 30 | 2^30 ≈ 1 G | **88,322 ms** |

- Each extra `a` doubles the time, as expected. A 40-character line would take ~25 hours.
- **There is no regex timeout.** `read`, `list` and `write` all take a caller-supplied regex and
  run it against arbitrary file content, so a single request can burn a core indefinitely.
- **A background job has no time limit** (`background_ms: 0` in `env limits`), so the same request
  started in the background keeps running after the conversation moves on.
- `background action:cancel` is best-effort and **does not interrupt a running regex**: the job
  still reported `state:"running"` 17 s after the first cancel and again after a second request.
  (A cancel issued for a job that had just finished answers "That job is not cancellable or not
  running in this session; its outcome may be unknown." — so the error does not distinguish
  "already done" from "cannot be stopped".)
- Foreground calls are bounded by the operator limit (180 s), but that only disconnects the
  caller; the work continues in the background.

Guardrails that would help: cap regex execution time per call (e.g. 2 s), refuse nested-quantifier
patterns, or run matching on a bounded input prefix. At minimum, `cancel` should be able to stop a
job that is stuck in pure computation, and `background action:list` should show elapsed time so a
stuck job is obvious.

## S5. Concurrency: what is safe and what is lost

Two background `pipe` jobs started in the same message run concurrently. Three experiments:

| experiment | outcome |
|---|---|
| both jobs create the same file with `text` (no `overwrite`) | one wins; the other fails at step 1 with "Replacing a nonempty file needs `overwrite:true`" and **its whole batch is lost** |
| both jobs append 10 lines each to an existing file | **no loss**: 20 lines, interleaved at call granularity (`A1 A2 B1 B2 A3 …`) |
| both jobs apply 5 unique `original` replacements each | **no loss**: all 10 replacements present |

- So per-path writes are serialised (as the `write` docs say) and appends do not clobber.
- The dangerous case is *create*: a concurrent creator gets a hard error, not a merge, and the
  failure arrives only in the background result file — nothing surfaces in the conversation.
- Trap found while building the test: `original` matches **substrings**, not lines. Replacing
  `a1` in a file containing `a1` and `a10` is refused ("That text appears 2 times"). Line anchors
  are the caller's job.

## S6. Tool arguments are literal text — escapes are not decoded

- `write text:"x\ty"` produced a **4-byte** file `x\ty` (backslash + t), not a tab.
  `x\ny` → 4 bytes, `x\\ty` → 5, `x\\\\y` → 6. Nothing is unescaped.
- `read delimiter:"\t"` on a real TSV → "Row at line 1 has 1 field(s)" (no split);
  passing an **actual tab character** works and returns the column.
- `read grep:"\d+"` matched digits, while `grep:"\\d+"` matched nothing — confirming that one
  backslash is one backslash.
- Consequence: regexes must be written with single backslashes, and control characters must be
  typed literally. JSON-style `\n`, `\t`, `\u0000` silently become two-to-six literal characters,
  which is invisible unless you check byte counts.
- (`graph` with `delimiter:"\t"` did parse the TSV, so that one tool appears to unescape on its
  own — an inconsistency worth knowing.)

## S7. BOM and encoding

- A UTF-8 BOM written into a file is **kept**. `read` shows it as part of line 1, and
  `grep:"^first"` does **not** match a file whose first line is `\uFEFFfirst`; `grep:"^﻿first"`
  does. So anchored patterns silently fail on BOM-prefixed files (typical of Excel CSV exports).
- `query` import is positional, so a BOM header only matters for `header:true` skipping; importing
  `bom.csv` with `header:true` added exactly the 2 data rows and nothing odd.
- No NUL-byte or invalid-UTF-8 file could be produced through `write` because escapes are literal
  (S6); `nul.txt` was refused earlier as a Windows device name.

## S8. Git edge cases

| call | result |
|---|---|
| `commit` with a path that does not exist | **1** "There is no selected file or changed path at ...; nothing was committed." |
| `commit` with an empty `message` | **1** "Give `message` describing the local commit." |
| `diff from:"deadbeef"` | **1** "There is no version deadbeef" |
| `restore revision:"deadbeef"` | **1** — but the message is about uncommitted changes, not the bad revision |
| `restore` whole project with uncommitted changes | **1** "This project has 2 change(s) that are in no version, and putting the whole tree back would destroy them. `push` first, or name one `path`" — a real guardrail (it did **not** protect unpushed *commits* earlier, see OBSERVATIONS §27) |
| `tag create` with an existing name | **1** "There is already a local tag ...; it was not moved." |
| `tag create "bad name!"` | **1** "That is not a valid Git tag name." |
| `tag push` a tag that does not exist | **1** "There is no local tag called ..." |
| `log count:0` and `count:-1` | both return 1 version (0/negative fall back to 1, no error) |
| `commit paths:["dist/bundle.js"]` (gitignored) | **1** "`dist/bundle.js` is ignored and untracked; nothing was committed." |
| `diff` whose result is ~8,184 tokens | written whole to `agent/background_results/diff-<random>.txt` with instructions — oversized results are handled rather than truncated |

- Ignored paths are invisible in `status` (not even under Untracked) but `write` still creates them.
- `.gitkeep` markers created by the app stay **untracked** until something commits them.
- **Bug (filed):** the app silently appended two harmless paths to `.gitignore`
  (`tooltest/stress/nulbyte.txt`, `nulbyte2.txt`), which then refused to commit. Not reproducible
  with the same names in other folders or with `nulx`, `null`, `console`, `conifer`, `com10`.

## S9. serve / http hardening

- Static root `.` does **not** expose app-owned files: `/.git/config` and `/.pechka/` → 404.
- Traversal is contained: `/tooltest/../../shared/onboarding/00-index.md`, `/../../../../etc/passwd`
  and `/%2e%2e/%2e%2e/etc/passwd` → 404. (`/../.gitignore` returns 200 only because it normalises
  back inside the root.)
- `Range: bytes=0-9` → **206** with `Content-Range: bytes 0-9/93`; `HEAD` → 200 with headers and
  no body; a query string is ignored for static files.
- Route options verified: `file` (served with the right `Content-Type`, e.g. `image/svg+xml` for
  `.svg`), `status` 204 (no body) and 500 (body delivered, `http` reports status 1), custom headers
  including quotes and non-ASCII.
- **Header injection is blocked**: a route header containing a line break is refused at
  `serve start` with "Header `X-Bad` cannot contain line breaks."
- `http` **follows redirects silently**: a 302 to `/big` came back as the 200 body with no note
  that a redirect happened (the final URL is not reported, unlike `browse`).
- Connection refused → **1** "Failed to connect to /127.0.0.1:1".

## S10. Browser: dialogs, iframes, runaway script

- `alert()` on load does **not** block: the page read returns normally with the text "loaded".
- `confirm()` returns **false** automatically; the handler wrote `confirm=false` and the click
  returned at once. Dialogs are auto-dismissed rather than surfaced.
- **Iframe content is invisible**: a page whose only content is `<iframe src="/data/alpha.txt">`
  reads back as "Iframe page / after iframe" — nothing from inside the frame. `browse` sees only
  the top document.
- A 5,000-row page reads fine; the reply truncates with "… and 9943 more lines in the file" and the
  full text lands in `agent/browser/page-*.txt`. A `full:true` screenshot of it produced a 212 KB
  PNG with no error.
- Link clicks navigate the session: the answer shows the **new** URL and the new page's text.
- **Infinite JS loop wedges the tab.** Clicking a button that runs `while(true)` gave
  **1** "Browser interaction failed to answer" after ~70 s (the `browser_ms` 60 s limit), and every
  later `read` on that session returned "had no readable text". `close` freed it; one immediate
  `browse` afterwards still timed out ("The page did not finish loading before the timeout"), and
  the next attempt worked. So the blast radius is the session plus one follow-up call, not the
  whole browser capability.
- The timeout message names no cause and no remedy — a caller cannot tell "slow page" from
  "script in an endless loop".

## S11. Query edge cases

| call | result |
|---|---|
| recursive CTE to 100,000 rows | fine (`n=100000`) |
| `INSERT ... SELECT` 200,000 rows, two such jobs concurrently | both succeeded (67 ms and 177 ms), no `SQLITE_BUSY` |
| `SELECT *` of 200,000 rows | reply caps at 1000 rows ("capped at 1000") and writes a 11,795-byte CSV; `LIMIT 1000 OFFSET 199000` reaches the tail, so paging works |
| `group_concat` of 200,000 values (2 MB) with `direct:true` | **1** "Direct query result is too large; narrow the query." |
| `db` pointing at a `.txt` | **1** explains the namespace/path rule |
| `db:"../shared/user/x.sqlite"` | **1** "Workspace databases must live inside this project branch." |
| `schema` + `sql` with no `data`/`path` | **1** "Batch row binding requires `data` ... or `path`" — `schema` switches the call into batch mode even without placeholders |
| `delimiter` together with `row_regex` | **1** "Use either `delimiter` or `row_regex`, not both." |
| `row_regex` without `comment` on a file with `;` comments | **1** "Line 1 did not match `row_regex`" — comment lines must be excluded explicitly |
| `row_regex` + `comment:";"` | 2 rows imported correctly |
| `fields:[1,5]` on a 2-field row | row-level error; the whole transaction (including `schema`) rolled back, so the table did not exist afterwards |

## S12. Archive edge cases

- Packing a path that does not exist → **1** "There is nothing at ...".
- Packing an empty folder → **1** "There is nothing inside ... to pack." (an empty zip is not made)
- Unpacking a plain text file renamed `.zip` → **1** "The zip could not be unpacked: zip END header not found. Partial output may remain at notzip." — the warning is cautious: no partial file was left behind.
- `seal:true` with an empty `key` → **1** "`seal` and `unseal` need `key` ... Without one the file could not be opened again by anyone."
- Earlier (OBSERVATIONS §5): `name` must be a simple filename for pack/unpack/seal, outputs land in the project root, and the tool appends its output names to `.gitignore`.

## S13. notify / report_bug limits

- `notify` with three links → **1** "At most two notification links are allowed."
- `report_bug` with a summary longer than 160 characters → **1** "The summary must be at most 160 characters."
- Both otherwise succeed and answer with the advisory id / submission note.

## S14. graph edges

| data | result |
|---|---|
| header only, no rows | **1** "There are no data rows after the header." |
| one row | OK |
| duplicate x values | OK |
| empty y cell | OK (plotted as 0) |
| 10 series, unicode labels, negative values | OK |
| non-numeric y | **1** "Line 2 column 1 (`a`) is not a finite number." |

## S15. Patterns, `multi`, and greedy replacement

- A pattern containing a **literal line break** is refused by `read` and `write`:
  "That is not a pattern: it holds a line break, and a pattern looks inside one line."
  This is confusing next to `multi`, which exists precisely to match across lines — `multi` works
  only through character classes such as `[\s\S]*`, not through an actual newline in the pattern.
- `list` has no `multi` argument at all; passing it produces the full allowed-argument list.
- `read grep:"alpha[\s\S]*end" multi:true` returns the whole span as one numbered range `1-10:`.
- `write grep:"[\s\S]*" multi:true` on a 3-line file replaced the content **twice**
  (`[\s\S]*` also matches the empty string at EOF), leaving `ALL\nALL`. A greedy multi pattern can
  duplicate as well as delete content — no confirmation, no size guard.
- Patterns cannot match a literal newline, so "join lines" or "delete blank lines" cannot be
  expressed directly.

## S16. Pipe scoping and stop

- `stop` with `return` inside a **nested** pipe exits only that inner block: the outer pipe carried
  on, wrote the inner result and read it back (`inner-stop`).
- Top-level `stop` with `sequence:99` answered "skipping the next 99 calls"; the single following
  call came back `interrupted: skipped by stop`, and the turn ended there.
- Unresolvable step references all give the same message — "`step` references are only available
  inside `pipe` `steps`" — whether the id is a typo, a forward reference, a self reference, or a
  numeric string (`"1"` instead of `1`). Only real positional numbers (`1`, `-1`) resolve.
- `row:0` / `row:999999` → "There is no row N in this conversation ... use `query` on `agent_rows`".
- `toolcall_body:N` pointing at a **failed** call → "Tool-call position N failed; use
  `toolcall_result` to inspect its error."

## S17. Filesystem oddities

- `move` a folder into itself → **1** "A folder cannot be moved inside itself".
- `write` to `tooltest/data/alpha.txt/child.txt` → **1** "There is already a "alpha.txt" here".
- `move` with `into` naming a path that does not exist **renames** the file to that path
  (`файл-😀.txt` → `sub`), while `into` ending in `/` targets the folder.
- `write` to a folder path → "is a folder, so there is no text to write"; `read` of a folder →
  "is a folder; use list with path to see its contents".

## S18. Argument clamps (silent, no error)

| argument | out-of-range value | observed |
|---|---|---|
| `list depth` | 17, 64 | clamped to 16, no complaint |
| `list count` | 101 | clamped to 100 (`count:100`, `truncated:true`) |
| `list count` | 0, -5 | treated as 1 ("stopped at 1 paths") |
| `log count` | 0, -1 | treated as 1 |
| `read from` | 0 | treated as 1 |
| `read to` | 0 | means end of file |
| `read context` | 21 | **refused** (0-20) |
| `read char_count` | 20000 | **refused** (1-16000) |
| `read paths` | 17 entries | **refused** (1-16) |
| `shot fps` | 30 | **refused** (2-24) |
| `shot duration_ms` | 20000 | **refused** (500-15000) |

The clamps are the more dangerous kind: a caller asking for `depth:64` silently gets 16 and may
conclude the tree is shallow (see S3, where that hid real files).

## S19. Context cost of the tools themselves

From `agent_rows` (own conversation), grouped by tool and total tokens spent:

| tool | calls | avg tokens | max | total |
|---|---|---|---|---|
| background | 55 | 199 | 655 | 10,971 |
| http | 41 | 132 | 602 | 5,404 |
| list | 43 | 100 | 692 | 4,281 |
| mcp | 10 | 339 | 1,410 | 3,391 |
| write | 144 | 21 | 162 | 3,085 |
| read | 112 | 27 | 561 | 3,052 |
| diff | 16 | 167 | 930 | 2,664 |
| status | 14 | 168 | 296 | 2,354 |

- `background action:list` is the most expensive per call and its cost **grows with every job**
  because it returns the whole history; polling it repeatedly to measure timings was the single
  largest line item in this session.
- `status` costs ~170 tokens even when nothing changed (it re-lists every staged path).
- `list` with the default JSON format is verbose: `format:"text"` returned the same information in
  a fraction of the space in every comparison made here.
- `mcp` averages 339 because tool descriptions/schemas are large; `mcp` with `server` alone (no
  `tool`) re-prints every schema each time.

## S20. What a self-check can and cannot see

- **Pipe internals are invisible to `agent_rows`.** A failing step inside a `pipe` (even with
  `allow_failure:true`) leaves no `GOT` row; only the pipe's final result is recorded. A guard that
  counts failures from inside the same pipe therefore always sees 0 and never trips.
- A guard *can* work one level up: `query` counting the last two top-level `GOT` rows for a tool,
  then `stop` with `{"value":{"toolcall_body":1,...}}` in the same response. Verified: `n=1` →
  "condition was 0; the sequence continues"; `n=2` → the return text, and the next call came back
  `interrupted: skipped by stop`.
- **Long arguments are fragile inline.** A ~1.5 KB URL passed as a literal `request` was lost twice
  (the call arrived as `{"direct": true}`), and each time the tool answered with its usage text
  while the loop kept running. The same URL sent from `agent/http/longurl.http` via
  `"request": "@agent/http/longurl.http"` worked first time. Write big documents to a file.

## S21. Cancelling a runaway background job

- `background {"action":"cancel","job":"regex-a35"}` answered "Cancellation requested for
  agent/background_results/regex-a35; remote effects may continue."
- The job's file then read: "Cancellation requested; effects may be partial or unknown. nothing in
  tooltest/stress/a35.txt matches that selection" — i.e. a cancelled job settles as a normal
  result with a preamble, not as a separate "cancelled" state.
- Measured cost of the catastrophic regex `(a+)+$`: 1 MB (200k a's, one 1 MB line) took **88 s** in
  the background; the 2 MB variant had still not returned when it was cancelled ~30 min later. The
  same pattern against 250k a's was killed at the 180 s foreground limit. There is no regex time
  budget — only "foreground gives up, background keeps burning a core".

## S22. Git: optimistic checks, restore, ignored paths

- `write` with `if_sha256` set to a digest measured before a **background job** appended to the file
  → **1** "The file changed since its SHA-256 was measured; nothing was changed." The check is a
  real compare-and-swap, not a formality.
- `restore` with `path` + `revision` + `keep:true` put one file back to an older commit, left the
  change staged ("put tooltest/stress/restore-test.txt back"), and `status` showed it as a normal
  staged modification. The rest of the working tree was untouched.
- `commit` with a path that is ignored and untracked → **1** "`extra.txt` is ignored and untracked;
  nothing was committed." No silent skip.
- `commit` with `paths` naming a folder commits every changed file under it (31 paths in one go).
- `commit` with nothing changed → "Nothing to commit; no new local commit was made."
- `pull` with staged local work and an up-to-date remote → "Already up to date", no disturbance.
- `tag create` makes a local tag only ("It has not been sent"); there is no `tag delete` action, so
  a mistaken tag stays until the person removes it.

## S23. Sealing with a vault secret

- `archive seal:true key:"<secret name>"` accepted the **name** of a vault secret, produced
  `tooltest/data/beta.csv.enc` (109 bytes, aes-256-gcm) beside the source, and `seal:false` with the
  same key opened it back to the exact 48 bytes.
- A wrong key → **1** "Authentication failed: this key does not open it, or the sealed file was
  altered." AEAD tamper/wrong-key detection behaves as advertised.
- The sealed file is written next to the source (`<path>.enc`), not into the project root; the
  opened file honours `name` and lands in the project root.

## S24. Big answers are filed, not quoted

- `http GET https://raw.githubusercontent.com/torvalds/linux/master/MAINTAINERS` → 200, body
  **935.0 kB**, and the tool answered only "The answer is 935.0 kB and is filed, not quoted. It is
  at agent/http/MAINTAINERS-621b9b". The file held the real content (checked a character slice).
  So a large `http` body costs a line of context, not a megabyte — good.
- `mcp` is not as careful: a `context7 query-docs` answer (~1.5 k tokens of code) was printed in
  full and also saved. Same for `deepwiki read_wiki_contents` (8 kB). A pipe can hide it
  (`{"tool":"mcp", ...}` → `write` → `read info`), but the default reply is the whole payload.
- `browse` truncates by lines and points at the saved page; `example.com` loaded, while
  `example.org` and `en.wikipedia.org` both timed out ("did not finish loading"). External browsing
  is not reliable enough to depend on for verification.

## S25. Watching the MCP handshake with a local mock

`serve` with `capture:true` plus a `mcp/` config pointing at it makes the client's protocol visible.
The client sends, in order:

1. `POST /mcp` `initialize` (id 1, protocolVersion 2025-06-18, clientInfo `pechka`/1)
2. `POST /mcp` `notifications/initialized` (no id) **with** `mcp-session-id` taken from the
   initialize response header
3. `POST /mcp` `tools/list` with **id 5** and `mcp-protocol-version` + `mcp-session-id` headers

A static route cannot answer both id 1 and id 5, so the mock fails with "The endpoint did not return
a valid MCP tools/list response." — i.e. responses are matched by JSON-RPC id, as they should be.
`serve` routes match on method+path only, not on body, so it can play a one-shot server but not a
multi-call JSON-RPC one. (Config removed again so no dead endpoint is left behind.)

## S26. Driving a form end to end

Built `tooltest/browser/form.html` (input, Add/Clear buttons, a counter, clickable rows) and drove it:

| step | answer |
|---|---|
| `browse` with `url` + `session:"form1"` | opens the tab, returns the page text |
| `snapshot` | `[e1] input[text] "Name"`, `[e2] button "Add"`, `[e3] button "Clear"` — only interactive elements get refs |
| `type` `[e1]` "First item" | `[action: Typed into Name]` |
| `press` `Enter` | `[action: Dispatched synthetic Enter; browser default actions are not guaranteed.]` — the page's keydown handler ran, Count became 1 |
| `type` + `click` `[e2]` | Count became 2 |
| `read` | shows both rows and the counter |
| `click` `text:First item` | **1** "No matching element on page" — `text:` does not find a plain `div` |
| `click` `#list div` | works: `[action: Clicked First item]`; the row's class became `done` (checked by `eval`) |
| `js:` as a **click** target | **1** "No matching element" — `js:` only works for `wait_for` |
| `wait_for` `#never-appears` | **1** "Timed out waiting for `#never-appears` on page." |
| `wait_for` `js:document.getElementById('count').textContent === '2'` | returns immediately |
| `scroll` 3000 on a 93,940 px page | `[action: Scrolled (3000px)]`; `eval` confirms `3000/93940` |

Notes: `read` always returns the whole DOM text, not the viewport, so scrolling does not change what
`read` shows (only screenshots show the viewport). Console errors accumulate on the page and are
repeated in every later `snapshot`/`read` under `## console`.

## S27. `browse` eval: what actually works

| eval code | result |
|---|---|
| `return document.title` | **1** `SyntaxError: Illegal return statement` — although the help says `return` is supported |
| `document.title` | status 0, **empty** value |
| `(() => document.title)()` | `Form test` — an IIFE is the reliable way to get a value |
| `await new Promise(r => setTimeout(r, 100)); document.title` | status 0, empty (await is accepted, the trailing value is lost) |
| `const d = ...; d.className` | status 0, empty, but the side effects run |

So: wrap the expression in a function to read a value, and do not use `return`.

## S28. A stuck tab vs a stuck engine

- `browse` of `https://192.0.2.1/` (blackholed) → "The page did not finish loading before the
  timeout" after ~60 s. A following local `browse` worked immediately, so a one-shot timeout is
  harmless.
- `http://192.0.2.1/` → `net::ERR_CLEARTEXT_NOT_PERMITTED`: plain http to a non-local host is
  blocked by Android; only https and localhost work.
- Earlier, after a **session** load timed out twice (plus two external one-shot timeouts), every
  later `browse` — including one-shot local pages — timed out until that session was closed. That
  wedge was observed once and could not be reproduced on demand with a single stuck session, so
  treat "close the session after a timeout" as the safe habit rather than a proven rule.
- `browse` `open` is refused on the built-in browser: "`open` requires Firefox, a URL and no
  session; new tabs must be enabled in the extension." A session is created by passing `url` with a
  `session` name instead.

