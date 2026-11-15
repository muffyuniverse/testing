# Context size and prompt-cache hit rate

**Report date:** 2026-11-15
**Scope:** project `testing`, branch `main`
**Source:** the app's request log — `requests` (per request, ~1 min retention),
`roll_minute` (2 days), `roll_hour` / `roll_day` (retention period)
**Model/provider:** `openrouter.ai` / `deepseek/deepseek-v4.1-flash-1017` (the only pair seen in scope)

---

## 1. Summary

Prompt-cache hit rate in this branch has fallen from **90.8%** (11-13) to **42.8%** (11-15)
while average prompt size grew from **18k** to **166k** tokens. Hit rate is a function of
context size, not of traffic or of cache expiry. The cause is prefix breakage from continuous
mid-context trimming: of 4376 conversation rows in this branch, only 21 (1852 tokens) are
still in context, and every removal that is not at the very tail invalidates the cached prefix.

At the fitted prices, ~**72% of today's spend ($1.84 of $2.57) is attributable to the miss
rate** rather than to more work being done.

## 2. Method

- Hit rate is defined as `cached / prompt`, per request and aggregated as
  `SUM(cached) / SUM(prompt)`.
- Requests are bucketed into bands by per-request hit rate: cold `<5%`, partial `5-50%`,
  warm `50-90%`, hot `>90%`.
- Per-request detail survives about a minute; older history exists only as minute aggregates
  with a request count (`n`). All aggregate statements below are therefore weighted by `n`,
  never by number of minutes.
- Unit prices were fitted on today's data rather than taken from a price list, because
  completion is only ~0.5% of prompt volume and is absorbed into the fit.

## 3. Results

### 3.1 Day trend

| day | requests | prompt tokens | cached | hit rate | USD |
|---|---|---|---|---|---|
| 2026-11-13 | 84 | 1,539,295 | 1,397,632 | 90.8% | 0.0324 |
| 2026-11-14 | 310 | 26,609,288 | 15,995,867 | 60.1% | 0.9120 |
| 2026-11-15 | 266 | 44,165,745 | 18,882,560 | 42.8% | 2.5680 |

### 3.2 Today by hit-rate band

| band | requests | avg prompt | hit rate | USD | share of spend | milli-USD per 1k prompt |
|---|---|---|---|---|---|---|
| hot >90% | 61 | 65.8k | 98.0% | 0.0372 | 1.4% | 9.1 |
| warm 50-90% | 66 | 197k | 62.1% | 0.5100 | 19.9% | 39.2 |
| partial 5-50% | 107 | 206k | 30.7% | 1.5265 | **59.5%** | 69.2 |
| cold <5% | 32 | 159k | 1.9% | 0.4944 | 19.3% | 97.2 |
| **all** | **266** | **166k** | **42.8%** | **2.5680** | 100% | 58.1 |

Cost per 1k prompt tokens rises monotonically as the hit rate falls — from 9.1 in the hot
band to 97.2 in the cold band, a **10.6x** spread within the same day and the same model.

### 3.3 Hourly detail

| hour (UTC) | requests | avg prompt | hit rate |
|---|---|---|---|
| 11-14 07h | 65 | 55,159 | 84.9% |
| 11-14 08h | 18 | 110,921 | 17.3% |
| 11-14 10h | 112 | 41,162 | 95.8% |
| 11-14 11h | 60 | 108,712 | 60.1% |
| 11-14 12h | 55 | 179,897 | 43.2% |
| 11-15 01h | 55 | 61,450 | 97.3% |
| 11-15 02h | 168 | 181,245 | 38.1% |
| 11-15 03h | 43 | 240,389 | 38.6% |

The relationship is visible hour by hour: the two hours above 180k average prompt are the
two hours at ~38%, and the two hours near 60k are at 85-97%.

### 3.4 Cache expiry ruled out

| gap since previous request | minutes | requests | avg prompt | hit rate |
|---|---|---|---|---|
| ≤2 min | 99 | 507 | 113,709 | 51.7% |
| 2-10 min | 28 | 104 | 121,191 | 41.7% |
| 10-60 min | 6 | 22 | 76,809 | 52.8% |
| >1 h | 3 | 11 | 14,293 | 66.3% |

Hit rate does not improve at short gaps and does not collapse at long ones. The shortest
gap band has the *largest* average prompt and a middling hit rate. Cache TTL is not the driver.

## 4. Unit price model

Cost is treated as linear in uncached prompt tokens:

    cost ≈ 99 milli-USD/1k × uncached_prompt + 7.3 milli-USD/1k × cached_prompt

Ratio **≈ 13.6x**: a cached token costs about a fourteenth of an uncached one.
Fitted on the cold and hot bands, then checked on the two bands held out of the fit:

| band | cached share | predicted m$/1k | actual m$/1k |
|---|---|---|---|
| partial | 30.7% | 70.9 | 69.2 |
| warm | 62.1% | 42.0 | 39.2 |

Both within ~7%, so the model is adequate for projection.

**Projection:** today's 44.17M prompt tokens at a 90% hit rate would have cost **~$0.73**;
at 98%, **~$0.40**. Actual spend was **$2.568**. Avoidable cost: **~$1.84 (72%)**.

## 5. Diagnosis

Caching is prefix-based: the provider serves a cached prefix only while it matches byte for
byte, and re-reads everything from the first difference onward at the uncached price.

`agent_rows` for this branch holds **4376 rows**, of which **21 (1852 tokens)** are still
`ctx='in'`. The conversation is trimmed continuously and hard. Any trim that removes a row
from the middle of the context — as opposed to dropping the tail — moves the first
difference to that point, so the entire remainder is re-read at 13.6x the price on that turn
and every following turn until the prefix re-settles.

The big-context minutes are exactly the partial and cold ones, which is the same fact stated
from the other side: large context means a large surface for the prefix to break, and a large
bill when it does.

## 6. The current session is the counter-example

Last five requests in this session: prompt 15.3k → 22.0k, hit rates
**99.3 / 98.7 / 94.8 / 96.9 / 98.4%**.

Append-only turns keep the prefix intact, so only the new tail is paid at full price. The
single 94.8% dip is the turn a large tool result entered the context — new tokens are
unavoidably uncached, and one turn later the prefix had re-settled above 96%.

## 7. Recommendations

1. **Append, don't rewrite.** Prefer adding rows over editing or removing them mid-context.
   A mid-context trim costs ~13.6x the removed tokens on every subsequent turn until the
   prefix re-settles.
2. **Trim at the tail, or not at all.** Where trimming is necessary, remove the oldest or
   newest end rather than a middle span.
3. **Keep the working context small by construction.** Use `grep` to locate and ranged
   `read` to fetch only the needed lines, rather than reading whole files into context.
4. **Batch independent calls per turn.** Fewer turns over the same prefix is strictly cheaper
   at a high hit rate, and each turn re-sends the whole context.
5. **Watch the band, not the average.** The partial band carries 59.5% of spend on 40% of
   requests. A dashboard that reports only the day average hides this.

## 8. Reproduce

    pipe {"steps":[{"tool":"query","args":{"sql":{"file":"monitor/dashboard.sql"},"direct":true}}]}

`monitor/dashboard.sql` returns four sections in one statement: live per-request detail
(last 30 min), today's totals, today split by band, and the 3-day hourly trend.
`monitor/README.md` carries the definitions and the same fitted price model.

## 9. Caveats

- `cache_write` is reported as 0 on every request by this provider, so write cost cannot be
  separated from read cost; hit rate is `cached / prompt` only.
- Per-request history beyond roughly one minute is not retained. Older figures are minute
  aggregates, and individual requests within a minute cannot be recovered.
- The log's conversation identity is `(project, branch)`, not a session id. Individual
  200k-prompt requests cannot be attributed to a particular session or task.
- The price model folds completion tokens (~0.5% of prompt volume) into the prompt rates and
  is fitted on one day of one model; it is a good basis for comparison, not an invoice.
