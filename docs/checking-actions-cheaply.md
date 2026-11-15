# Watching a GitHub Actions run without eating the context

A run object from the API is ~12 kB, and almost all of it is the same repository boilerplate.
Polling it with `direct: true` costs ~3 k tokens each time. Two cheaper ways:

## 1. Wait on the artifact

An artifact only exists once the job reaches the upload step, so it doubles as a
"finished" flag and is a single short object:

    GET https://api.github.com/repos/{owner}/{repo}/actions/runs/{run_id}/artifacts

`{"total_count":0,"artifacts":[]}` means still running. A run that failed before uploading
also leaves this empty, so on a real failure check the job.

## 2. Read the run list in slices

File the run list, then read the one region that matters — the `status` / `conclusion`
pair sits a few hundred characters in:

    http  GET .../actions/workflows/{workflow}.yml/runs?per_page=1   name: runs.json
    read  agent/http/runs.json  char_from: 300  char_count: 240

For the step-by-step view (which step is running, and whether install worked):

    http  GET .../actions/runs/{run_id}/jobs?per_page=1              name: jobs.json
    read  agent/http/jobs.json  from: 1  to: 1   # one line; slice it instead

The `steps` array gives each step's name, status and timestamps — enough to tell a slow
render from a stuck install.

## 3. Make the workflow write its own report

Logs from a runner can arrive as non-UTF-8 and then `read` refuses them. A workflow that
catches its own exception and writes `report.json` into the artifact makes the run
diagnosable from the artifact alone, with no log fetch at all. `scripts/blender/phone.py`
does this: geometry steps, render list, sizes, traceback.

## 4. Waiting: put the whole wait in the background

There is no sleep command, and a foreground `pipe` is cut off at 60 s. The workable
recipe is a `background` pipe that delays itself and then fetches the answer:

    background tool: pipe name: wait-run.txt
      steps:
        s1  http GET .../runs?per_page=1  (direct: true)      # find the newest run id
        38 x http GET https://httpbin.org/delay/10           # ~380 s of waiting
        s40 http GET .../runs/{{id}}/artifacts  (direct: true, vars id = s1 json
                 workflow_runs.0.id)

Intermediate steps stay out of the conversation, and the job wakes the agent when it
finishes. `httpbin.org/delay/N` accepts N up to 10 (httpbingo.org is the same service and
rejects anything longer with a 400).

## 5. If a real timer is wanted

`.github/workflows/timer.yml` sleeps on a runner: dispatch it with `{"seconds": "300"}`
(1-900) and poll the run. No third party, no cap, and it survives an app restart — unlike a
background job, which `env` reports as `unknown` after one.

Public delay endpoints are not an alternative: `httpbin.org/delay/N`, `httpbingo.org/delay/N`
and `/drip?duration=N` all reject N > 10 s, `deelay.me` is unreachable from this network and
`slowwly.robertomurray.co.uk` no longer resolves to the service. The MCP servers configured
here (Exa, Context7, DeepWiki, Hugging Face, MS Learn, Cloudflare docs) expose search and
documentation, not timers.

## 6. Mind the hourly API budget

The REST API here is 60 requests per hour, and an artifact download counts heavily. A
single run cycle — dispatch, find the id, poll, download — can use most of it. When it runs
out, `GET https://api.github.com/rate_limit` is free and says when it resets; `git push`
and `git pull` do not touch the API at all. Batch the API calls, and never poll the full
run object.
