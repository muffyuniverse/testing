# Tools available in this project

Every call answers with a first line of `0` (it worked) or `1` (it did not, and the rest
says why). `ask`, `conclude` and `wait` end a turn and must be called alone.

Notation: `*` marks a flag I verified by experiment in this project; the rest are taken
from the tool descriptions.

## Reading

### `read` — read a file, or one of its versions
| flag | meaning |
|---|---|
| `path` | the file, relative to the project (required) |
| `from` | first line, 1-based; defaults to 1 |
| `to` | last line, inclusive; omitted runs to the end |
| `grep` | regular expression matched inside one line; returns only matching lines, each with its line number (*) |
| `revision` | an id from `history`, to read an old version |

Notes: `grep` is applied *inside* the `from`/`to` window, not to the whole file (*);
it is case-insensitive and supports anchors such as `^` and `\b` (*); a truncated
answer says so, and `from`/`to` fetch the rest.

### `tree` — list the files of a folder
| flag | meaning |
|---|---|
| `path` | where to look (required) |
| `depth` | how deep to recurse; defaults to 2 |
| `name` | regex on file names; may answer with a folder |
| `grep` | regex on file text; keeps matching files and shows the matching lines |
| `from` / `to` | pick a range of the result's lines (each line is a path) |

## Changing

### `edit` — change part of a file
Give **exactly one** of these selectors (*):
| flag | meaning |
|---|---|
| `find` | literal text to replace everywhere it appears (*) |
| `from` + `to` | replace that block of lines, inclusive; `from` without `to` replaces to the end of the file (*) |
| `at` | insert *after* that line number; `at: 0` inserts at the top (*) |
| `text` | write the whole file |

...and one of these as the content:
| flag | meaning |
|---|---|
| `replace` | the new text, used with `find`, `from`/`to` or `at`; omitted means delete |
| `text` | the whole new contents |

Gotchas:
- `find` is **literal**, never a regex: `\bcat\b` and `c.t` are searched as characters (*).
- `find` is case-sensitive, has no word boundaries, and crosses line breaks (*).
  Use ` cat ` with spaces, or a longer unique fragment, to hit one word only (*).
- `replace` may be a read call, `{"tool": "read", "args": {...}}`, to copy another
  file's lines in verbatim, without them passing through the conversation (*).
  A bare `{"path": ...}` is not recognised and is written in as literal JSON text (*).
- The read call's own `from`/`to`/`grep`/`revision` decide *which* lines are copied,
  independently of the edit's selector (*).
- Giving two selectors at once is refused (*); giving both `text` and `replace` silently
  uses `text` and drops `replace` (*).
- `find` text that is not present is refused, and nothing is written (*).

### `create` — make a new file, with its folders if needed
| flag | meaning |
|---|---|
| `path` | file to create (required) |
| `text` | its contents |

It does **not** overwrite: an existing file is refused (*) — use `edit` with `text`.

### `delete` — delete a file, or a folder and everything in it
| flag | meaning |
|---|---|
| `path` | what to delete (required) |

### `move` — move or rename a file
| flag | meaning |
|---|---|
| `path` | the file (required) |
| `into` | the folder to move it into |
| `name` | the new name; inside its own folder this is a rename |
| `copy` | `true` leaves the original where it is |

A name that is already taken is refused.

## History

### `history` — list the versions of a file, newest first
| flag | meaning |
|---|---|
| `path` | the file (required) |
| `from` / `to` | pick a range of versions |
| `grep` | keep only versions with a matching line, and show that line |

### `restore` — put an old version back, as a new version
| flag | meaning |
|---|---|
| `path` | the file (required) |
| `revision` | the id `history` gave you (required) |

Nothing is lost by restoring.

## Working

### `background` — run a command, result written to a file
| flag | meaning |
|---|---|
| `tool` | the tool to run, e.g. `read` |
| `args` | that tool's arguments, as an object, e.g. `{"path": "big.txt"}` |

Returns at once with the path of the result file; read it to see whether the command
finished. It keeps running while the turn carries on.

### `forget` — take rows out of the context
| flag | meaning |
|---|---|
| `ids` | row ids to drop: `2`, `7-12`, or several like `"2 5 7-12 14"` |

Rows stay in the record, marked `out`; a call together with its result, never the newest
task.

### `reason` — write down what you are thinking
| flag | meaning |
|---|---|
| `text` | the reasoning |

Goes in the record, not in the project's files.

## Ending a turn (each must be called alone)

| tool | flag | meaning |
|---|---|---|
| `ask` | `question` | ask the person one clear question and stop |
| `conclude` | `text` | say the work is done |
| `wait` | — | idle, the person has not spoken yet |

## Rules the app enforces

- A locked file, or anything inside a locked folder, is read-only; a refusal is the answer.
- Names starting with a dot, and the app's own folders, are not yours to change.
- `agent/` — the record — is read-only: `agent/context.log` holds the rows in context,
  `agent/session.log` everything that happened.
- Save is a commit on the phone; nothing is pushed to GitHub.
- A result is never silently shortened: "showing first N of M" means ask for the rest.
