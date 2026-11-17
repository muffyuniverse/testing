# Recipe: no-progress guard (self-applied)

## The failure it prevents

An identical tool call that keeps failing is not progress. It happened here with `http`:

```
CALL http {"direct": true}                      <- `request` was lost from the arguments
GOT  http 1 "`http` needs a `request` ..."
CALL http {"direct": true}                      <- the same call again
GOT  http 1 "`http` needs a `request` ..."
```

The app shows "working", the model sees the same error, and nothing moves until the person
interrupts. Reported to the app as an advisory.

## Guard 1: put the document in a file and gate it in one pipe

A pipe stops at the first unhandled failure, so a cheap read gates the expensive step:

```
{"steps": [
  {"id": "check", "tool": "read", "args": {"path": "agent/http/longurl.http", "to": 1}},
  {"tool": "http", "args": {"request": "@agent/http/longurl.http", "direct": true}}
]}
```

Verified: a 1.5 KB URL sent this way returns 200. Long URLs, JSON bodies and multi-line headers
never have to survive an inline JSON argument.

## Guard 2: count recent failures at top level, then `stop` in the same response

**Important limit found by testing:** a pipe's intermediate steps are *not* recorded in
`agent_rows`, so a guard placed inside the same pipe as the failing step counts 0 and does not
trip. The count must look at top-level `GOT` rows, i.e. at what already came back to the model.

So the guard is two calls in one response — the second gates everything after it:

```
1. query  {"direct": true,
           "sql": "SELECT COUNT(*) AS n FROM (SELECT status FROM agent_rows
                    WHERE name='http' AND kind='GOT' ORDER BY id DESC LIMIT 2) WHERE status=1"}
2. stop   {"value": {"toolcall_body": 1, "regex": "(\\d+)$"},
           "matches": "^[2-9]",
           "return": "GUARD TRIPPED: the last 2 http calls failed; change the call before retrying"}
3. ...    any further calls are skipped with "interrupted: skipped by stop"
```

Verified twice: with `n=1` the stop answered "condition was 0; the sequence continues" and the
write ran; with `n=2` it answered with the return text and the next call was skipped.

## Rule of thumb

- Two identical failures = stop and change something (argument, form, or tool).
- Any tool call that needs a multi-line document (`http`, `write`) goes through a file.
- When a call fails for a missing argument, the next attempt must differ in that argument.
- Guards see only finished turns: run them at the start of the response that would retry.
