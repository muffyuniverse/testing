# Spec: a session browser tool

Requested by the project owner, 2026-11-15. Written from measurements taken with the current
`browse`/`shot`/`serve` tools in this project, not from assumptions. Everything marked
**measured** was run; everything marked **proposal** is a design choice.

---

## 1. The problem, measured

`browse` is a real Chrome and it already keeps state: a cookie set on one call was still there
on the next (`reused=true`, timestamp unchanged), and `localStorage` persisted too
(**measured**, `samples/cookie.html`). What it cannot do is hold a *page* between calls, and it
always returns the page's text.

| limitation | evidence from this session |
|---|---|
| every call is a fresh navigation | scroll position, typed text, open modals and JS variables are lost between calls |
| full page text always enters context | a search page costs ~8 kB of context per query even when the eval result is 300 bytes |
| no `wait_for` | flows are timed with `setTimeout` guesses (`await new Promise(r=>setTimeout(r,900))`) |
| no JSON-only mode | `browse` answers page text **plus** `## eval`; the text is the expensive part |
| no identity control | user agent, locale, viewport and profile cannot be chosen or reset |
| no network visibility | no way to wait for a specific XHR, inspect responses, or block trackers |
| 30 s per call | long pages or slow origins cannot be waited on across calls |

## 2. Proposal: one tool, sessions, actions

```
browser {session?, action, ...params}
```

`session` is a name; `"default"` when omitted. A session owns one page and one profile, and
lives between calls until closed or the app restarts.

### Actions

| action | params | returns |
|---|---|---|
| `open` | `session`, optional `user_agent`, `locale`, `viewport`, `fresh:bool` | `{ok, session, created:bool}` |
| `goto` | `url`, `wait?` (`load`\|`dom`\|`idle`), `timeout_ms` | `{ok, url, status, title}` |
| `click` | `selector` **or** `text`, `timeout_ms` | `{ok, matched:{selector, text, bounds}}` |
| `type` | `selector` **or** `label`, `text`, `submit:bool` | `{ok, chars}` |
| `press` | `key` | `{ok}` |
| `scroll` | `dx`, `dy` or `to_selector` | `{ok, y}` |
| `wait_for` | `selector`, `network_idle:bool`, `timeout_ms` | `{ok, waited_ms}` |
| `eval` | `js` (may `await`), `max_chars` | **`{ok, result}` only — no page text** |
| `text` | `selector?`, `max_chars` | `{ok, text}` — the explicit opt-in to page text |
| `shot` | `full:bool` | `{ok, path}` |
| `state` | `op: save\|load\|clear`, `path?` | `{ok, cookies, origins}` |
| `list` | — | `{sessions:[{name, url, age_ms}]}` |
| `close` | `session` | `{ok}` |

### Requirements that matter most

1. **`eval` returns only JSON.** `result` is serialised, capped (16 kB), with an explicit
   `truncated` flag and the full value filed to a path — never the page's text.
2. **The page survives between calls.** `window` state, form values, scroll and open modals
   persist; `eval` can read a variable set three calls earlier.
3. **`wait_for` with `network_idle`** so no flow depends on `sleep`.
4. **Structured errors**, never a silent page:
   `NO_SESSION`, `NAV_FAILED`, `TIMEOUT`, `NOT_FOUND` (with up to 3 fuzzy candidates),
   `BLOCKED` (challenge detected, see [bot-walls.md](bot-walls.md)), `JS_ERROR` (message + line).
5. **Challenge detection.** If the document is a bot wall — title or body matching
   `captcha|are you a robot|unusual traffic|verify you are human`, or status 403/429 with a
   challenge body — answer `BLOCKED` with the marker that matched. A CAPTCHA page must never be
   reported as `ok` with page text; that happened with DuckDuckGo in this session.
6. **Per-session identity**, and `fresh:true` to get a clean profile on demand.
7. **`state save/load`** to a project file: cookies plus per-origin `localStorage`, so a working
   session (a consent dismissal, a login) can be reproduced or handed over.

### Non-goals

No credential handling, no CAPTCHA solving, no proxy rotation. Those are policy decisions for
the owner and belong in egress configuration, not in a tool.

## 3. Why this is the 80/20

- Search: `goto` → `eval` returning 5 results costs a few hundred tokens instead of ~8 kB, and
  the same session can then `click` a result and `eval` its content — a research loop that today
  needs a fresh navigation per step.
- Any app-like page: login or dismiss a consent wall once (`click`), keep it for later calls.
- Debugging: a `JS_ERROR` with message and line instead of a blank screenshot.

## 4. Test plan (each item is pass/fail)

1. **Cookie persistence** — set a cookie, reload, read it: `reused=true` without re-setting.
   (Already true of `browse`; must not regress.)
2. **JS state across calls** — `eval: window.__probe = 42`, then a second call reads `42`.
3. **Form state across calls** — `type` into an input, `eval` its value on the next call.
4. **No page text** — `eval` on a 200 kB page returns only the JSON; measure tokens.
5. **`wait_for` network idle** — a page that renders after a 1.5 s fetch; no `sleep` used.
6. **`NOT_FOUND`** — bad selector returns an error with candidates.
7. **`BLOCKED`** — a known challenge page is reported as an error, not as content.
8. **JS shell** — Google search URL: `eval` yields ≥5 result URLs (today `http` gets a shell;
   `browse` gets results — measured).
9. **`state save/load`** — save cookies, `open` a fresh session, `load`, cookie is present.
10. **WebGL capture** — `shot` of a canvas page is not black, or the answer says it cannot be
    captured (today it is silently black — advisory filed).

## 5. Priority if only part of it ships

`eval` returning JSON only → persistent page → `wait_for` → `BLOCKED` detection → `state`
save/load → identity controls → network visibility.
