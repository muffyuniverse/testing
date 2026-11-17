# Self Debug — an app I can build and update without you

`dev.pechka.selfdebug` is a small Android app that carries the Pechka debug RPC provider. It is
built by GitHub Actions, published as a release asset, and it can update itself from a JSON manifest
that the same workflow rewrites on every build.

## What you do once

1. Install the newest APK:
   `https://github.com/muffyuniverse/testing/releases/latest/download/selfdebug.apk`
   (first build: `…/releases/download/debug-3/selfdebug.apk`, 1,544,159 bytes,
   sha256 `2331a7a7f9de9f86ecce71dbc2e076b548dacea73e787410662fb6ce3806688e`)
2. Open it once (it will ask to check for updates; that is fine).
3. In Pechka, approve `dev.pechka.selfdebug` and pin this signing certificate SHA-256:

   ```
   FB:5B:25:39:07:5C:9B:97:0D:9C:A4:5D:BF:1F:CD:2C:DF:BD:63:3E:06:03:31:0C:3C:9C:E9:E5:9E:38:EC:36
   ```

   (The same value is printed by the workflow in the "Signing keystore" step and stored in
   `app/update/version.json` as `certSha256`.)
4. Allow "install unknown apps" for Self Debug when it asks (it opens the right settings screen).

After that I can build, publish and ask the app to install a new version through `app_rpc` without
touching anything here.

## What happens on an update

1. A change under `app/**` (or a manual `workflow_dispatch`) starts the build.
2. The workflow signs with the keystore in `keystore/keystore.b64` (generated once, committed), makes
   a release `debug-<run>`, and commits `app/update/version.json`:
   ```json
   {"versionCode": 7, "versionName": "1.0.7", "apkUrl": "…/debug-7/selfdebug.apk", "sha256": "…"}
   ```
3. The app fetches that manifest from raw.githubusercontent.com on launch (and on resume), compares
   `versionCode` with its own, downloads the APK, checks the sha256, and opens the system installer.

Android always shows the install confirmation — that one tap is the only thing the OS will not let
an app skip. Everything before it (check, download, verify, launch) is automatic.

## RPC methods

| method | what it does |
|---|---|
| `app.version` | version name/code, last status, manifest URL, whether installs are allowed |
| `app.check_update` | fetch the manifest, compare, optionally `{install:true}` |
| `app.install_update` | download the newest APK and open the installer |
| `app.install_downloaded` | open the installer for an APK already on disk |
| `app.set_manifest_url` | point the updater at another manifest (`{url}`) |

Plus the generated provider's own `rpc.methods`, `app.info`, `ui.tree`, `ui.tap`, `ui.input`,
`ui.scroll`, `ui.back`, `ui.shot`, `ui.record*`, `debug.logcat`, `debug.crash_report`.

## The one caveat: the signing key is in a public repo

The repository is public, so `keystore/keystore.b64` (PKCS#12, password `pechka-test-keystore`) is
readable by anyone. That key is what Android uses to decide whether an update is genuine. Anyone who
takes it can sign an APK that your phone would accept as an update to Self Debug — but they cannot
push it to you: the app only installs what the release manifest points at, and only repo writers can
change that. If this app ever matters beyond testing, move the keystore into an Actions secret
(`KS_B64`) and delete the committed copy.

## Rebuilding

```sh
gh workflow run android.yml          # manual
# or: commit anything under app/ and push
```

## Debugging the build from here

The agent cannot read Actions logs directly: the log zip contains ANSI control bytes, and the text
tools refuse binary. So the workflow publishes its own failure summary:

- `build.log` (full, plain console) is kept in the run workspace only.
- On failure the workflow strips ANSI, greps the interesting lines and commits `build-error.txt` to
  the repo. That file is plain text and readable with `read`.
- On success it commits `app/update/version.json` (with `sha256` and `certSha256`), which is the
  signal that a usable APK exists.

Polling those two raw URLs (`raw.githubusercontent.com/.../build-error.txt` and
`.../app/update/version.json`) is cheap and needs no API token.
