# Client decks

Two self-contained HTML presentations you can open on any phone or laptop and walk a
prospect through. No build step, no PowerPoint, no font downloads.

| File | Deck | Slides |
|---|---|---|
| `index.html` | hub page — links to both | — |
| `chatbot.html` | AI assistant for a support team | 11 |
| `ozon.html` | Ozon integration + operations dashboard | 12 |

Both share `assets/deck.css` and `assets/deck.js`.

---

## Presenting

Open `index.html` (or either deck) in a browser. Then:

| Key | Does |
|---|---|
| `→` `↓` `Space` | next slide |
| `←` `↑` | previous slide |
| swipe left / right | same, on a phone |
| `Home` / `End` | first / last slide |
| `L` | flip English ⇄ Russian |
| `O` | slide map — jump anywhere |
| `N` | speaker notes for the current slide |
| `F` | fullscreen |
| `P` | print — "Save as PDF" gives you a handout |

The slide number lives in the URL (`ozon.html#7`), so you can bookmark a specific slide
or send someone a link straight to the pricing page.

Language is remembered per browser. A Russian-locale browser opens in Russian.

---

## Edit these before you send anything

Both decks are written for a generic contractor. Replace, at minimum:

1. **Your name and contact** — the last line of each cover slide, the `‹Your name›`
   placeholder. Search for `‹Your name›` and `‹Ваше имя›`.
2. **The prices** — slide "Packages" in both decks. They are placeholders, not advice.
3. **The numbers** — every `data-count` figure is illustrative. Either replace it with a
   real client result or delete the slide; a prospect who asks "where did 38% come from"
   and gets no answer is worse than a deck with one slide fewer.
4. **The sector wording** — `ozon.html` says "Ozon" everywhere on purpose. If you pitch a
   Wildberries or Yandex Market seller, swap the marketplace name and the endpoint names
   (`/v3/product/info/stocks`, `/v3/posting/fbs/list`) for that platform's.

---

## Editing a slide

Every slide is one `<section class="slide">`. Text is in paired attributes:

```html
<p data-en="English text" data-ru="Русский текст"></p>
```

The script copies whichever matches the current language into the element. Leave a
`data-ru` off and Russian readers see the English — fine for product names, not for prose.

A few elements also accept `data-html` if you need a `<b>` or `<br>` inside.

Adding a slide: copy a `<section>` block, give it `data-title-en` / `data-title-ru` (those
titles are what the `O` map shows), and put it where you want it. Slide count, the
progress bar and the map all derive from the number of sections — nothing to update.

Removing a slide: delete the whole `<section>`. Same deal.

### The bits that are not plain text

- `<span class="kin">` — a line that rises into place on entry.
- `<div class="reveal" style="--i:4">` — fades up; `--i` is the stagger index. Keep them
  in ascending order or the slide looks drunk.
- `<div class="stat"><span class="num" data-count="38" data-suffix="%">` — counts up from
  zero when the slide opens.
- `<svg class="flow">` — the architecture diagrams. The text inside is `data-en`/`data-ru`
  like everything else; the boxes are drawn to a fixed `viewBox`, so if you add a node,
  widen the `viewBox` and move the `<path>` wires with it.
- `<aside class="notes">` — speaker notes, hidden on screen, shown by `N`.

---

## Publishing

The decks are static files, so GitHub Pages serves them directly. If Pages is on for this
repository, they live at:

```
https://<user>.github.io/<repo>/decks/
```

That URL opens the hub. Send a prospect the deck URL, not a file — nothing to download,
and it works on their phone.

---

## One caution

`ozon.html` and `chatbot.html` describe systems that do not exist yet. They are proposals.
Keep the "typical results" figures either clearly illustrative or backed by a real
engagement — the deck reads as a case study otherwise.
