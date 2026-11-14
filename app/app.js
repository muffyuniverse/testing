// Testing Factory — one page, no dependencies, no token.
//
// Two sources, in this order:
//   1. build.json — baked by the ship workflow and delivered inside the release asset,
//      so it always renders, even on a network where api.github.com answers 403.
//   2. the public GitHub API — used to refresh the same cards with live numbers.
// If the live call fails, the snapshot stays on screen and says so.

const OWNER = "muffyuniverse";
const REPO = "testing";
const API = `https://api.github.com/repos/${OWNER}/${REPO}`;

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

async function getJson(path) {
  const res = await fetch(path, { headers: { Accept: "application/vnd.github+json" }, cache: "no-store" });
  if (!res.ok) throw new Error(`${new URL(path, "https://api.github.com").pathname} → HTTP ${res.status}`);
  return res.json();
}

async function readBuild() {
  try {
    const res = await fetch("build.json", { cache: "no-store" });
    if (!res.ok) return null;
    return await res.json();
  } catch {
    return null;
  }
}

async function main() {
  const build = await readBuild();

  if (build && build.snapshot) {
    paint({
      ...build.snapshot,
      label: `snapshot baked into ${build.tag || "the asset"} — live check next…`,
      build,
    });
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
    if (!build) {
      paint({ label: `${err.message} — and no build.json next to this page`, labelClass: "bad", build: null });
    } else {
      paint({ ...build.snapshot, label: `snapshot only — live check failed: ${err.message}`, labelClass: "bad", build });
    }
  }
}

$("refresh").addEventListener("click", main);
main();
