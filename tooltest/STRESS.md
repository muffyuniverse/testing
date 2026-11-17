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

