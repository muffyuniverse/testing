# Self-check: is this environment healthy?

One `pipe` call, no side effects outside `tooltest/selfcheck*`. It touches write, read, edits,
append, query, graph, archive pack/unpack and delete. Status 0 and a final answer of `world` plus `!`
means the core tools agree with each other. Run it after an app update, before trusting a long batch,
or when something felt wrong.

```json
{"steps": [
  {"tool": "write",  "args": {"path": "tooltest/selfcheck/a.txt", "text": "hello\n"}},
  {"tool": "write",  "args": {"path": "tooltest/selfcheck/a.txt", "edits": [{"original": "hello", "text": "world"}]}},
  {"tool": "write",  "args": {"path": "tooltest/selfcheck/a.txt", "append": true, "text": "!"}},
  {"tool": "query",  "args": {"direct": true, "sql": "SELECT 1 AS one"}},
  {"tool": "graph",  "args": {"data": "x,y\n1,2\n2,4\n", "name": "selfcheck", "type": "line"}},
  {"tool": "archive","args": {"path": "tooltest/selfcheck", "format": "zip", "name": "selfcheck.zip"}},
  {"tool": "archive","args": {"path": "selfcheck.zip", "unpack": true, "name": "selfcheck-unpack"}},
  {"tool": "read",   "args": {"path": "selfcheck-unpack/a.txt"}},
  {"tool": "delete", "args": {"path": "selfcheck.zip"}},
  {"tool": "delete", "args": {"path": "selfcheck-unpack"}},
  {"tool": "read",   "args": {"path": "tooltest/selfcheck/a.txt"}}
]}
```

Verified answer of the last step: `world` on one line and `!` on the next — `append` does not add a
newline before the text, so it lands after the newline that the first write left.

What each step proves:

| step | proves |
|---|---|
| 1-3 | create, exact edit and append reach the same file without losing bytes |
| 4 | the log database answers |
| 5 | the chart writer works (`agent/plots/selfcheck.svg`) |
| 6-8 | pack/unpack round trip preserves content, read back from the unpacked copy |
| 9-10 | cleanup runs, and a failing cleanup would stop the chain (a pipe halts on failure) |
| 11 | the original file still reads back after the archive round trip |

If step 3 answers "Replacing a nonempty file needs `overwrite:true`", the edit in step 2 did not
happen — stop and inspect instead of continuing.
