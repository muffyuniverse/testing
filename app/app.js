// Testing Factory — one page, no dependencies, no token.
//
// Three sources, in this order:
//   1. build.json — baked by the ship workflow, delivered inside the release asset, and mirrored
//      to the `site` branch. Always present on a real deployment, so the page renders even where
//      api.github.com is blocked or rate-limited.
//   2. the public GitHub API — refreshes the same cards with live numbers.
//   3. nothing — the page still renders, and says what failed.
//
// Every fetch has a hard timeout: a page that waits forever on a blocked host never goes idle,
// which also means no screenshot of it can ever be taken.

const OWNER = "muffyuniverse";
const REPO = "testing";
const API = `https://api.github.com/repos/${OWNER}/${REPO}`;
const TIMEOUT_MS = 6000;

const $ = (id) => document.getElementById(id);

const esc = (value) =>
  String(value ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

const shortSha = (sha) => (sha ? String(sha).slice(0, 7) : "?");

const when = (iso) => {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "—";
  return d.toLocaleString(undefined, { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
};

const stateOf = (run) => run.conclusion || run.status || "unknown";

async function fetchWithTimeout(url, options = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } catch (err) {
    if (err.name === "AbortError") throw new Error(`no answer in ${TIMEOUT_MS / 1000}s`);
    throw err;
  } finally {
    clearTimeout(timer);
  }
}

async function getJson(path) {
  const res = await fetchWithTimeout(path, { headers: { Accept: "application/vnd.github+json" }, cache: "no-store" });
  if (!res.ok) throw new Error(`${new URL(path, "https://api.github.com").pathname} → HTTP ${res.status}`);
  return res.json();
}

async function readBuild() {
  try {
    const res = await fetchWithTimeout("build.json", { cache: "no-store" });
    if (!res.ok) return null;
    return await res.json();
  } catch {
    return null;
  }
}

function card(title, rows) {
  const body = rows
    .filter(Boolean)
    .map(([k, v]) => `<dt>${esc(k)}</dt><dd>${v}</dd>`)
    .join("");
  return `<section class="card"><h2>${esc(title)}</h2><dl>${body}</dl></section>`;
}

function paint(view) {
  const sub = $("sub");
  sub.classList.remove("bad");
  sub.textContent = view.label;
  if (view.labelClass) sub.classList.add(view.labelClass);

  const r = view.repo || {};
  const head = view.head || {};
  const rel = view.release || {};

  $("cards").innerHTML = [
    card("repository", [
      ["name", r.full_name ? `<a href="${esc(r.html_url)}" rel="noreferrer">${esc(r.full_name)}</a>` : "—"],
      ["branch", esc(r.default_branch || "—")],
      ["visibility", esc(r.visibility || "—")],
      ["license", esc(r.license || "none")],
      ["open issues", String(r.open_issues ?? 0)],
    ]),
    head.sha &&
      card("last commit", [
        ["message", esc(head.message || "")],
        ["sha", `<code>${esc(shortSha(head.sha))}</code>`],
        ["author", esc(head.author || "?")],
        ["when", esc(when(head.date))],
      ]),
    card("release", [
      ["tag", rel.tag ? `<a href="${esc(rel.url)}" rel="noreferrer">${esc(rel.tag)}</a>` : "none yet"],
      ["assets", String(rel.assets ?? 0)],
      ["published", esc(when(rel.published_at))],
    ]),
    card(
      "workflows",
      (view.runs || []).length
        ? view.runs.map((run) => {
            const s = stateOf(run);
            const cls = s === "success" ? "pass" : s === "failure" ? "fail" : "pending";
            const link = run.url
              ? `<a href="${esc(run.url)}" rel="noreferrer">${esc(shortSha(run.sha))}</a>`
              : esc(shortSha(run.sha));
            return [`${esc(run.name)} <span class="${cls}">${esc(s)}</span>`, `${link} · ${esc(when(run.created_at))}`];
          })
        : [["runs", "none yet"]]
    ),
  ].join("");

  $("built").textContent = view.build
    ? `${view.build.tag || "?"} · ${shortSha(view.build.sha)} · ${when(view.build.built_at)}`
    : "dev build (no release asset)";
  $("loaded").textContent = `read ${new Date().toLocaleTimeString()}`;
}

async function main() {
  const build = await readBuild();
  const label = build?.tag ? `snapshot from ${build.tag}` : "snapshot from this page's own files";

  if (build && build.snapshot) {
    paint({ ...build.snapshot, label: `${label} — live check next…`, build });
  }

  try {
    const [repo, commits, releases, runs] = await Promise.all([
      getJson(API),
      getJson(`${API}/commits?per_page=1`),
      getJson(`${API}/releases?per_page=1`).catch(() => []),
      getJson(`${API}/actions/runs?per_page=3`).catch(() => ({ workflow_runs: [] })),
    ]);
    const head = commits[0] || {};
    const rel = releases[0] || {};
    paint({
      repo: {
        full_name: repo.full_name,
        html_url: repo.html_url,
        default_branch: repo.default_branch,
        visibility: repo.visibility,
        license: repo.license?.spdx_id,
        open_issues: repo.open_issues_count,
      },
      head: {
        message: (head.commit?.message || "").split("\n")[0],
        sha: head.sha,
        author: head.commit?.author?.name || head.author?.login,
        date: head.commit?.author?.date,
      },
      release: { tag: rel.tag_name, url: rel.html_url, assets: (rel.assets || []).length, published_at: rel.published_at },
      runs: (runs.workflow_runs || []).map((run) => ({
        name: run.name,
        conclusion: run.conclusion,
        status: run.status,
        sha: run.head_sha,
        created_at: run.created_at,
        url: run.html_url,
      })),
      label: `live from github.com/${OWNER}/${REPO}`,
      build,
    });
  } catch (err) {
    const reason = `live check failed: ${err.message}`;
    if (build?.snapshot) {
      // A snapshot is the normal path on a rate-limited or offline network. Say so quietly:
      // the page has real data, so a red failure banner would be misleading.
      paint({ ...build.snapshot, label: `${label} · live unavailable`, build });
    } else {
      paint({ label: `${reason}, and no build.json next to this page`, labelClass: "bad", build: null });
    }
  }
}

$("refresh").addEventListener("click", main);
main();
