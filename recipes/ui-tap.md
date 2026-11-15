# Photograph a page, find a container, tap it

Verified 2026-11-15 against a served test page (`demo/tap/index.html`). Works for anything the
browser renders — a served file, a local preview, a public site.

**The one rule: each `shot`/`browse` call loads the page fresh.** State does not carry between
calls, so the tap and the capture must happen in the *same* call's `eval`.

## 1. See it, and list what can be tapped

```
browse {"url":"http://127.0.0.1:PORT/",
        "eval":"return JSON.stringify([...document.querySelectorAll('button, a, .card, [role=button]')].slice(0,12).map(e=>{const b=e.getBoundingClientRect();return {id:e.id, tag:e.tagName, text:e.innerText.slice(0,40), box:[Math.round(b.x),Math.round(b.y),Math.round(b.width),Math.round(b.height)]}}))"}
```

The result is under `## eval` in the saved page file. `box` is what a coordinate tap needs.

## 2. Tap by selector, and capture the result

```
shot {"url":"http://127.0.0.1:PORT/",
      "eval":"const t=document.querySelector('#card-b'); t.scrollIntoView({block:'center'}); t.click(); await new Promise(r=>setTimeout(r,200));"}
```

`shot` photographs after the eval runs, so the image shows the tapped state. To get a value back
instead of a picture, use the same eval with `browse`.

## 3. Verify the tap did something

Return the state before and after in one eval — a tap that silently fails otherwise looks like
a tap that worked:

```
browse {"url":"http://127.0.0.1:PORT/",
        "eval":"const el=document.getElementById('status'); const before=el.innerText; document.querySelector('#card-b').click(); await new Promise(r=>setTimeout(r,300)); return JSON.stringify({before, after:el.innerText, changed:before!==el.innerText})"}
```

## 4. Tap by coordinates (when there is no selector)

```
eval: "const el=document.elementFromPoint(166,242); el.click(); await new Promise(r=>setTimeout(r,200));"
```

## 5. Native Android UI

`app_rpc` is the native equivalent, and needs an approved debug build of the app:

| method | use |
|---|---|
| `ui.tree` | the container/node tree with bounds |
| `ui.tap` | tap by coordinates from the tree |
| `ui.input`, `ui.scroll`, `ui.back` | text entry, scrolling, back |
| `ui.shot` | screenshot (binary, lands in `agent/rpc/`) |

Check with `app_rpc {"action":"list"}`; if it says no apps are discovered, generate the debug
bridge with `app_rpc {"action":"template","app":"<package>"}`, install that debug APK, and
approve the app in Settings → On-device app debugging. Without that, a WebView page inside the
app can only be driven through its URL in `browse`/`shot`, not through native widgets.

## 6. Things that bite

- Below the fold: call `scrollIntoView` before clicking, or the click lands on nothing.
- A tap that navigates replaces the document — read the result from the *next* call, not this one.
- Pages that fetch after load may render a loading state in `browse` text; a `shot` shows the truth.
- `shot {full:true}` can return a shorter image than the viewport (bug-list #14) — use the plain
  capture when you need to see what is on screen.
