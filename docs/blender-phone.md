# POCO X8 Pro — a Blender asset

A phone modelled in Blender from the official product shots, rendered headless on a
GitHub runner, and kept as one text file so the repo stays binary-free.

## Reference

Official press images of the POCO X8 Pro (GSMArena, `xiaomi_poco_x8_pro_5g-pictures-14506`):

| what the photos show | what the model does |
| --- | --- |
| flat slab, "ice blue", glossy back | 163 × 76 × 8 mm rounded box, glass back a hair proud of the frame |
| two large circular camera rings, stacked vertically, on a raised rounded plateau, top-left of the back | plateau 28 × 57 mm, two 11 mm rings with domed dark glass |
| small round flash beside the top lens, tiny sensor dot below it | emissive flash disc, dark sensor dot |
| punch-hole front camera, thin even bezels | 1.35 mm hole in a black front glass inset from the frame |
| dark brushed-metal wallpaper with a diagonal split and a glowing capsule | procedural gradient wallpaper + emissive capsule geometry |
| metallic frame, volume rocker and power button on the right, red accent on power | beveled buttons; red inset strip on the power button |
| USB-C, speaker holes, mic at the bottom; mic and IR dot on top | port, six speaker holes, mic, IR |

## The asset

`scripts/blender/phone.py` builds everything procedurally:

- geometry from `bmesh`-free primitives + bevel modifiers, so it scales by editing numbers
- a Principled-BSDF material per part (ice-blue metal, glass back with coat, lens rims,
  dark lens glass with transmission, emissive flash and screen capsule)
- a four-light studio (key, fill, rim, kick), dark floor, AgX view transform
- five cameras: front hero, back hero, flat front, camera-module detail, edge

It writes `renders/` with the PNGs, `poco-x8-pro.blend`, `poco-x8-pro.glb` and a
`report.json` listing each render, its camera, lens and file size — so the run can be
audited from the artifact instead of the log.

## Running it

`.github/workflows/blender-phone.yml`, manual dispatch, `samples` input (default 48).
The runner has no GPU, so Cycles runs on CPU with OpenImageDenoise.

    blender -b --factory-startup -noaudio -P scripts/blender/phone.py -- renders 48

Locally the same command works with Blender 4.x; the script tolerates 3.x material input
names and the pre-4.1 auto-smooth API.

## Why a workflow and not the tree

Renders, `.blend` and `.glb` are build output: they belong in a release or an artifact,
not in Git. Same rule as the media assets in `recipes/release-assets.md`.
