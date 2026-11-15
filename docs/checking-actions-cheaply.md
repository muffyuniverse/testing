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

## 4. What cannot be done

There is no sleep command here, so a poll loop is a series of turns. `browse` with a
JavaScript `eval` that polls times out after ~8 s, so it cannot block on a long job either.
Keep the polls small and do something useful between them.
