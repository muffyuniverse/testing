# Tool-use critique — what the system prompt gets wrong

Written from this branch's own conversation log (`agent_rows`, ~3,600 rows, every tool result with
`status`), not from memory. Two caveats before the numbers:

- A large share of the failures are **deliberate probes** — `tooltest/`, `no-such-secret`,
  `deadbeef`, `example.com`, `mcp nope`. Those are not confusion; their value is in whether the
  error message taught me what to do next, and several did not.
- The **accidental** failures cluster in one task: building and updating an Android app through
  GitHub Actions with `http`, `write`, `pipe`, `app_rpc` and `pull`/`push`. That is the honest
  sample of "a real job", so it gets the most weight below.

## Failure rate by tool

| tool | calls | failed | rate | what it means |
|---|---|---|---|---|
| `pipe` | 58 | 21 | 36% | the longest prompt section, the worst score |
| `archive` | 38 | 14 | 37% | naming rules |
| `http` | 96 | 31 | 32% | 404s and a stale CDN |
| `browse` | 87 | 24 | 28% | `wait:"done"` hangs |
| `write` | 250 | 54 | 22% | stale `original`, Windows names |
| `query` | 102 | 20 | 20% | SQL errors, one missing table |
| `list` | 65 | 13 | 20% | JSON shape |
| `read` | 202 | 27 | 13% | mostly deliberate misses |
| `background` | 72 | 4 | 6% | cancel semantics |

## pipe — the most expensive section

**Tripped:** `Argument text: step references are only available inside pipe steps` (4×), `pipe does
not take id` (2×), `Step 2 does not take steps`, `Step 1 does not take server`, `steps has 65
entries, and 64 is the limit`, `pipe exceeded the maximum of 64 step executions`, and the worst
one:

> `Step 2 (w) when: Argument status: Step bad failed; its body cannot be piped.`

**Gap.** The rule *is* in the prompt ("Call ordinary tools with literal values... To use a source,
call `pipe`"), but it is stated once, far above, in a section about `pipe`. The tool schemas I
actually compose against say nothing. The natural order of thought is "I have this result, now
pass it on", and the first attempt is refused at runtime.

The `when` rule is genuinely ambiguous: "unless ... that step actually inspects `step_status`,
`step_error`, or `default`" — my `when` inspected `status`, and the step still tried to pipe a
failed body. Reading `step_status` in `when` and reading it in `args` are different things, and
the prompt does not distinguish them.

The **64-step limit is mentioned nowhere**; I found it by hitting it twice.

**Fix.** Put "literal only — sources need `pipe`" into each arg description, not one section; say
`id` is a step key only; state the 64 limit; and say a `when` that reads `step_status` does not
make the body available.

## write — stale originals and Windows

**Tripped (accidental):** `nothing in app/src/main/java/.../Updater.java matches original. Read the
file first` after I had edited the same file twice in a row. Also `The file changed since its
SHA-256 was measured` (2×, `if_sha256`), `Edits #1 and #2 overlap`, `That text appears 2 times`.

**Tripped (naming):** `nul.txt is a name Windows reserves for a device`, `con.txt`, `CON.txt`, `A
name cannot contain |`, `:`, `"`, `A name cannot end with a space or a full stop` — eight failures
on a rule the prompt never states. The checkout has to survive Windows, so device names and
`\| : " ? * < >` and trailing dots/spaces are refused.

**Also unmentioned:** `archive` names must be a bare filename (`name must be a simple filename of
at most 160 letters, digits, dots, hyphens or underscores`), while the prompt only says "a used
scratch name gets a suffix". And `if_sha256` exists in the schema but not in the prose.

**Fix.** Say the checkout is Windows-compatible and list the forbidden names/chars once. Say that
a refused `original` almost always means the file moved under you — grep for the anchor, do not
retype from memory. Say the uniqueness rule applies to the whole file, not the matched line.

## browse — `wait:"done"` is a trap

**Tripped:** `The page did not finish loading before the timeout` — nine times, the single biggest
time sink in the log. Pages with polling or websockets never reach network idle, so the default
`wait:"done"` waits for a condition that will not come.

The prompt lists the `wait` values but never says which one to use when `done` hangs, nor that
`dom` returns as soon as the DOM is parsed. `net::ERR_CLEARTEXT_NOT_PERMITTED` (plain http to a
non-localhost address) is also undocumented.

**Fix.** One sentence: "`done` waits for network idle and will hang on pages that poll — use
`dom` for those." Mention the cleartext rule next to the private-address rule.

## http — 404s with header noise, and a CDN that lies

**Tripped:** `HTTP 404 Accept-Ranges: bytes Connection: keep-alive Content-Type: text/plain Date:
...` — eleven times, and `404: Not Found` nine more. A 404 is a normal answer; the raw header dump
in the failure body is noise, and the same URL in a browser shows a clean "Not Found".

**The expensive one is not a tool bug:** `raw.githubusercontent.com` serves with `max-age=300`
keyed on path only. A manifest written by a build that finished a minute ago reads as the
previous version, and `?t=<millis>` does **not** defeat it — I proved that with a unique query and
got the stale file back. That cost two rebuilds and a wrong "up to date" on the phone. The prompt's
`http` section says nothing about stale 200s, and "read a raw file to check freshness" is the
obvious move for anyone watching a CI artifact.

**Fix.** Warn that a 200 can be a cached copy and that path-keyed CDNs ignore query busting; prefer
an API endpoint for freshness. Report a non-2xx as `status body` without the headers, or at least
put the headers last.

## query — a table that does not exist

**Tripped:** `no such table: roll_hour`. The system prompt describes `roll_hour`, `roll_day`,
`roll_minute` and `requests` as tables of the request log; in this workspace they are absent, so
the documented schema is wrong (or the tables are pruned here without saying so).

Also: `malformed JSON (code 1 SQLITE_ERROR)` (3×) names neither the expression nor the value;
`Direct query result is too large; narrow the query` hides the 6,000-token cap that produced it.

**Fix.** Correct the table list, or say which log tables are guaranteed. Name the offending
expression in JSON errors. State the direct-result cap.

## read — small things that cost a turn

- `runlogs/build/7_Build release APK.txt could not be read (not a text file), so nothing was
  changed` — "nothing was changed" is write's phrasing on a read.
- Direct reads are numbered; a copied `original` keeps the numbers and fails. Piped payloads are
  not numbered. The prompt says the second half, not the first.
- `Row at line 1 has 1 field(s), so field 2 is out of range` — field projection on a one-column
  file is legal syntax, just pointless; the message is fine.

## app_rpc — advice that is wrong

- `No resumed Activity. Call launch first.` (2×). `launch` does **not** resume the activity when
  the screen is off, so the advice sends me in a circle. It should say "bring the app to the front;
  the screen may be off", and `ui.*` should say it needs a resumed activity.
- `Unknown RPC method: app.version` — the provider answered, the method was simply gone after a
  process restart because a file-scope `register { }` never ran. Nothing in the error hints at
  registration. Filed as an advisory against the template.

## push / pull — refusals without a reason

`Nothing was pushed; the remote did not accept the branch.` — three times, no cause. Non-fast-
forward, branch protection and auth failure all produce the same sentence, so the only move is to
`pull` blind. The prompt says what to do when the remote is ahead, but the error should name the
reason the way `pull` names conflicted paths.

`pull` answers `Brought in 2 path(s)` — no word on fast-forward vs merge, no commit id, no conflict
state. For a loop that pushes and pulls every few minutes, that is the one fact I needed.

## background — "outcome may be unknown"

`That job is not cancellable or not running in this session; its outcome may be unknown.` — four
times, each for a job that had already finished successfully. "May be unknown" for a completed job
is alarming and false; it should answer "already finished, status 0".

## Smaller ones

- `list`: the JSON payload (`items, paths, files, matched, complete, skipped, truncated,
  next_cursor`) is heavy for a yes/no question. `matched:false` + `complete:true` versus
  `matched:null` + `complete:false` is explained in the prompt and still easy to misread at a
  glance. `format:text` should be the suggested default for a human-shaped question.
- `archive`: `There is nothing inside tooltest/stress/emptyfolder to pack` and `Authentication
  failed: this key does not open it` are exactly right.
- `ssh`/`sftp`: `There is no secret called "no-such-secret" ... The names it knows are:
  "github-muffyuniverse", "open..."` — the best error in the whole toolset; it lists the valid
  names. Every tool should do this.
- `notify`: `At most two notification links are allowed` and `report_bug`: `The summary must be at
  most 160 characters` match the prompt exactly. No friction.
- `stop`: `sequence` 0 means "skip all remaining", not "skip none". Stated, and still backwards.
- Skipped calls are recorded as failures (`interrupted: skipped by stop`, 4×), which pollutes any
  honest failure-rate analysis like this one.

## Cross-cutting

1. **The prompt is long where it is least used.** `pipe` has the longest section and the worst
   failure rate. A short "sources" primer plus the reference would beat one dense block.
2. **Schema text is what I compose against, prose is what I remember.** Every rule that must hold
   at call time (literal vs source, bare filename, Windows names) belongs in the argument
   description, not only in the system prompt.
3. **Context is spent on protocol, not work.** A single GitHub release JSON is ~1.5k tokens and I
   fetched several. Nothing in the prompt suggests projecting API answers (`char_from`, `grep`,
   `fields`) before reading them.
4. **Errors should teach the next move.** `ssh` lists the names it knows; `push` says only "did not
   accept". The first saved a turn, the second cost one every time.
