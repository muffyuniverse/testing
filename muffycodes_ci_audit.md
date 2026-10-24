# Muffy Codes — CI/CD audit

> Asked of `moonshotai/kimi-k3.1-pro` through OpenRouter, one request, reasoning
> effort `max`, no output cap set below the model's own.
>
> | | |
> |---|---|
> | input | 58,738 tokens (the whole pipeline: workflow, 15 tools, build files, docs) |
> | output | 67,122 tokens, of which 53,453 were reasoning |
> | wall clock | 19m 11s |
> | cost | $0.78 |
>
> The audit is reproduced verbatim below. My verification of its claims, and
> what I did about them, is in the second half — read that before acting on any
> of it, because two of its claims were checked and one of its suggestions was
> rejected after measurement.

---

## The audit, as it came back

## Verdict

**[PROVEN]** The strong parts are specific and real: test selection no longer perturbs the configuration-cache command line; `doNotTrackState` prevents cached test verdicts; the release build really does fail without signing material; `apksigner` and `zipalign` are both checked; `deliver` now handles a skipped `lint` explicitly; fork PRs do not receive signing secrets; and third-party actions are SHA-pinned.

**[PROVEN]** The fragile parts are the boundaries between jobs and the absence of postconditions. The workflow proves that jobs returned success, not that the expected tests executed, that the expected APK was delivered, or that a published release points at the commit that was built.

**[PROVEN] The most dangerous defect in the YAML is the optimistic signed handoff.** `build-release` can upload `release-apk` while the suite is still running—or after reading only a successful subset of the matrix—and that signed, installable artifact remains downloadable when a sibling shard later fails. `deliver` is correctly gated, but the raw artifact already exists. That directly violates N3.

**[PROVEN]** The closest second is quieter: `build-release` and `lint` compare against `HEAD~1`, not the push’s old branch head. A direct push containing an app commit followed by a documentation commit is classified as documentation-only: lint and the APK are skipped, and the run can be green without the delivery the push required.

---

# Q1 — The critical path, with arithmetic

## The 58-second, one-class pull request

**[ARITHMETIC]** The two measurements that can be combined without inventing precision are:

- Total run: **58s**
- Gradle invocation: **33s**
- Therefore everything outside that Gradle invocation: **58 − 33 = 25s**

The documented essential-work breakdown sums to:

| Component | Seconds |
|---|---:|
| Job setup, checkout, workflow/selector checks | 8 |
| Gradle setup and cache restore | 6 |
| Robolectric runtime restore | 3 |
| Gradle configuration and startup | 13 |
| Test compilation | 10 |
| Test execution | 8 |
| Teardown | 5 |
| **Total** | **53** |

**[ARITHMETIC]** That leaves **58 − 53 = 5s** of queueing, variance, profiling, reporting, or unallocated work. It is below the measured **±6s** run-to-run noise. The 18.1s test-task and 13.7s compilation profile comes from a different run and cannot be inserted into the 33s Gradle measurement without double-counting or mixing configurations.

**[PROVEN]** The defensible warm-cache floor for the current toolchain is therefore **about 53s**, not 20s or 30s. A lower number may be achievable, but nothing in the artifacts measures a stripped-down runner that proves it.

**[ARITHMETIC]** There is no worthwhile PR deletion left for speed: even recovering the entire 5s discrepancy would be unmeasurable against ±6s variance. Removing `scaling-report.py`, `build-times.py`, or `--profile` might save fractions of a second, but would trade away instrumentation for a result that cannot be distinguished from noise.

## The 146-second `main` run

**[ARITHMETIC]** The release build is **128–129s**. Taking the midpoint:

- Total run: **146s**
- Release build: **128.5s**
- Everything else on the critical path: **146 − 128.5 = 17.5s**

That 17.5s includes startup, checkout/toolchain/cache work not included in the quoted build figure, signature checks, artifact transfer, delivery, attestation, and publication.

**[PROVEN]** Tests and lint are not the current critical path. `lintRelease` is approximately **98s**, beside a **128–129s** build; deleting lint would weaken the gate without establishing a 48s saving. The same applies to the test suite while `build-release` remains the longest job.

**[ARITHMETIC]** If the 128.5s build is untouched, no amount of deleting reports or ancillary steps can save more than the surrounding **17.5s**. A realistic floor is roughly **136–140s**, not a minute: approximately 8–10s is potentially recoverable from the inter-job APK round trip and delivery setup, while some checkout, verification, and publication work remains. That range is **[PRIOR]**, because the artifacts do not provide a current step-level timeline for those 17.5s.

## What I would delete or rearrange, in order

1. **Measure and remove useless release build-cache restoration, without removing dependency restoration.**  
   **[PROVEN]** `build-release` restores Gradle state read-only, yet a repeat build reports **40 executed, 0 from cache**. The YAML elsewhere records approximately **13s** of cache restoration in this area.  
   **[PRIOR]** The recoverable amount is somewhere between zero and 13s—not automatically 13s—because the Gradle distribution and dependencies still need to be restored.  
   **Change:** compare the current setup with a setup that retains wrapper/dependency state but excludes release task-output caches.  
   **Measure:** three repeated builds of the same commit; require a total improvement greater than 6s and no new dependency downloads.  
   **Risk:** a colder dependency cache or slower compilation; stop if setup savings are offset inside Gradle.

2. **If another 8–10s is wanted, challenge D10’s narrow interpretation.**  
   **[PROVEN]** D10 rejects merging delivery into `build-release` *through `needs:`*, which would indeed delay the build. It does not rule out building optimistically in the same job and performing delivery later in that job, after a terminal gate check.  
   **[ARITHMETIC]** Removing the APK upload/download and second delivery setup has a previously measured basis of **8–10s**, putting `main` near **136–138s**, provided the build still starts at approximately 3s.  
   **Cost:** the API gate remains, and the enlarged job needs publishing permissions.  
   **Measure:** build start time, first-test start time, and total run time on three main-like runs. If the build starts later or the saving is below 6s, abandon it.

3. **Do not touch the PR reports for speed.**  
   **[ARITHMETIC]** The maximum unexplained PR gap is 5s, below the noise floor. Spend effort on Q2’s correctness defects instead.

4. **Do not name a release task for deletion until its duration is supplied.**  
   **[PROVEN]** `mergeExtDexRelease`, `optimizeReleaseResources`, `compileReleaseKotlin`, and `mergeReleaseResources` are named suspects, but no per-task release durations are given. Disabling resource optimization or changing dexing without those durations and an APK-size comparison would be guessing.

---

# Q2 — Correctness: how this pipeline can lie

## A. Green after zero or incomplete tests

### 1. There is still no execution postcondition

**[PROVEN]** `pick-tests.sh --self-test` proves properties of the shell selector. It does not inspect a single JUnit result. `scaling-report.py` explicitly returns success when there are no results:

> “No test results to read; nothing to say about scaling.”

Thus a successful `Run the tests` step is the last evidence required by the workflow. Nothing checks that Gradle produced any `<testcase>` elements, that the selected classes appeared, or that every class assigned to a shard ran.

**Smallest change:** after a successful test invocation, parse `app/build/test-results/testDebugUnitTest/*.xml` and fail if:

- there are zero test cases;
- the actual class set does not equal the expected class set for that shard;
- an expected class produced no result file.

Derive the expectation from `test-shard.sh`/`MUFFY_ONLY_TESTS`; do not hard-code 148. Also run `test-shard.sh --verify` for the active shard count.

**Gain:** closes the entire zero-test family rather than another individual spelling bug.  
**Cost:** less than 1s per active shard and a small Python script; a test class with no tests becomes red, which is desirable under N4.  
**Measure:** temporarily force an empty selection and remove one expected class in a test branch; both must fail after Gradle, with the missing class named.

### 2. Empty and whitespace selections remain fail-open

**[PROVEN]** The workflow treats only the exact string `NONE` as a no-test verdict. An empty or whitespace selection takes the `RUN` branch. In the multi-shard path, `pick-tests.sh` treats an empty selection as matching nothing and emits `NONE`; a whitespace selection also reaches `NONE` because its placement loop iterates over no names. Every shard can therefore stop before Java and report success.

The current `changed-tests.sh` does not normally emit those strings, so this is a latent failure triggered by a future selector regression or malformed output—not a claim that ordinary selections are empty today.

**Smallest change:** in `pick-tests.sh`, accept only exact `NONE`, exact `ALL`, or a nonempty validated class list. Reject empty/whitespace input with exit 3. Validate every selected name before the `OF=1` special case as well; currently that path emits names without the universe check.

**Gain:** removes a silent-zero input class.  
**Cost:** negligible; malformed input becomes red instead of fast green.  
**Measure:** add `--self-test` cases for `""`, `" "`, and `ValidTest NoSuchTestAnywhere`, under both one and two shards.

### 3. A valid but incomplete map can run too little without triggering “unknown”

**[PROVEN]** `changed-tests.sh` sets `UNKNOWN=1` only when a source path appears on **no** map line. If a path appears on one line, the selector trusts the union of those lines completely. It cannot detect a missing test dependency on another line. A rename to an unmapped path normally falls back to `ALL` and is safe; the dangerous case is a mapped path whose listed coverage is incomplete or wrong.

**Smallest change:** either run the full suite for non-documentation PRs, priced in Q5, or add the static map checker described there. The checker can enforce inventory and references, but not behavioral coverage.

### 4. Runtime shard configuration is not validated as a matrix

**[PROVEN]** `strategy.job-total` is the number of matrix jobs, not `max(shard)+1`. Non-contiguous values such as `[1]` or `[0,2]` fail loudly because `SHARD >= OF`; `[0]` is safe. However, the workflow checks itself and self-tests the selector only when `matrix.shard == 0`, and there is no validation that the repository variable contains a unique, contiguous zero-based list.

**[ASSUMPTION]** A duplicated matrix value such as `[0,0]` could omit the other half while each duplicate runs shard zero; whether GitHub preserves or deduplicates that exact matrix needs an experiment, so I would not claim it as a proven current exploit.

**Smallest change:** at the present fixed size, replace the repository variable with the literal matrix `[0,1]`. Changing shard count then requires a reviewed workflow edit instead of an unvalidated repository setting.  
**Gain:** removes an entire configuration drift mode and keeps required check names stable.  
**Cost:** a workflow edit when resharding; no runtime cost.  
**Measure:** set the variable to each malformed value in a test branch before removing it, and record which fail; afterwards verify the matrix always contains exactly shards 0 and 1.

## B. Skipped build/lint/delivery on a push that needed them

### 5. `HEAD~1` is the wrong base for a multi-commit push

**[PROVEN]** Both `lint` and `build-release` use:

```sh
changed-tests.sh --base HEAD~1
```

with `fetch-depth: 2`. This answers “what did the last commit change?” not “what did this push change?”

Trigger:

1. Commit A changes app code.
2. Commit B changes only documentation.
3. Push A and B together.

The tests still run because non-PR test selection is `ALL`. Lint and the release decision see only B, return `NONE`, skip their work, and `deliver` is skipped because `changed == 'no'`. On `development`, no APK is handed out; on `main`, no release is made and that push’s app code was not linted.

**Smallest change:** pass `${{ github.event.before }}` through `env` in both jobs, fetch that exact commit, and use it as the base. If the fetch or diff fails, the existing selector’s `ALL` fallback is the safe outcome. Keep the dispatch override.

**Gain:** repairs both delivery and the main lint gate for multi-commit pushes.  
**Cost:** one extra small fetch; a missing base can cause a conservative full build/lint.  
**Measure:** push an app commit plus a docs commit to `development`: `changed` must be `yes`. A docs-only two-commit push must remain `no`.

## C. Artifacts or releases that should not exist

### 6. The signed candidate survives a red suite

Covered in Q3. The official `build-bundle` and release are gated; the raw `release-apk` artifact is not.

### 7. Manual dispatch can publish from `development` or another non-main ref

**[PROVEN]** `build-release` runs for every non-PR event. In `deliver`, both attestation and publication use:

```yaml
if: github.ref == 'refs/heads/main' || github.event_name == 'workflow_dispatch'
```

The second disjunct makes the branch check irrelevant for dispatch. A dispatch on `development` therefore publishes, despite N11. It also runs `lint`, and the lint step selects `lintDebug` because `GITHUB_REF_NAME` is not `main`, contrary to the stated main-only doctrine.

**Smallest change:** make attestation and publication conditional solely on `github.ref == 'refs/heads/main'` within this already non-PR job. Restrict the lint job similarly if manual development builds are intended to remain lint-free.

**Gain:** enforces the branch boundary.  
**Cost:** manual non-main releases cease; that is the intended constraint.  
**Measure:** dispatch on `development`: a development bundle may exist, but no release or attestation should be created. Dispatch on `main`: publication must still work.

### 8. Existing releases and overtaken runs can end as successful no-ops

**[PROVEN]** The publish step exits 0 both when the run was overtaken and when `v${VERSION_NAME}` already exists. The second case does not verify that the existing release targets this commit or contains this APK. A tag collision or partial earlier publication can therefore produce a green run that repaired nothing.

**Smallest change:** distinguish an idempotent re-run from a collision. If the release exists, compare its target SHA and APK digest with the current run; succeed only if both match, otherwise fail or repair missing assets. Emit an explicit summary for an intentionally skipped overtaken run.

**Gain:** no silent success with missing or different delivery.  
**Cost:** one release API read and one digest comparison on this rare tier.  
**Measure:** rerun the same commit successfully; then create a conflicting tag in a disposal test and verify a loud failure.

## D. Wrong-commit publication and cancellation

### 9. Publication has a head-check/use race, and the tag has no explicit target

**[PROVEN]** The head check and `gh release create` are separate operations. A push can occur after the check but before release creation. The create command does not pass `--target "$GITHUB_SHA"`.

**[PRIOR]** When `gh release create` creates a missing tag without an explicit target, it uses the current branch/default-branch state rather than guaranteeing the workflow’s `GITHUB_SHA`. In the race above, the release can consequently point at newer source while carrying an APK built from older source.

**Smallest change:** add `--target "$GITHUB_SHA"` and fail if the head API response is empty or unreadable. Keep the head check, but do not describe it as eliminating the race; it only narrows it.

**Gain:** the APK, attestation, and tag at least name the same commit.  
**Cost:** none beyond the CLI argument.  
**Measure:** inspect `targetCommitish`/the tag’s resolved SHA after the next release and compare it with the run SHA and attestation.

### 10. Cancellation can leave a partial release

**[PROVEN]** `cancel-in-progress: true` applies on `main`, and publication is a multi-asset external operation. Cancellation during `gh release create` can leave a release or tag with only some assets. The “existing release” fast path does not repair that state.

**Smallest detection:** after creation, verify the tag SHA, APK presence and digest, and expected scenario assets; fail if incomplete. Make a subsequent rerun capable of repairing it.  
**Stronger policy:** test `cancel-in-progress: false` for `main` only. That allows obsolete work to finish but reduces interruption during publication; it does not by itself remove the head-check race.  
**Measure:** perform one deliberately slowed publication on a disposable release, push again during upload, and inspect what remains. That drill, not the comment, should decide the main cancellation policy.

## What is not currently a hole

- **[PROVEN] Fork PRs:** no release, lint, signing, or delivery job runs; the token is read-only and repository secrets are withheld. A fork can make its own PR lie by changing its own tests/workflow, but it cannot obtain the release key through this YAML.
- **[PROVEN] Shallow PR history:** if both diff forms fail, `changed-tests.sh` returns `ALL`, which is fail-safe. Using full base history would make targeting more accurate, but no zero-test path follows from the shown fallback alone.
- **[PROVEN] A shard dying mid-run:** the run is not green; its job is failed or cancelled and `deliver` cannot pass `needs.test.result == 'success'`. The residual problem is the already-uploaded signed candidate, not official delivery.
- **[PROVEN] Unsigned publication:** the Gradle task-graph guard and subsequent signature/alignment checks are real. A missing key does not silently produce an unsigned release.

---

# Q3 — The optimistic build

## The reasoning is sound only for `deliver`, not for the artifact boundary

**[PROVEN]** `deliver` has a sound structural gate for its own outputs: the matrix must succeed, the build must succeed, and lint must succeed or be skipped. A red suite therefore cannot produce `build-bundle` or publish a release through that job.

**[PROVEN]** It does not make the one-shot API query safe, because `Hand the APK onward` creates a second, directly downloadable delivery channel before `deliver` runs.

## The query has a subset race

The jq program discards jobs whose `conclusion` is null and then evaluates only the remaining conclusions.

**[PROVEN]** With two shards:

- Shard 0 finishes successfully.
- Shard 1 is still running.
- The filtered array is `[ "success" ]`.
- Its length is nonzero and every retained item is `success`.
- The verdict is therefore **`green`**, not `running`.

If shard 1 later fails, the APK has already left the job. If no shard has finished, the verdict is `running`, with the same upload outcome. API failure produces `unknown`, again with the same outcome.

**[PROVEN]** A fail-fast sibling cancellation has `conclusion == "cancelled"`. If it is visible to the query, the current `any(. != "success")` correctly treats it as red. Cancellation does not repair the case where the APK was uploaded earlier, and it does not make `unknown` safe.

**[PRIOR]** There is also a permissions doubt worth measuring: `build-release` declares only `contents: read` but calls the Actions jobs API, which normally requires `actions: read` for `GITHUB_TOKEN`. If that call is receiving 403, the `2>/dev/null || echo unknown` construction turns a permanently broken gate into an apparently acceptable permanent `unknown`. Add `actions: read` if the query is retained, log the HTTP status and number of Test jobs, and verify two expected jobs are visible.

## Smallest safe repair while keeping the current architecture

Replace the one-shot query with a terminal wait:

1. Require the complete set of Test jobs to be present.
2. If any has `status != "completed"`, the answer is `running`.
3. If any completed conclusion is not `success`, fail before upload.
4. Only upload after every Test job completed successfully.
5. Retry API errors briefly, then fail closed.

**Gain:** no signed artifact exists before a terminal green suite.  
**Cost:** on `main`, probably zero added wall time because the 128–129s build already outlasts the suite. On a faster development build, the job waits for the suite—but `deliver` would have waited anyway. API unavailability now causes a loud red run instead of optimistic handoff.  
**Measure:** force one shard to sleep and the other to fail. At the end there must be no `release-apk` artifact. A green delayed run must still deliver within the old total ±6s on `main`.

## The cheaper no-query construction

**Build unsigned in parallel; sign only in `deliver`, after `needs:` has passed.**

- `build-release` compiles an explicitly unsigned candidate and uploads that. It is not installable, so its presence on a later-red run does not violate N3.
- `deliver` downloads the candidate only after tests/lint succeed, then zip-aligns, signs, verifies the certificate and alignment, attests the final APK, and publishes or bundles it.
- Delete `Ask the suite before anything leaves this job` entirely.
- This also removes signing secrets from Gradle build logic and third-party build plugins.

**Gain:** removes the API gate, the subset race, and the signed-artifact-on-red path; improves signing-key isolation.  
**Cost:** signing moves into `deliver`, likely adding only a small local operation on a roughly 9 MB APK **[PRIOR]**; the signing order must be correct—align before signing, then verify. It requires a deliberate CI-only unsigned candidate path so the existing “unsigned release must fail” guard is not weakened for publication.  
**Measure:** compare the final certificate fingerprint with today’s APK, run `apksigner verify` and `zipalign -c`, install over the previous build, and repeat the delayed-red probe. Expect no total-run regression greater than 6s; otherwise price the actual signing step from its log.

---

# Q4 — Caching

The useful four-store view is: Gradle wrapper/dependency home, Gradle task-output/build cache, the Robolectric runtime cache, and the configuration cache. `cache-cleanup` is policy on setup-gradle’s stores, not a fifth cache.

| Store | Assessment | Expected hit behaviour on a PR |
|---|---|---|
| setup-gradle wrapper/dependencies | **Load-bearing.** Without it, every fresh runner re-downloads the toolchain and Android/Compose dependency graph. | **[PRIOR]** Near-always warm once the default branch has the same dependency/Gradle inputs; cold on first use or dependency/toolchain changes. |
| setup-gradle task outputs/build cache | Useful around debug compilation, but **the test task itself is always 0% by design**. For release-only tasks it is currently close to cargo cult. | **[PROVEN]** No test verdict can come from it. Changed-source compile tasks generally miss; unchanged surrounding tasks may hit. |
| `~/.m2/repository`, fixed Robolectric key | **Load-bearing for tail latency**, worth about a minute on a cold run, although its steady-state restore costs about 3s. | **[PRIOR]** Near-100% until the runtime version changes or the entry is evicted. |
| `.gradle/configuration-cache` | Real but modest: approximately 6s of Gradle time, below end-to-end noise. It is not the foundation of the pipeline. | See the PR-scope distinction below. |

## Configuration-cache keys and PR scope

**[PROVEN]** The exact key correctly includes shard, OS, event, build scripts, properties, and wrapper. The event separation fixes the `-Pmuffy.shots` collision, and the absence of `restore-keys` is justified for this store: a wrong entry fails in ways that resemble source corruption.

**[PRIOR]** The expected PR hit rate needs an important qualification: Actions caches created for a pull-request merge ref are scoped to that PR. Because the key also contains `pull_request`, a brand-new PR cannot rely on the push entry and should normally miss on its first active shard. Later updates to that same PR can hit. A useful expectation is therefore:

- first run of a new PR: often **0%** for its configuration entry;
- subsequent run of the same PR, unchanged build inputs: high, often **100%** for an entry that was actually saved;
- after a Gradle/build-file edit: **0%**, by design.

That does not invalidate the cache; it explains why “warm PR” measurements must say whether they mean a new PR or a rerun.

## Release task-output caching

**[PROVEN]** `build-release`, `lint`, and PRs are cache-read-only. The only writer is the push test job, which runs `testDebugUnitTest`, not the expensive release tasks. Therefore release-only outputs have no demonstrated writer in the current workflow. A repeat release showing **0/40 from cache** is consistent with the configuration, not mysterious.

**[PROVEN]** The earlier experiment also showed why enabling writes is not automatically the answer: on changed source, the expensive tasks are keyed on inputs that changed, and saving cost about 7s without improving the measured build.

**Recommendation:** do not simply make `build-release` writable again. First separate dependency restoration value from task-output restoration value and measure the latter.  
**Gain:** potentially part of the 13s restore cost; no guaranteed saving.  
**Risk:** reintroducing 7s saves and 144 MB entries for outputs no future run reads.  
**Measure:** same-commit repeat builds and changed-source builds, with task hit counts and setup/post-step times reported separately.

## Configuration cache versus build cache

**[PROVEN]** They do not overlap. The configuration cache remembers the configured task graph; the build cache stores task outputs. `doNotTrackState` disables reuse of test results, not compilation outputs.

**[PROVEN]** Enabling the configuration cache for `build-release` as currently written would probably disappoint: every invocation supplies a fresh `muffy.versionCode` and usually a fresh `muffy.versionName`, and the build script reads those while configuring `defaultConfig`. That is a changing configuration input on every release. I do **not** have evidence to overturn D12. The experiment that would settle it is two identical release invocations with identical version properties and a dedicated exact key; if the second cannot reuse the entry and save at least 6s, leave `--no-configuration-cache` alone.

## `cache-cleanup: never`

**[PROVEN]** Keep it for runtime latency: the measured alternative spends 28s starting another Gradle merely to prune. That remains the right trade for this pipeline.

**[ARITHMETIC]** Storage is already the counterweight: **5.6 GB in 169 entries from one active day** means approximately **10 / 5.6 ≈ 1.8** similar days to the repository ceiling. At 10× activity, eviction/thrashing becomes routine; the problem is not waiting for 10× code.

**[PROVEN]** The manual pruning script is not safe to automate as written:

- its second page uses a different sort direction, so it does not construct the advertised complete oldest-first inventory;
- it sorts by `created_at`, not use, so it can delete the old but constantly used fixed Robolectric entry;
- despite its description, it is not restricted to a Gradle namespace.

**Recommendation:** retain `cache-cleanup: never`, but fix the pruner before using it again: paginate consistently, select by key prefix, exempt the active Robolectric/configuration keys, and prune only above a usage threshold.  
**Gain:** avoids manual 78-entry recovery sessions and cold runtime downloads; no PR wall-time gain.  
**Risk:** over-deletion causes cache misses, not incorrect builds.  
**Measure:** weekly cache bytes/count, config-cache hit rate, and Robolectric download time. Do not schedule the current script.

A smaller additional improvement is to derive the Robolectric key from the version in `app/build.gradle.kts`. The current hand-raised `v1` is an N5 convention; after an upgrade it can restore only the old runtime forever until someone remembers to bump it. The measurement is the first run after the next Robolectric upgrade: no Android runtime download should recur once the new entry exists.

---

# Q5 — The selector

## The map’s drift modes

**[PROVEN]**

1. **Missing source path:** safe for correctness; it falls back to `ALL`, but narrowing silently degrades.
2. **Stale source path after rename:** usually the same safe degradation, because the new path is unknown.
3. **Missing test-class line:** that class is not selected for mapped source changes, although it still runs on `ALL`.
4. **Stale test-class name:** usually loud now, because placement fails or Gradle finds no matching class.
5. **Valid but incomplete dependency mapping:** dangerous and silent. The source is “known,” so there is no full-suite fallback, yet the affected test is absent.
6. **Shared-hub collapse:** as more files depend on `AppViewModel`, `Workspace`, and `GitService`, more changes map to most of the suite. The map remains while its value disappears.
7. **Inventory/documentation drift:** the supplied inventory contains **15 classes with tests plus two harness files**, not 17 independently runnable test classes. Any completeness check must derive the universe from source, not prose.

## Cheapest automated completeness check

If the map is retained, add a millisecond-scale `check-test-map.py` to the existing shard-zero checks. It should:

- enumerate every `*Test.kt` class using the same discovery function as `test-shard.sh`;
- require every discovered class to have a map line or an explicit `SELF`/`ALL` classification;
- reject map lines naming nonexistent test classes;
- reject mapped source paths that no longer exist;
- enumerate every file under `app/src/main` and require it to appear as a mapped source or match an explicit `ALL`/wildcard entry;
- reject overlapping wildcard entries whose meanings differ.

That proves **reachability and inventory**, not behavioral coverage. It cannot prove that `GitService.kt → SyncFeatureTest` is a sufficient promise.

**Gain:** converts forgotten inventory maintenance into a loud PR failure.  
**Cost:** an initial inventory of the currently unlisted source files and one classification whenever a new file is added.  
**Measure:** add a dummy source file and rename a mapped file in a test branch; both must fail locally and in CI with the exact path named.

## What I would actually do at this size

**[ARITHMETIC]** The measured value of narrowing is about **32s** between narrow and wide changes. A one-class PR is 58s, so the full-suite price is approximately **58 + 32 = 90s**. For that price, the project maintains the map, selector, shard intersection, self-tests, and an entire failure family.

**[PRIOR] Under N5, I would remove map-based PR narrowing now and run every test for every non-documentation PR.** Keep:

- documentation-only early exit;
- `test-shard.sh` for splitting the full suite;
- execution-time `MUFFY_ONLY_TESTS` for shard filtering;
- the selector self-tests that still apply;
- the new JUnit execution postcondition.

Keep `changed-tests.sh` only as a conservative app-change classifier for build/lint decisions, after fixing its push base.

**Gain:** deletes valid-but-incomplete selection as a correctness risk and removes the highest-maintenance convention.  
**Cost:** approximately **+32s** on the narrowest PR, taking it to roughly 90s; no extra paid minutes or infrastructure.  
**Measure:** compare five representative PRs. If p50 exceeds an explicitly chosen latency ceiling, reintroduce narrowing only with the completeness checker and evidence that it still selects a useful subset.

If 58s versus 90s is worth more than the maintenance risk, the checker is the acceptable alternative. The current middle state—a hand map defended only against some spelling mistakes—is not.

## Alternatives

- **Gradle dynamic filtering:** only filters names. It has no source-to-behavior dependency graph and does not replace the map.
- **Compile-time dependency analysis:** high effort and likely to collapse through `AppViewModel`; it also misses resources, reflection, Robolectric setup, and behavior reached without a direct code reference.
- **Mutation probing:** useful as an occasional coverage audit, not as the per-PR selector. A surviving mutant does not prove safe selection, and a failing test does not necessarily identify every behavioral dependency.
- **Splitting test source sets/modules:** the right structural answer when compilation dominates at scale, not a 15-class rescue operation. It creates genuine module boundaries and makes compilation divisible, but it is far more expensive than either full-suite PRs or the static checker.

---

# Q6 — Sharding

## D3’s cache justification is now obsolete

**[PROVEN]** I disagree with D3 as written. Fixed position may be operationally simple, but it is no longer required to preserve configuration-cache entries. The test list travels in `MUFFY_ONLY_TESTS` and is read in `doFirst` at execution time; changing shard membership does not change the Gradle command line.

**[PROVEN]** The implementation also does not provide the claimed insertion stability. It hash-sorts classes and then deals by position. Inserting a class in the middle shifts later positions, so many subsequent classes can change shards.

**Measurement that settles D3:** reorder or add a class on a test branch without changing build scripts, run the affected shard twice, and inspect for `Reusing configuration cache.` If it reuses—as the execution-time selection design predicts—duration balancing carries no configuration-cache penalty.

**[PRIOR]** I would still keep the fixed split today. The 83/65 test-count split and 26% largest-class share do not justify a timing database. Duration balancing becomes interesting only when measured shard-wall imbalance exceeds the 6s noise floor repeatedly.

## Fixed cost and when another shard pays

**[PROVEN]** The supplied model uses approximately **F = 45s** of per-job fixed work and duplicates compilation **C** in every active shard. An added shard therefore consumes another runner slot and roughly `F + C` of runner occupancy. Those costs do not all add to wall time while jobs run concurrently; they become latency when the 20-job ceiling queues work.

For a balanced suite:

```text
wall(N) ≈ F + C + T/N
```

The marginal saving from adding one shard is:

```text
wall(N) − wall(N+1) = T / (N × (N+1))
```

**[ARITHMETIC]** At the document’s 10× example (`F=45`, `C=40`, `T=80`):

- 2 shards: `45 + 40 + 40 = 125s`
- 3 shards: `111.7s`, saving **13.3s**
- 4 shards: `105s`, saving another **6.7s**
- 5 shards: `101s`, saving only **4s**, below run noise

So approximately **four shards** is the useful ceiling in that example.

At the 100× example (`F=45`, `C=300`, `T=800`):

- 16 shards: `45 + 300 + 50 = 395s`
- 18 shards: approximately `389.4s`

Two more shards buy **5.6s**, below ±6s, while `F + C = 345s` remains untouched. That is the sharding ceiling: execution has stopped dominating. Splitting compilation units is then the only material lever.

**[ARITHMETIC]** On `main`, 18 test shards plus lint and the release build occupy the entire 20-job allowance. Nineteen test shards require 21 initial jobs and guarantee queueing even before another PR is considered.

## The specific edge cases

- **[PROVEN] `TEST_SHARDS=[0]`:** `strategy.job-total` is 1. `pick-tests.sh --of 1` is safe for valid input. For `ALL`, it emits no class filter and the Gradle task runs the whole suite. For a narrowed selection, it emits the selection directly. Its weakness is the missing name validation in that branch, not `--of 1`.
- **[PROVEN] `fail-fast: true`:** keep it. It shortens time-to-red, and `deliver` treats a cancelled matrix as unsuccessful. The optimistic artifact handoff must be fixed independently; disabling fail-fast would only make red answers slower.
- **[PROVEN] Class-level sharding has a hard floor:** no shard can finish faster than the largest indivisible class plus fixed work. Today that class is 11.2s. At scale, splitting the class or sharding below class granularity becomes necessary before adding more runners helps.

---

# Q7 — The scenario report

## It is not currently reliable before/after evidence

**[PROVEN]** Nine of the ten scenarios perform no new action inside the `then` lambda; only scenario 04 performs its principal action between `before()` and `after()`. Several others perform the described action **before** calling `record`/`scenario`:

- Scenario 05 writes both files and refreshes the change count before recording.
- Scenario 07 writes and syncs before recording.
- Scenarios 08–10 construct their histories before recording.
- Scenarios 01–03 are static screens with empty actions.

Those pictures may differ because of asynchronous rendering, but they are not evidence of the prose’s claimed transition. In scenario 07, for example, “before” is already the post-sync state.

**[PROVEN]** The prose is not tied to assertions. `result()` accepts any sentence. Scenario 05 waits for `changesOf(...) > 0`, while the prose claims two changes. Scenario 07’s prose says the file is on GitHub, but the scenario itself does not assert `remoteText`/`remoteFiles`. `ScenarioShots.write()` also does not require both pictures to exist before writing a page that links to them.

Nothing catches prose/picture disagreement. The only complete semantic check is the human reader, which is acceptable for appearance but not for claims such as “the file reached GitHub.”

## Human value and construction

**[PRIOR]** The report is useful as a guided tour accompanying `changes.md`; it is not evidence that an APK looks right or is safe to install. The APK, change list, signature, and provenance answer the install question. Twenty pictures with duplicated before/after states add substantially less than the harness implies.

**Keep:** one human-readable report, prose without classes/timings, and recording only on builds.

**Change:** make each scenario choose one of two honest forms:

- a real transition: perform the action inside `then`, assert the resulting state, and capture before/after around it;
- a static explanation: admit that it is one illustrative screen, not a before/after demonstration.

Also make `ScenarioShots.write()` fail recording runs when required PNGs are absent or empty, and put state assertions beside the prose that makes factual claims.

**Gain:** the report stops asserting transitions it did not observe, and missing-picture releases fail before publication.  
**Cost:** editing the ten scenarios; no new service and no meaningful CI overhead.  
**Measure:** run with `-Pmuffy.shots`, verify every linked file exists, and inspect scenarios 05 and 07 specifically: their before image must predate the action and their result sentence must correspond to an assertion.

## There is avoidable duplication

**[PROVEN]** The same prose is carried as individual scenario pages, an aggregate `scenarios.md`, and again in the release body, with separate sed rewrites for different link forms. That is the part fighting GitHub’s model.

Within N12, keep the flattened PNG/reconstruction requirement but publish one canonical aggregate report plus the flattened images. Drop the separate per-scenario page assets and the index-link rewriting unless someone actually opens those pages independently.

**Gain:** fewer broken-link paths and roughly ten fewer release assets; wall-time saving is negligible.  
**Cost:** the individual page download disappears.  
**Measure:** open the release body and the reconstructed phone report; every image and index link must resolve without the second copy of the prose.

## Should scenarios run on PRs?

**[PROVEN]** They do not literally run on every PR today: targeted PRs run them only when selected or when selection falls back to `ALL`.

**[PRIOR]** Recording is correctly disabled on PRs. Execution should be decided by their regression value, not by the report audience. Keep the flows that assert unique behavior. Delete or consolidate scenarios that merely duplicate feature tests and empty prose assertions; do not move them wholesale to `main`, where a broken flow would be discovered only at release time.

---

# Q8 — The scaling doctrine

## “Flat scaling” is a fixed-cost observation, not a law

**[PROVEN]** The current suite is dominated by setup and compilation, so additional small tests can be nearly free. That does not imply test execution can remain flat indefinitely. The useful objective is:

> minimize time to a trustworthy verdict, while proving what that verdict covered.

Total test count, average test duration, and summed class duration are secondary.

## The current instrument mixes unlike quantities

**[PROVEN]** `scaling-report.py` sums JUnit test durations. With `maxParallelForks = 4`, that is worker-time, not test-task wall time. It then compares the sum against budgets expressed as job wall time: 17s shard break-even, 60s shard recommendation, and 300s panic floor. Those comparisons are not dimensionally consistent.

**[PROVEN]** Its sharding-below-break-even warning is unreachable. `whole_suite` is `args.shards <= 1`; the final `elif whole_suite:` can only be reached after the preceding `elif args.shards <= 1` has already handled every such case. Thus one advertised drift detector can never fire.

**[PROVEN]** `max_failure_share` measures the proportion of summed test time attributed to failing cases, not time-to-red. It excludes queueing, compilation, and cancellation, which are often the actual cost of a red run. And zero results deliberately produce no warning—the exact silence N4 cares about.

## Measure these instead

1. **Executed evidence per shard:** expected classes, actual classes, test-case count, skipped count. This is a correctness instrument, not an advisory report.
2. **Critical-path wall time and time-to-red:** run creation → first job start; each job’s start/end; first failed job; test/build/lint completion; delivery completion. `ci.sh` already queries the jobs API, so most of these timestamps can be printed without another CI job.

**Gain:** the first catches silent gate erosion; the second identifies whether the next problem is queueing, compilation, execution, or delivery.  
**Cost:** less than 1s of local XML parsing and essentially no additional API machinery for the second.  
**Measure:** the zero-test probes must fail; a deliberate two-second queue delay and a delayed failing shard must appear in the reported intervals.

## What breaks first

- **[PRIOR] 10× tests:** shared-hub mappings increasingly select `ALL`; class-level shard imbalance and the largest Robolectric classes become visible; summed-duration advice becomes increasingly misleading. Per-shard test compilation is still duplicated.
- **[PRIOR] 10× code:** release Kotlin compilation, dexing, and whole-tree lint dominate. The 20/25-minute timeouts and 4 GB JVM heap become relevant before selector overhead does. Release task-output caching still cannot compensate for every source-changing release.
- **[ARITHMETIC] 10× contributors:** ten concurrent two-shard PRs consume all 20 slots before `main` starts its four-job test/lint/build fan-out. Queue time, not Gradle time, becomes the critical path.

## The instrument to delete

**[PRIOR]** Delete the advisory whole-suite half of `scaling-report.py`—the sharding recommendations, average-test moralising, and failure-share budget—along with `scaling-budget.txt`. Preserve the small per-class table if it is read, and move expected/actual counts into the hard test-evidence check. Keep `build-times.py`; unlike the scaling adviser, it has directly identified measured build wins.

The runtime saving is negligible. The gain is removing a few hundred lines of instrument that can give wrong advice while remaining silent about zero execution.

---

# Q9 — Trust and supply chain

## What is already sound

**[PROVEN]** PRs have read-only tokens and no path to signing secrets. Third-party actions are SHA-pinned. `persist-credentials: false` limits git credential reuse. Same-run artifact names and immutable artifact uploads make silent replacement by another job difficult. Attestation covers the final APK on ordinary `main` publication.

## The highest-value missing controls

### 1. Verify the expected signing identity, not merely “some valid signature”

**[PROVEN]** `apksigner verify --print-certs` followed by `apksigner verify` proves a valid APK signature. It does not prove that the certificate is the one used by previous releases. A valid but newly generated/wrong keystore passes every current check and then fails to upgrade users’ installed apps.

**Change:** compare the signer certificate SHA-256 with an expected fingerprint stored in a non-secret repository variable or other reviewed configuration.  
**Gain:** catches an effectively irreversible wrong-key release.  
**Cost:** one string comparison; rotation requires a deliberate reviewed change and a migration plan.  
**Measure:** compare every new APK with the currently installed known-good APK; a test signed by another key must fail before upload/publication.

### 2. The signing key is exposed to the build’s executable logic

**[PROVEN]** The release Gradle invocation receives the keystore and all passwords while evaluating repository build scripts and third-party plugins. Moving signing into the gated delivery step, as described in Q3, shrinks that exposure considerably.

**[ASSUMPTION]** If an AI agent’s credentials can push same-repository branches or dispatch workflows, repository-scoped signing secrets are a more serious insider/compromised-agent target than fork PRs. A workflow edit is executable code; a code review convention alone is not a security boundary.

**Cheapest strong control for actual releases:** place release signing secrets in a protected environment with an approval rule and restrict the agent’s ability to dispatch/alter release workflows.  
**Cost:** one approval per protected release and potentially inconvenience for development signing.  
**Measure:** attempt an unapproved dispatch using the agent identity; it must not receive signing material.

### 3. Branch protection is absent from the evidence

**[PROVEN]** `AGENTS.md` says PRs are the unit of verification, but nothing in the artifacts enforces that. A direct push to `main` is accepted by the workflow.

**Change:** require PRs and the Test checks for `main`; block force pushes. If shard count remains configurable, add one stable aggregate check name rather than binding branch protection to a changing matrix.  
**Gain:** makes the agent convention machine-enforced and reduces unexpected multi-commit direct-push cases.  
**Cost:** occasional repository administration; no PR wall-time increase.  
**Measure:** a direct push using the normal agent identity must be rejected, while a green PR merges normally.

### 4. Provenance is produced but never consumed

**[PROVEN]** `fetch-bundle.sh` downloads the APK and copies it into Downloads without verifying its attestation or digest. A replaced release asset would be delivered normally; the attestation would help only if someone manually checked it.

**Change:** verify the release APK’s attestation in `fetch-bundle.sh` before replacing the last good local delivery.  
**Gain:** detects release-asset tampering or accidental replacement at the point of use.  
**Cost:** one verification operation on a manual/local fetch, not on CI’s critical path.  
**Measure:** verification must succeed for the next release and fail for a locally modified APK.

### 5. Gradle distribution and dependencies are less pinned than actions

**[PROVEN]** `gradle-wrapper.properties` has a URL but no `distributionSha256Sum`. `validateDistributionUrl=true` does not authenticate the downloaded distribution’s content.

**Change:** add the official Gradle 9.8.2 distribution checksum.  
**Gain:** a corrupted or substituted distribution fails before execution.  
**Cost:** one checksum update per Gradle upgrade.  
**Measure:** a deliberately incorrect checksum in a test branch must stop the wrapper.

### 6. Permissions around the API query are backwards

**[PROVEN]** `build-release`, which calls the Actions jobs API, has only `contents: read`; `deliver`, which reads a branch head through the contents API, explicitly has `actions: read` with a comment claiming that permission is for the branch-head check. The comment is wrong. If the optimistic query remains, move the Actions read permission to the job that needs it; `contents: write` already gives `deliver` the repository read it needs.

### 7. Development delivery receives write permissions it does not use

**[PROVEN]** Ordinary development delivery needs none of `contents: write`, `id-token: write`, or `attestations: write`, yet receives all three because the job also serves `main`. This is not a fork-PR exposure, but it increases the consequence of a compromised development workflow.

The no-query unsigned-candidate construction makes a clean split easier: an unprivileged candidate builder and a privileged signer/publisher. If jobs remain combined, at least avoid exposing the write token to repository scripts until the publish step.

## GitHub’s free security features

**[PRIOR]** Prioritise them by maintainer attention, not checklist order:

1. **Secret scanning plus push protection:** cheapest protection against an agent committing a token or keystore; no CI latency. Verify with a harmless documented test pattern, never a real key.
2. **Clear or close the four stale Dependabot PRs before adding more alert volume.** Then use grouped security updates rather than another permanently ignored queue.
3. **Dependency review on dependency-changing PRs:** useful because this pipeline downloads executable Gradle plugins and dependencies; run beside tests so it does not extend the 58s critical path. Pin the action by SHA as required.
4. **Code scanning on a schedule, not as another PR gate initially.** A one-maintainer repository should first establish that alerts are read; otherwise it becomes another green-looking but ignored signal.

The worst realistic compromise is theft or replacement of the signing identity, because attestation does not make an attacker-held legitimate key safe again. The cheapest controls against the more likely accidents are the certificate-fingerprint assertion, protected signing secrets, and provenance verification during `bundle`.

---

# Q10 — What is missing entirely

## 1. `ci` can report the previous commit’s green run

**[PROVEN]** This is the largest missing safeguard outside the YAML.

After pushing, `ci.sh` immediately selects the newest run on the branch. It requests `headSha` in one JSON field list but does not filter on it. Unless the caller explicitly supplied `--sha`, a previous completed run can be selected before GitHub has created the run for the new push. The command can then print `RESULT: success` for code that is no longer the branch head.

Because `AGENTS.md` designates this command as the only test runner, this is an operator-facing false green.

**Change:** after any push, capture the intended local/remote SHA and require every candidate run’s `headSha` to match it. Do not fall back to “latest on branch.”  
**Gain:** eliminates stale-run verification.  
**Cost:** if PR API `headSha` semantics differ from the local SHA, the command waits/times out until the correct merge/head SHA handling is implemented; that is louder and safer than a false green.  
**Measure:** push twice in quick succession with a delay before the second run appears. The second invocation must never print the first run’s ID or verdict.

Also, `fetch-bundle.sh` failures are discarded with `|| true`, while the comments say success means the phone already has the build. Delivery failure must produce a distinct unsuccessful delivery result or an explicit “CI green, delivery failed” result.

## 2. `bundle --dev` mistakes the newest green run for the newest green bundle

**[PROVEN]** `bundle --dev` selects the latest successful development run and then attempts to download `build-bundle`. A later documentation-only run can be green with no bundle, causing the command to fail even though an older installable development build exists.

**Change:** search recent successful runs for the newest one that actually has `build-bundle`; distinguish “no app change in this run” from “no development build exists.”  
**Gain:** the phone command remains reliable after documentation and tooling pushes.  
**Cost:** a few artifact-list API calls on a local command.  
**Measure:** create an app build, then a docs-only green run; `bundle --dev` must still fetch the app build and name its run ID.

## 3. No release postcondition or repair path

**[PROVEN]** There is no assertion after `gh release create` that:

- the tag resolves to `GITHUB_SHA`;
- exactly the intended APK is present;
- its digest equals the attested local file;
- every scenario link has a corresponding asset;
- a rerun repaired, rather than ignored, a partial earlier attempt.

This is the missing control for cancellation, tag collision, and partial upload. It belongs on `main`, where two API calls and a digest are irrelevant beside 146s.

## 4. No drills for the failure modes that the current self-tests do not cover

The useful drills are specific:

- **Zero execution:** empty selection and one missing expected class must be red.
- **Multi-commit push:** app then docs must still build/lint/deliver.
- **Delayed red sibling:** one successful shard and one delayed failing shard must leave no signed candidate.
- **Second push during publication:** inspect tag, APK, and assets afterwards; rerun must repair or fail loudly.
- **Wrong signing key:** must fail certificate identity verification.
- **Stale local watch:** two rapid pushes must never attach the second `ci` invocation to the first run.

**[PRIOR]** Encode the first three as ordinary script tests/probes where possible. The publication and credential drills can remain occasional operator drills because automating real release destruction would cost more attention than it saves.

## 5. No automated cache-usage ownership

**[PROVEN]** There is a manual pruner, a documented unbounded-growth problem, and no threshold/report that does not depend on someone remembering. Fix the pruner first, then run it only when usage crosses a chosen threshold, with active runtime keys exempted. Measure cache bytes and cold-download incidence, not merely entry count.

## 6. No queue/critical-path telemetry

**[PROVEN]** The measurements record end-to-end totals and some Gradle tasks, but not queue time or the actual job timeline. That is the first missing number when contributor count or shard count rises. Add it to `ci.sh`, which already polls runs and jobs, rather than creating another CI job.

## 7. Floating runner and dependency-policy maintenance remain open

- **[PROVEN]** Replace `ubuntu-latest` with `ubuntu-24.04` in all jobs. This does not improve speed; it converts an unannounced image migration into a reviewed workflow edit. Measure three runs for no regression beyond ±6s and upgrade deliberately later.
- **[PRIOR]** Dependency verification metadata is worthwhile only after the dependency-review and stale-Dependabot backlog is under control. Generating a large verification file that no one updates would be another ornamental control.

## 8. A distant but real version-code defect

**[ARITHMETIC]** The 2038 fallback is not monotonic. Immediately before overflow, epoch seconds approach `2,147,483,647`; immediately afterwards the code becomes approximately `1,500,000,000 + RUN_NUMBER`, which is lower unless the run number has reached hundreds of millions. Android would regard the new APK as older.

This is not urgent. The eventual fix must preserve a value greater than the last published code rather than switching to a smaller unrelated sequence. Add a unit test around the boundary now if the version script is next touched; do not spend a release-cycle fire drill on 2038 today.

---

# Ranked actions

Ranking is by expected failure avoided or measured latency gained, confidence, and effort. Correctness items generally have **zero claimed wall-time gain**.

## Do now — under 30 minutes each

| Change | Expected gain / basis | Cost and risk | How to know it worked |
|---|---|---|---|
| **`ci.sh`: require run `headSha` to equal the commit just pushed** | Removes a stale green verdict from the only sanctioned test command | PR/merge SHA handling may require one adjustment; failures become timeout rather than false green | Two rapid pushes; second invocation must select only the second SHA |
| **Use `github.event.before` in `lint` and `build-release` decisions** | Fixes multi-commit push skipping lint/APK/release | Small fetch; missing history conservatively causes full work | App+docs push builds; docs+docs push stays skipped |
| **Restrict attestation/publication to `refs/heads/main`; add `--target "$GITHUB_SHA"`** | Closes non-main dispatch publication and aligns tag with built commit | Manual non-main releases stop; head API failures must be loud | Dispatch development and main; inspect release target SHA |
| **Reject empty/whitespace selection; validate selected names before the one-shard branch** | Removes remaining selector fail-open inputs | Malformed selector output now fails immediately | New self-test cases fail as intended on empty and mixed valid/invalid input |
| **Add a JUnit result assertion: nonzero cases and expected class set per active shard** | Establishes the missing execution postcondition; <1s basis | A zero-test or wrongly discovered class becomes red | Empty-selection and removed-class probes both fail after Gradle |
| **Replace `ubuntu-latest` with `ubuntu-24.04`; add wrapper distribution checksum** | Prevents unreviewed image/distribution changes; no speed claim | Manual future upgrades/checksum updates | Three normal runs within ±6s; bad checksum drill fails before build |
| **Assert expected APK certificate fingerprint** | Prevents a valid but upgrade-incompatible signer | Rotation becomes deliberately harder | Wrong-key test build fails; known-good fingerprint remains unchanged |
| **Enable secret scanning/push protection; clear or close the four stale Dependabot PRs** | Highest security value per maintainer minute; no CI latency | Alert triage; avoid adding further unused alert channels | Test pattern is blocked; stale PR count reaches zero |

## Do next — approximately a day

| Change | Expected gain / basis | Cost and risk | How to know it worked |
|---|---|---|---|
| **Repair optimistic handoff: terminal fail-closed wait now; preferably unsigned candidate + gated signing next** | Enforces N3 at the artifact boundary and can remove the API query entirely | Signing relocation needs careful align/sign/verify ordering; transient API failure becomes red if wait is retained | Delayed-red run has no installable artifact; green APK installs over previous; main delta within ±6s |
| **Make publication idempotent and verify tag, APK digest, and assets after creation** | Detects/repairs partial or colliding releases instead of green no-op | A few API calls on the rare main tier; repair logic must not overwrite a valid different release | Cancellation drill leaves either a complete release or a loud recoverable failure |
| **Enforce PR-only main with stable required checks; hard-code the current two-shard matrix** | Machine-enforces AGENTS.md and removes shard-variable drift | Repository administration; changing shard count later needs a deliberate edit | Agent direct push rejected; both required checks always appear |
| **Choose selector economics: full non-doc PR suite, or inventory checker** | Full suite removes map correctness risk for about **+32s**, approximately 90s narrow PR; checker preserves the 32s saving | Full suite costs latency; checker still cannot prove behavioral coverage | Five representative PRs for latency; dummy new/renamed files must trigger the chosen safe behavior |
| **Fix and threshold the cache pruner; exempt active fixed keys** | Avoids 10 GB thrashing and cold Robolectric downloads; no run-speed claim | Wrong deletion causes misses, so dry-run and namespace restrictions are required | Dry run names exact victims; usage stays below threshold without recurring cold runtime downloads |
| **Repair scenarios 05/07–10 and validate generated PNGs/prose claims** | Makes the report evidence of what it says happened | Scenario edits; semantic appearance remains human-only | Before/after brackets the action; result assertions and all linked files exist |
| **Verify attestation in `fetch-bundle.sh`; make `bundle --dev` find the newest actual bundle** | Prevents tampered/wrong APK delivery and docs-run delivery failures | Local verification dependency and a few API calls | Modified APK rejected; docs-only run no longer hides the last development bundle |
| **Experiment with release task-output cache restoration separately from dependencies** | Only remaining plausible >6s main deletion besides delivery handoff; upside bounded by roughly 13s | Could lose useful compile outputs or add setup complexity | Three same-commit and three changed-source runs; adopt only if total improves >6s |

## Do eventually

| Change | Expected gain / basis | Cost and risk | Measure |
|---|---|---|---|
| **Split test/application modules when `C`, not `T/N`, dominates** | At the document’s 100× model, attacks the `F+C ≈ 345s` floor that more shards cannot reduce | Major source and dependency-graph refactor | Per-shard compile wall time must fall; total must beat the 16-shard baseline, not merely use more runners |
| **Introduce duration balancing only after repeated >6s shard-wall imbalance** | Better shard wall time without the claimed config-cache penalty, which should first be disproved by the reuse experiment | Timing history and more selector machinery | Configuration cache still reuses; imbalance and total improve beyond noise |
| **Add queue/critical-path trends and dependency scanning on a schedule** | Detects contributor/shard ceiling and dependency risk without taxing the workhorse PR | Alert and storage maintenance | Queue time explains totals; alerts are actually triaged |
| **Fix and test the 2038 version-code boundary when versioning is next touched** | Prevents future version-code regression | Must define monotonic behavior beyond epoch seconds | Boundary test proves every new code exceeds the previous published code |

## Stop doing

| Stop | Why / replacement |
|---|---|
| **Stop treating a one-shot `running`/`unknown` suite answer as permission to upload a signed APK.** | `deliver` does not retroactively make that artifact safe. Gate before signed output or sign after the structural gate. |
| **Stop spending PR latency effort on changes below 6s.** | **[ARITHMETIC]** The entire unexplained gap above the documented 53s floor is about 5s; it cannot be measured against current variance. |
| **Stop extending `test-map.txt` as though presence proved coverage.** | Either pay the measured ~32s for full PR testing or enforce inventory with a checker; valid-but-incomplete mapping remains the unsolved case. |
| **Stop citing fixed-position sharding as a configuration-cache requirement.** | The selection is now an execution-time input. Verify cache reuse and keep fixed dealing only because it is currently simpler. |
| **Stop running the current broad `prune-caches.sh` as housekeeping.** | Its pagination is inconsistent and its age-based deletion can remove the actively used runtime cache. |
| **Stop treating the scaling report’s sharding and failure-share advice as measurement.** | It mixes summed worker-time with wall budgets, contains unreachable advice, and stays silent on zero results. Replace it with execution evidence and wall-time intervals. |

---

# After the audit: what was checked, what was fixed, what was not

## Verified before acting

Every finding below was re-derived from the artifacts rather than trusted.

**The signed APK could exist while a shard was still running.** The verdict query
dropped jobs without a conclusion, then asked whether the rest had passed — so
shard 0 finishing first read as green. Reproduced with the same jq on a synthetic
two-job response. Fixed: the step now waits for every test job to conclude and
fails closed. Verified in a real run: `All 2 test jobs passed, so the APK is
handed on.`

**Lint and the release build asked the wrong question.** Both used `--base
HEAD~1`, which is what the last commit changed, not what the push changed — so a
push carrying an app commit and then a documentation commit skipped lint and the
APK. Fixed with `tools/push-base.sh`, which uses `github.event.before` and falls
back to `HEAD~1` only when it cannot be read.

**A manual dispatch on any branch could publish.** `github.event_name ==
'workflow_dispatch'` bypassed the branch check. Fixed: publication and attestation
are `refs/heads/main` only, and the tag is created with `--target "$GITHUB_SHA"`.

**Nothing checked that the tests that ran were the tests that were asked for.**
Added `tools/check-results.py` and a workflow step that reads the JUnit XML. First
real run: `8 result files · 82 test cases · 0 skipped / asked for 8 classes; 8
produced tests`.

**An empty selection was read as "nothing to do".** With two shards that stopped
every shard before the toolchain and produced a green run with no tests. Now
refused, and the self-test covers empty, blank, unknown and half-valid selections.

**`ci` could report a previous commit's green.** It requested `headSha` and never
used it. It now asks about a commit, and says so when watching another branch.

**The scaling report had a branch that could never run** (`whole_suite` is
`shards <= 1`, already answered above it) and compared summed worker-time with
wall-clock budgets. The dead branch is gone and the figure is labelled for what
it is.

**Smaller, all verified:** `ubuntu-latest` → `ubuntu-24.04`; the Gradle wrapper
now checks its distribution's SHA-256; the scenario harness can show a second
screen so 08–10 are real transitions instead of the same frame twice.

## Measured after the fixes

| | before | after |
|---|---|---|
| `main`, full release | 146s | **124s** |
| release build job | 128s | 96s |
| scenarios showing a real transition | 1 of 10 | **6 of 10** |

## Where the audit was wrong or unproven

**`actions: read` was not missing.** It claimed the verdict query must be failing
with 403 because `build-release` declares only `contents: read`. The logs show the
query succeeded and printed the `running` branch, so the permission is fine.

**The scenario suite does not run on every pull request.** It claimed it did. It
runs when the selector picks it or when selection falls back to `ALL`.

**"Fixed-position sharding is no longer needed for the cache" is right, but the
conclusion was not applied.** The claim is proven and the code still deals by
position; that is now an open question rather than a defect.

## Where I disagreed and was wrong

I guessed that scenarios 04 and 07 were identical because a missing
`refreshChanges()` stopped the redraw. I added it, measured, and the pictures
were still byte-identical. The commit was reverted with a message saying so.

**Still open:** 04 and 07 produce identical before/after pictures. Evidence: 04's
after is not the same image as 05's before, which does show the projects list
with a project in it — so 04 is probably not painting the list it claims to.
07's row count never changes while its prose says nothing is waiting. Both need a
person's eyes on the pictures.

## The one recommendation I refused

The audit proposed merging delivery into the release build to save 8–10s. That
was already refused once and the reason still holds: delivery must wait for tests
and lint, so folding it in would put the build behind both. Removing a job is
worth ~45s; this would have cost ~60s. The audit's narrower reading — build
unsigned in parallel, sign only after the gate — is sound and is the better
version of that idea, but it moves the signing key and the `apksigner`/`zipalign`
steps into `deliver`, which is a change to the release path and deserves its own
run rather than being tacked onto this one.
