# Firefox bridge — what works (tested against the updated add-on)

Check state first:

    env {"section":"capabilities"}

The `browser` line reports the bridge and its pairing, e.g.
`firefox (paired on 127.0.0.1:49187, asleep)`. `asleep` is normal — a command wakes it.

All calls use `session:"shared"`, the tab the person shares in Firefox.

## Works

| call | notes |
|---|---|
| `browse {session:"shared"}` | full page text of the live tab |
| `browse {session:"shared", url:"…"}` | navigates the real tab |
| `shot {session:"shared"}` | PNG of the live tab (viewport only) |
| `browse {session:"shared", output:"eval", eval:"expr"}` | **expression only** |

`eval` sees the real DOM: `document`, `window`, `location` are objects.
Use `document.title`, not `return document.title` — a `return` statement throws
`@sandbox eval code`.

## Broken (as of this build)

- `type`, `press`, `click`, `scroll` → `Interaction script not ready on page.`
  The interaction content script is never injected, so this does not depend on the page.
- `snapshot` → page text, but no `[eN]` control refs.

## Workaround: drive the page with eval

Expressions can use the comma operator, so a whole interaction fits in one call:

    browse {"session":"shared","output":"eval",
            "eval":"(document.querySelector('input[name=q]').value='query', document.querySelector('form').submit(), 'submitted')"}

The navigation lands slightly after the eval returns — read the page on the *next*
call, not the same response.

## When to prefer the native viewer

Native (`browser  com.google.android.webview …`) has refs, click/type/press, full-page
PNG and MP4 recording. Use the bridge only when the point is the person's own
logged-in Firefox session; use the native viewer for driving pages.
