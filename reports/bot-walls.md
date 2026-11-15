# Bot walls: what blocked what, measured

For the developer agent, 2026-11-15. Every row below was run from this phone in this project.
Facts first, then levers, then the decisions that are the owner's rather than a tool's.

## 1. What was observed

| target | path | result |
|---|---|---|
| `reddit.com/search` | `http` | 200 with "You've been blocked by network security" — 54 716 tokens of block page |
| `reddit.com/search` | `browse` (real Chrome) | HTTP **403**, clean failure |
| `duckduckgo.com/lite` | `http` | 200, real results — for the first ~5 queries of the session |
| `html.duckduckgo.com/html` | `browse` | after those queries: "Select all squares containing a duck" CAPTCHA, returned as **status 0 content** |
| `google.com/search` | `http` | 200, 91.2 kB, **no results in the body** (JS shell) |
| `google.com/search` | `browse` | results with titles, URLs and snippets |
| `bing.com/search` | `browse` | results, plus a cookie-consent banner above them |
| `server.smithery.ai`, `gitmcp.io` | `mcp` | TCP connect timeout after 15 s — reachability, not a bot wall |

Conclusions that follow directly from the table:

- **The browser is not a bypass.** Reddit blocks both paths; DuckDuckGo challenged the browser.
- **The browser is necessary for JS-rendered engines.** Google returns a shell to `http` and
  results to Chrome — that is the one clear win.
- **A challenge page is indistinguishable from content today.** The DuckDuckGo CAPTCHA came back
  `status 0` with plausible text; nothing in the answer said "this is a bot wall". That is the
  single most dangerous failure mode for an agent: it will quote a puzzle as search results.
- **These are IP and fingerprint decisions, not user-agent ones.** The browser sends a real UA
  and still got 403/429-class treatment.

## 2. Levers, most legitimate first

1. **Use an API instead of a page.** Exa's hosted MCP is keyless and answered every query here
   (~1 s, structured JSON, no wall). Brave, Tavily, HasData and SerpAPI sell the same thing with
   a key. This is the correct answer for research: it is stable, it is allowed by the provider's
   terms, and it does not depend on winning a fingerprint fight.
2. **Keep and reuse session state.** Cookies already persist between `browse` calls (measured:
   `reused=true`). A consent banner dismissed once stays dismissed; a locale choice sticks.
   Cheap, legitimate, and it removes the most common "wall" — the consent interstitial.
3. **Send a coherent identity.** UA, `Accept-Language`, timezone and viewport that agree with each
   other and with the egress region. Incoherent defaults are a classic automation signal.
4. **Pace and cache.** Repeat queries from one IP are what trip the limits. A local cache
   (`search_cache(query, engine, results, fetched_at)` in the SQL sandbox) makes repeats free,
   and a per-origin backoff makes bursts impossible. Both are pure wins with no policy question.
5. **Prefer bot-tolerant engines.** Server-rendered engines (Mojeek, Startpage, Brave's HTML
   endpoint) have far less to defend than Google/Bing; rotate to them instead of hammering one.
6. **Egress.** Datacenter and shared IPs are what "network security" is reacting to. Residential
   or mobile egress changes outcomes more than any client change — but it is the owner's privacy
   and cost decision, and rotating IPs specifically to defeat blocks raises terms-of-service
   questions that should be decided deliberately, not by an agent.
7. **Respect the signal.** A 403/429/CAPTCHA is the site saying no. The right agent behaviour is
   to stop, switch to an API, and say so — not to escalate. Escalation is what turns a nuisance
   into an account ban.

## 3. What a tool should do about it

- Detect the wall and **name it**: `BLOCKED`, with the marker that matched and the HTTP status.
  Never return a challenge page as `status 0` content.
- **Never retry a wall automatically.** Retries are what make a soft limit permanent.
- Surface the alternatives it knows: "DuckDuckGo is challenging; Exa answered this query in 0.9 s".
- Keep a per-origin ledger of `{last_status, last_seen, retry_after}` so pacing is possible at all.

## 4. Decisions that are the owner's, not the tool's

- Whether to pay for a search API instead of scraping.
- Whether to route egress through a residential proxy, and what that means for privacy and terms.
- Whether any target is one the project should be scraping at all.

The tool should make the first two cheap to switch on, and refuse to make the third decision on
its own.
