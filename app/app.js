// Testing Factory — a single page that reads its own repository's public state.
// No build step, no dependencies, no token: everything here is an unauthenticated
// GitHub API read of a public repo.

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

async function get(path) {
  const res = await fetch(path, { headers: { Accept: "application/vnd.github+json" } });
  if (!res.ok) throw new Error(`${new URL(path, "https://api.github.com").pathname} → HTTP ${res.status}`);
  return res.json();
}

async function safe(path, fallback) {
  try {
    return await get(path);
  } catch {
    return fallback;
  }
}

function card(title, rows) {
  const body = rows
    .filter(Boolean)
    .map(([k, v]) => `<dt>${esc(k)}</dt><dd>${v}</dd>`)
    .join("");
  return `<section class="card"><h2>${esc(title)}</h2><dl>${body}</dl></section>`;
}

function runRow(run) {
  const state = run.conclusion || run.status || "unknown";
  const cls = state === "success" ? "pass" : state === "failure" ? "fail" : "pending";
  const label = `${esc(run.name || run.workflow_id)} <span class="${cls}">${esc(state)}</span>`;
  const link = `<a href="${esc(run.html_url)}" rel="noreferrer">${esc(shortSha(run.head_sha))}</a>`;
  return [label, `${link} · ${esc(when(run.created_at))}`];
}

async function render() {
  const sub = $("sub");
  const cards = $("cards");
  sub.classList.remove("bad");
  sub.textContent = "reading github.com…";

  try {
    const [repo, commits, runs, releases] = await Promise.all([
      get(API),
      get(`${API}/commits?per_page=1`),
      safe(`${API}/actions/runs?per_page=3`, { workflow_runs: [] }),
      safe(`${API}/releases?per_page=1`, []),
    ]);

    const head = commits[0];
    const latest = releases[0];

    cards.innerHTML = [
      card("repository", [
        ["name", `<a href="${esc(repo.html_url)}" rel="noreferrer">${esc(repo.full_name)}</a>`],
        ["branch", esc(repo.default_branch)],
        ["visibility", esc(repo.visibility)],
        ["license", esc(repo.license?.spdx_id || "none")],
        ["open issues", String(repo.open_issues_count ?? 0)],
      ]),
      head &&
        card("last commit", [
          ["message", esc((head.commit.message || "").split("\n")[0])],
          ["sha", `<code>${esc(shortSha(head.sha))}</code>`],
          ["author", esc(head.commit.author?.name || head.author?.login || "?")],
          ["when", esc(when(head.commit.author?.date))],
        ]),
      card("latest release", [
        ["tag", latest ? `<a href="${esc(latest.html_url)}" rel="noreferrer">${esc(latest.tag_name)}</a>` : "none yet"],
        ["assets", latest ? String((latest.assets || []).length) : "0"],
        ["published", latest ? esc(when(latest.published_at)) : "—"],
      ]),
      card(
        "workflows",
        runs.workflow_runs.length ? runs.workflow_runs.map(runRow) : [["runs", "none yet"]]
      ),
    ].join("");

    sub.textContent = `live from github.com/${OWNER}/${REPO}`;
    $("loaded").textContent = `loaded ${new Date().toLocaleTimeString()}`;
  } catch (err) {
    sub.classList.add("bad");
    sub.textContent = err.message;
  }
}

async function stampBuild() {
  const built = $("built");
  try {
    const res = await fetch("build.json", { cache: "no-store" });
    if (!res.ok) throw new Error("no build.json");
    const meta = await res.json();
    built.textContent = `${meta.tag || "?"} · ${shortSha(meta.sha)} · built ${when(meta.built_at)}`;
  } catch {
    built.textContent = "dev build (no release asset)";
  }
}

$("refresh").addEventListener("click", render);
render();
stampBuild();
