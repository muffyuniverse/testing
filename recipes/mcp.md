# MCP toolbox — what is configured and how to use it

Set up and verified from this project on 2026-11-15. Every server below answered a real
`tools/list`, and every tool listed was invoked at least once except where noted.

A server is a file in `mcp/`: the **filename is the server name**. Flat JSON is
`{"url": "…"}`. A Markdown file with a ```` ```mcp ```` block also works and is the only form
that takes `auth:` / `token:`.

---

## 1. Web search — `exa` (keyless)

`https://mcp.exa.ai/mcp` — Exa's hosted server. Its own docs state: *"No API key is needed to
try search and fetch, at a free rate limit."* This is the working general-purpose search.

| tool | use |
|---|---|
| `web_search_exa` | search; needs `query` **and** `objective` |
| `web_fetch_exa` | read URLs as clean markdown; `urls` array, `maxCharacters` |

```
mcp {"server":"exa","tool":"web_search_exa",
     "args":{"query":"detailed blog post about Android WebView debugging","objective":"find the official remote-debugging guide","numResults":5}}
```

- Query like a description of the page you want, not keywords — Exa is embedding-based.
- `category:company`, `category:people`, `category:news`, `category:publication`,
  `category:personal site` narrow the type.
- Keyless limits: `agent_run` is unavailable, and the free tier can answer `429`. Exa's docs
  also warn that keyless access expects a direct IPv4 connection — over IPv6 or a proxy it can
  answer `403`. It worked from this phone.

## 2. Filtered search — `exa-advanced`

Same endpoint with `?tools=web_search_exa,web_fetch_exa,web_search_advanced_exa`. The explicit
list replaces the defaults, so include the ones you still want.

`web_search_advanced_exa` adds `includeDomains`, `excludeDomains`, `category`, `start/endPublishedDate`,
`includeText`/`excludeText`, `userLocation`, `enableSummary`, `enableHighlights`,
`textMaxCharacters`, `subpages`. Verified with `includeDomains:["developer.android.com"]`.

## 3. Library documentation — `context7` (keyless)

Two-step, and the first step is required:

1. `resolve-library-id {libraryName, query}` → a Context7 id such as `/vercel/next.js`
2. `query-docs {libraryId, query, language}` → version-specific docs and code examples

## 4. Vendor documentation search (keyless)

| server | tool | scope |
|---|---|---|
| `mslearn` | `microsoft_docs_search`, `microsoft_code_sample_search`, `microsoft_docs_fetch` | Microsoft/Azure docs; search returns up to 10 chunks, `fetch` converts a learn.microsoft.com page to markdown |
| `cloudflare-docs` | `search_cloudflare_documentation` | Cloudflare products; returns semantically similar chunks |
| `deepwiki` | `ask_wiki_question`, `read_wiki_contents`, `read_wiki_structure` | GitHub repos already indexed by DeepWiki; unindexed repos answer "Repository not found" |

## 5. Practical notes

- **Results are filed**: every answer is also written to
  `agent/mcp/<server>-<tool>-<timestamp>.txt`. The names are timestamped, so unlike `http`
  there is no collision between repeated calls to the same tool.
- **Large answers are truncated inline** (tens of kB) with the tail pointing at the full file.
  Read the file with `grep` or `char_from/char_count`; do not re-invoke to get the rest.
- **Arguments are not validated against the schema.** A call missing a required field runs
  anyway (bug-list #16), so match the schema yourself — `mcp {server}` prints it.
- **A wrong URL pastes the remote page into the conversation** (bug-list #17). Check the URL
  before adding a server.
- **Transport timeouts are ~15 s** and the error names the address (gitmcp.io timed out this way).
- A `{"servers": {…}}` wrapper is listed by `mcp` but fails on invocation — use the flat form
  (bug-list #18).
- Credentials belong in the vault, never in the file: `token: {{secret:name}}`, and the secret
  must be bound to the server's host or the call is refused before it is sent.

## 6. Adding a server

1. Find a `streamable-http` remote in the official registry:
   `https://registry.modelcontextprotocol.io/v0/servers?search=TERM&limit=5`
   — check `remotes[].headers`: `isRequired`/`isSecret` there means you need a key.
2. Write `mcp/<name>.jsonc` with `{"url": "…"}`.
3. `mcp {"server":"<name>"}` to read the schemas, then invoke one tool.
4. If it does not answer, delete the file — a dead config still shows up in `mcp` listing.
