# Binary files as GitHub release assets (keep the repo slim)

Screenshots, MP4s, PDFs and deck exports do not belong in Git. Attach them to a
release instead: they live outside the tree, cost nothing against repo size,
survive forever, and get a stable download URL.

Repo: `muffyuniverse/testing` · secret `github-muffyuniverse` (works on
`api.github.com` and `uploads.github.com`; scopes `repo`, `workflow`).

The project `.gitignore` already ignores `*.png`, `*.jpg`, `*.jpeg`, so those
never reach a commit — this is where they go instead.

## 1. Create (or find) the release

    GET https://api.github.com/repos/muffyuniverse/testing/releases
    Authorization: Bearer {{secret:github-muffyuniverse}}
    Accept: application/vnd.github+json
    User-Agent: pechka-agent

Create one — `tag_name` may be a tag that does not exist yet; GitHub creates it
at `target_commitish`:

    POST https://api.github.com/repos/muffyuniverse/testing/releases
    Authorization: Bearer {{secret:github-muffyuniverse}}
    Accept: application/vnd.github+json
    User-Agent: pechka-agent
    Content-Type: application/json

    {"tag_name":"assets-v1","target_commitish":"main","name":"Assets v1",
     "body":"Screenshots and clips, kept out of the tree.","draft":false,"prerelease":false}

The response carries `id` and `upload_url`.

## 2. Upload one asset per call

    POST https://uploads.github.com/repos/muffyuniverse/testing/releases/<id>/assets?name=deck-intro.mp4
    Authorization: Bearer {{secret:github-muffyuniverse}}
    Accept: application/vnd.github+json
    User-Agent: pechka-agent
    Content-Type: video/mp4

Use `body_file` for the binary — never inline bytes in the request text:

    http {"body_file":"agent/browser/clip-1794745328834.mp4", "request":"..."}

MIME types worth using: `image/png`, `image/jpeg`, `video/mp4`,
`application/pdf`, `application/zip`, `text/html; charset=utf-8`.

`name` is the filename people see; it must be unique per release. Re-uploading
the same name fails — delete the old asset first:

    DELETE https://api.github.com/repos/muffyuniverse/testing/releases/assets/<asset_id>

## 3. Link it

The asset URL in the response (`browser_download_url`) is permanent and can go in
README, an issue, or a chat message. It is a download link, not an image in the
tree — for an image shown inside a Markdown file, the same URL works in `![](…)`.

## Limits and alternatives

- 2 GB per asset, no practical total limit; not counted in repo size.
- Releases are versioned snapshots. Something that changes constantly is better
  served by Actions artifacts (90-day retention) or a Pages site.
- Git LFS is the other option, but it puts files back in the tree and burns a
  separate storage/bandwidth quota (free tier 1 GB each). Release assets do not.
- Deleting a release deletes its assets. Keep the tag if you want the URL.

## 4. Round-trip test: runner fetches the assets and uses them as app assets

`assets-v2` is the current media release (three files, ~570 kB). The workflow
`.github/workflows/assets-roundtrip.yml` proves the inbound half of the rule: a runner
downloads a release's assets and puts them where the app serves them from.

    POST https://api.github.com/repos/muffyuniverse/testing/actions/workflows/assets-roundtrip.yml/dispatches
    Authorization: Bearer {{secret:github-muffyuniverse}}
    Content-Type: application/json

    {"ref":"main","inputs":{"tag":"assets-v2","dest":"android/app/src/main/assets/www/media"}}

What it does, in about ten seconds:

1. `gh release download <tag>` — every asset, no pattern.
2. Rejects anything that is not a binary (an HTML error page fails the job).
3. Copies them to `dest`, writes `media/index.json` (`name`, `path`, `bytes`, `mime`,
   `sha256` per file) so the WebView can list them without hitting the network.
4. Asserts each `media/<name>` is URL-safe, the bytes on disk match the manifest, and
   the hashes still match.
5. Uploads the whole `media/` folder as the artifact `app-assets-media`.

To check a run without trusting the green tick, pull the artifact and compare its
`index.json` hashes with the release's own digests:

    GET https://api.github.com/repos/muffyuniverse/testing/releases/tags/assets-v2   -> assets[].digest
    GET https://api.github.com/repos/muffyuniverse/testing/actions/artifacts/<id>/zip

A pass means the bytes that reached the app are the bytes that were published.

### Making a new media release (what `assets-v2` was)

1. Download the old assets by id, so the bytes are known:

       GET https://api.github.com/repos/muffyuniverse/testing/releases/assets/<asset_id>
       Accept: application/octet-stream

2. `POST /releases` with `{"tag_name":"assets-v3","target_commitish":"main",...}` — GitHub
   creates the tag at that commit. Note the `id` it returns.
3. Upload each file with `body_file` to
   `https://uploads.github.com/repos/muffyuniverse/testing/releases/<id>/assets?name=<name>`.
4. Dispatch the round-trip workflow against the new tag.
