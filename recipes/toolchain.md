# Reusable pipe recipes

Copy these into a `pipe` call and change the obvious parts. Verified 2026-11-15 in this
project; see [reports/upgrade-report.md](../reports/upgrade-report.md) for what each test
proved.

## 1. HTTP JSON → field, without leaking the body into context

`json` extraction only works when the http step is `direct:true`. A normal http step's body
is the human summary, so `json` silently falls back to `default`.

```json
{"steps": [
  {"id": "api", "tool": "http", "args": {
    "direct": true,
    "request": "GET https://api.github.com/repos/OWNER/REPO\nAuthorization: Bearer {{secret:github-muffyuniverse}}\nAccept: application/vnd.github+json\n\n"}},
  {"id": "pick", "tool": "write", "args": {
    "path": "notes/repo.txt",
    "text": {"template": "branch={{b}} private={{p}}",
             "vars": {"b": {"step": "api", "json": "default_branch", "default": "<none>"},
                      "p": {"step": "api", "json": "private", "default": "<none>"}}}}},
  {"tool": "read", "args": {"path": "notes/repo.txt"}}
]}
```

Fallback when the payload is not JSON: `{"step":"api","regex":"...","group":1,"default":"<none>"}`.

## 2. Guard a chain on a failure, without aborting

```json
{"steps": [
  {"id": "check", "tool": "read", "args": {"path": "reports/x.md"}, "allow_failure": true},
  {"tool": "stop", "args": {"status": {"step_status": "check"}, "equals": 1,
                            "return": "input missing, nothing done"}},
  {"tool": "write", "args": {"path": "reports/x.md", "text": "..."}}
]}
```

`return` exits the pipe with that text and status 0; the write never runs. At top level,
`stop {"sequence": N}` skips the next N calls instead (they come back as
`interrupted: skipped by stop`).

## 3. Preview anything built in `app/`

```
serve  {"action":"start","server":"preview","root":"app","capture":true,
        "routes":{"/api/ping":{"status":200,"body":"{\"pong\":true}"}}}
shot   {"url":"http://127.0.0.1:<port>/"}            # still
shot   {"url":"http://127.0.0.1:<port>/","duration_ms":3000,"fps":8}   # clip
look   {"paths":["agent/browser/clip-*.mp4"],"frames":4}
serve  {"action":"stop","server":"preview"}
```

Read `agent/serve/<server>/requests.jsonl` for what the page actually asked for.
`browse` on a page that fetches after load returns the loading state — trust `shot`.

## 4. Sealed round trip (mind the name)

`unseal` writes the name it derives by stripping `.enc`, and refuses if that exists — so
delete the source first, or seal a copy under a different stem.

```
archive {"path":"build","format":"zip"}          -> build.zip
archive {"path":"build.zip","seal":true,"key":"…"} -> build.zip.enc
delete  {"path":"build.zip"}
archive {"path":"build.zip.enc","seal":false,"key":"…"} -> build.zip
```

A wrong key answers `Authentication failed: this key does not open it…`.

## 5. Local data

Read-write scratch DB (survives between turns, one statement per call):

```
query {"db":"scratch","sql":"CREATE TABLE t(id INTEGER PRIMARY KEY, v TEXT)"}
query {"db":"scratch","sql":"INSERT INTO t(v) VALUES ('x') RETURNING id, v"}
query {"db":"scratch","sql":"SELECT * FROM t"}
```

Spend of this conversation's requests:

```
query {"sql":"SELECT model, SUM(n) requests, ROUND(SUM(cost)/1000000.0,4) usd FROM roll_hour GROUP BY model"}
```
