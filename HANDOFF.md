# Handoff — POCO X8 Pro asset

Written before reinstalling the app. Read this first; it is the state of the work, not a
plan.

## Where things stand

- Repo `muffyuniverse/testing`, branch `main`, last commit `68f91cc` (pushed).
- The asset is **`scripts/blender/phone.py`** — one file: geometry, materials, studio
  lights, cameras, stills, turntable, glTF/GLB export, `.blend` save, `report.json`.
  It is run headless on a GitHub runner, not locally (there is no local Blender).
- **`.github/workflows/blender-phone.yml`** renders it: `workflow_dispatch` inputs
  `samples` (default 64) and `release` (`yes` publishes a release with every render).
  Artifact name `poco-x8-pro-render`, kept 90 days.
- **`.github/workflows/timer.yml`** is a runner-side sleep (1–900 s), for waiting without a
  public delay service.
- **`docs/checking-actions-cheaply.md`** holds the loop: how to dispatch, poll and fetch
  without burning turns, and what the app's own limits are.
- Verified locally: `phone-render-2/`, `phone-render-3-2/`, `phone-render-6/` (unpacked
  artifacts, not in Git). `phone-render-6/` is the newest: 5 stills, glTF, GLB, `.blend`,
  `report.json`. **No turntable in it** — see the bug below.

## The bug that cost the last render

`turntable()` never ran, and the run still reported `ok: true`. Cause: the workflow passed
`-- renders 64 anim`, but **Blender drops the `--` separator from `sys.argv`**, so
`"--" in sys.argv` was false, every setting silently fell back to its default, and the
turntable was never requested. Fixed in `68f91cc` by reading the environment instead
(`PHONE_OUT`, `PHONE_SAMPLES`, `PHONE_ANIM`); the workflow now sets those and passes no
positional arguments. **This fix has not been rendered yet** — the next dispatch is the
first test of it.

That run also failed at the release step because it named `renders/turntable.mp4`, which
did not exist. The step now globs `renders/*` with `nullglob`, so a missing file cannot
fail a release.

## What the last render looks like (and what to fix next)

From `phone-render-6/`:

1. **Bright white outline around the display.** The front-glass slab's bevel rim catches
   the key light. `rounded_slab("front-glass", ...)` (~line 283) uses `rim=0.3*MM`;
   drop it to ~`0.1*MM` and/or give the front glass a matte rim material.
2. **Fingerprint capsule too loud** (~line 302): 9 × 24 mm with `glow_mat` emission
   strength 1.6 reads as a sticker. Cut to ~7 × 18 mm, strength ~0.5.
3. **POCO wordmark almost invisible** (~line 344): `logo_mat` is `(0.16, 0.18, 0.19)`
   at metallic 0.7, which vanishes on the sage body. Darken to ~`(0.05, 0.06, 0.06)`,
   roughness ~0.45.
4. **Camera plateau is pale ivory** against the body: `plateau_mat` is `ice` with coat
   0.70. Darken to ~0.85 × `ice`, roughness ~0.22, so it reads as one piece with the back.
5. **Fine speckle on the back glass** — the procedural bump is too strong at render size;
   halve it.
6. **Wallpaper band too broad and frosted** (`wallpaper()`, emission 0.55, wide stops):
   narrow the lit edge and drop emission to ~0.35.

## Next steps, in order

1. Dispatch `blender-phone.yml` with `samples: 64`, `release: yes`.
2. Find the new run id, poll `status`, then fetch
   `https://api.github.com/repos/muffyuniverse/testing/actions/runs/<id>/artifacts`
   and download `artifacts/<artifact id>/zip`.
3. Unpack, check `report.json` for a `turntable` key — that proves the env-var fix works.
4. Look at the stills, apply fixes 1–6 above, re-render, then hand over the release link.
5. `*.png` and `*.zip` are ignored by Git; `.blend`/`.glb`/`.gltf`/`.bin` are untracked and
   deliberately kept out of the repo. The deliverable is the release, not a commit.

## Traps worth remembering

- **Always send the token when probing limits.** `GET /rate_limit` without
  `Authorization` reports the anonymous bucket — **60/hour, shared per IP**, which reads
  like "GitHub is throttling me" when the authenticated bucket has **5000/hour** and is
  untouched. Every other call here did carry the token; only the probes did not.
- **Public delay services are not a timer.** `httpbin.org/delay/N` and
  `httpbingo.org/delay/N` reject N > 10 s; `/drip?duration=N` is capped the same way;
  `deelay.me` does not connect from this network; `slowwly.robertomurray.co.uk` is a
  parked domain. The MCP servers configured here (Exa, Context7, DeepWiki, Hugging Face,
  MS Learn, Cloudflare docs) are search and documentation, not timers.
- **Background jobs do not survive an app restart.** After one, `env` reports them
  `unknown` while their result file still says `running` — so a file saying `running`
  proves nothing. Prefer polling, or `timer.yml` as the clock.
- **Big responses are the crash risk.** A full `runs?per_page=1` dump is ~12 kB and a
  release list is bigger; the app closed once during a 7.5 MB artifact download. Use
  `direct: true` with small answers, read slices (`char_from`/`char_count`), and never
  print a whole API document into the conversation.

## Requested from the app: a wait tool

Please add a first-class way to spend wall-clock time, because every web substitute is
unreliable:

- `wait(seconds)` — a foreground tool that sleeps up to the foreground limit, returning
  the seconds actually waited. No HTTP, no third party, no dependency on a public
  service staying alive.
- Optional `until` form: `wait_until(time)` or `wait_for(job)` — block until a background
  job's file stops saying `running`, or until a timestamp, and return its status.
- Background jobs would then need no delay loop at all: dispatch, `wait_for(job)`, read.

Until then: `timer.yml` (runner sleeps 1–900 s) is the dependable clock, and
`httpbin.org/delay/10` in a background pipe is the cheap approximation.
