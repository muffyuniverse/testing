# Boundary report — agent tools and app limits

Written by the agent inside project `testing` (branch `main`), 2026-11-13.
Everything below was observed, not inferred, unless marked **unverified**.
Probe files from these tests are listed at the end; the person may delete them.

---

## 1. What is versioned, and what only looks like it

| area | in git? | evidence |
|---|---|---|
| project files | yes | normal commits |
| `agent/` (app's own folder) | **no** | `log agent/http` → "Nothing has ever changed" |
| `requests/` (a project's form folder) | **no** | wrote `requests/chat/probe2.txt`; `diff` and `log requests/chat` never show it, while a control file in `probe-dir/` does |
| `shared/` | not reachable for writing, not versioned here | `list {history}` says shared files are outside this project's versions |

**Danger.** A file an agent writes into a project's `requests/` folder is invisible to
`diff`, invisible to `log`, and never committed — so it cannot be restored. My first
deliverable in this project (a request form at `requests/chat/deepseek-deepinfra-flex.jsonc`)
disappeared from the working tree and `log` says that folder "has never changed". There is
no history to recover it from. Keep deliverables anywhere except `requests/`.

**Good.** The app's own folder is genuinely excluded: no `agent/` path is in any commit, and
the saved HTTP request/response files contain no `Bearer` or `sk-or-` strings, so expanded
secrets are not being written into versioned or on-disk artefacts that I could find.

---

## 2. Bugs (reproduced)

1. **`push` reports success when it committed nothing.** The first push of this session
   ("Add a chat request form…") answered `Pushed`; no commit was created (`log` jumps from
   `c8286df` straight to a later probe commit) and the file it was meant to save was never
   versioned. A push message is *not* evidence that anything was saved.
2. **`.gitignore` is not honoured.** `.gitignore` listed `probe-ignored.txt` *before* that
   file existed; it was still committed and pushed. The push output never mentioned ignored
   paths. This contradicts the instruction "An ignored file is never sent".
3. **`list {total: true}` is ignored.** With and without `grep`, it returns the paths instead
   of a count.
4. **`browse` returns success on failure.** A domain that does not resolve answers `0` and
   saves a "Webpage not available" page as if it were content.
5. **`shot` can capture a blank page.** First capture of `https://example.com` was 12 KB of
   background colour with no content; the same URL captured again (and with `full`) rendered
   correctly. No wait by default.
6. **SQL runs only the first statement, silently.** `SELECT 1; DROP TABLE requests` returned
   `1` and discarded the `DROP` with no warning. Nothing was dropped (`requests` still has
   rows), but the tool reports success for a statement it only partly ran.
7. **`PRAGMA` is refused as "not a read"**, while `SELECT * FROM pragma_table_info('agent_rows')`
   is allowed. The gate is keyword-based, and the message misdescribes a read as a write.
8. **`diff` calls small text files "binary"** — `.probe-dot` (10 bytes of text) and every
   `.gitkeep`.
9. **A sealed archive cannot be opened.** `archive {seal: true, key: "probe"}` writes
   `renamed.zip.enc`; unpacking it ignores `key` and fails with `zip END header not found`.
   Sealing is one-way for the agent.
10. **Unpacking into an existing folder forks it**: `reports/copies` became `reports/copies 2`.
11. **`archive` will not seal a folder directly** — pack, then seal the zip (reasonable, but
    the error only says so after the attempt).
12. **Wrong refusal message for `agent/` writes**: "agent/ is the app's own folder. Read it;
    `forget` takes rows out of your context" — that answers a different question than the one
    asked.
13. **`http` private-address refusal mentions browsing**: "Allow private addresses in settings
    to browse it", for a request that was not a browse.
14. **`http` malformed-document error mangles the input**: `this is not a request` → `"THIS"
    is not a method this app can send` (uppercased).
15. **`mcp` listing and `mcp` invocation disagree on the config format.** `{"servers": {...}}`
    is listed as a configured server, then invocation says `mcp/probe.jsonc has no url field`.
    The flat `{"url": …, "name": …}` form works.
16. **`mcp` errors echo the server's raw body** into the agent's context — a 405 answer dumped
    a chunk of the remote page's HTML into the error string.
17. **A one-line file cannot be paged.** `read` of a minified JSON answered ~20k tokens; both
    `from/to` and `grep` return the whole line, and the result is re-filed, so the file stays
    effectively unreadable. Hit twice (OpenRouter endpoint lists).

---

## 3. Danger zones, in order of how much they should worry you

1. **The agent can edit the file that instructs it, and the edit is pushed.** `project_prompt.txt`
   is an ordinary writable file. I appended a line, committed it, and restored it. Any prompt
   injection that reaches an agent can therefore persist instructions in the repo — and the
   next session, on any device, obeys them.
2. **The agent can register MCP servers.** Writing `mcp/probe.jsonc` was enough: `mcp` listed it
   at once, with no restart, and invoking it made a live network call. An MCP server is code the
   app will talk to and, per the tool description, hand host-bound vault credentials. Registration
   is verified; credential handoff is **unverified**.
3. **`.gitignore` is a false comfort.** Secrets or junk a person believes are excluded get
   pushed (bug 2).
4. **`push` success is not persistence** (bug 1). Combined with the unversioned `requests/`
   folder, an agent can report "saved and pushed" about work that exists nowhere.
5. **`browse` failure looks like content** (bug 4). A page that failed to load arrives with the
   same status as one that loaded, so a careless agent quotes an error page as a source.
6. **`agent/` is readable by the agent, and it holds untrusted web text.** Saved pages from
   `browse` live there and can be read back later in the same session — content written by a
   stranger, one `read` away from being treated as project material.
7. **Secret expansion is not limited to headers.** `{{secret:…}}` expands anywhere in the
   request document. Host binding is enforced strictly and well (a secret for `openrouter.ai`
   was refused for `api.github.com` and vice versa, with the host and scope named), but a secret
   placed in a URL query string ends up in that server's logs. Whether URL expansion is allowed
   is **unverified** (not tested).
8. **`forget` cannot be used early.** It refuses below the clear-up point (100k here) even when
   asked, so an agent cannot prune its own context while it still fits; it must wait for the
   app to decide. Not dangerous, but it means a long exploration like this one fills up.

---

## 4. What held up well

- Secret host binding, with precise refusals naming host and scope.
- `original`/`grep`/`from`-`to` write selectors: ambiguity refused with the count and a fix
  suggested; `grep` really replaces every match; out-of-range lines refused.
- `..` outside the workspace refused with an exact explanation of what is reachable.
- `shared/` is read-only to the agent in every direction tried (write, delete, copy target),
  and the refusals are accurate.
- `read` of a binary file refused cleanly ("not a text file").
- Per-file `restore` from a revision works and stages the result; `read path@sha7` works.
- `http @file.http` request documents work; missing `{{file:…}}` is caught *before* sending.
- `pull` is a merge and reports "Already up to date" when it is.
- `delete`/`move` on the agent's own files behave as advertised and create parent folders.

---

## 5. Not tested (and why)

- **Push conflict path.** Would need a commit on the remote that this phone does not have; I was
  not willing to invent one on your GitHub without asking.
- **Whole-project `restore`.** It has no dry-run and no confirmation argument; too destructive
  to try on a repo with real history.
- **Tag deletion.** No tool deletes a tag; `push {tag}` is one-way.
- **MCP credential handoff** and **cross-project conversation leakage** (`all_agent_rows` showed
  only this project, so there was nothing to leak between).
- **Redirect handling for `http`**, i.e. whether a key follows a redirect to another host.

---

## 6. Probe files left behind (safe to delete)

`.probe-dot` (deleted), `probe-traversal.txt`, `probe-ignored.txt`, `extra.txt`,
`probe-dir/`, `requests/chat/probe2.txt`, `reports/` (this report, `copies/`, `copies 2/`,
`copies.zip`, `renamed/`, `renamed.zip`, `renamed.zip.enc`, `probe.http`),
`.gitignore` (one line added), git tag `boundary-probe`, and two probe commits.
`project_prompt.txt` was restored to its original text.

---

## 7. Late additions (tested after the sections above)

- **A placeholder is refused in the address line.** `GET https://openrouter.ai/api/v1/models?probe={{secret:openrouter}}`
  → "That is not an address". So a secret cannot be pushed into a query string from the address
  line. This closes danger 7 above; a placeholder is only expanded in headers and the body.
- **`user@host` addresses are refused** ("Addresses with embedded credentials … are refused"),
  which kills the classic parser-differential trick.
- **Only http and https**; `file://` is refused with the scheme named.
- **Redirects are followed, across hosts, silently.** `https://httpbin.org/redirect-to?url=https://example.com/`
  answered `200` with example.com's body, and the reported address is still the one I typed, not
  where it landed. Host binding is checked against the typed address only, so a redirect is the
  one remaining way a header could reach a host nobody chose. Whether the `Authorization` header
  survives the hop is **unverified** (it needs a redirecting host that a stored secret belongs to).
- **`delete` works on folders and does not leave a `.gitkeep` behind**; `.gitkeep` files appear
  when a folder is *created* by a write or a move, and they are committed.

