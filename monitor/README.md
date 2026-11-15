# Context & cache-hit monitor

Scope: project `testing`, branch `main`. Source: the app's request log
(`requests` = last ~1 min, per request; `roll_minute` = 2 days; `roll_hour` / `roll_day` = longer).

## Run it

    pipe {"steps":[{"tool":"query","args":{"sql":{"file":"monitor/dashboard.sql"},"direct":true}}]}

Sections returned: `live` (each request of the last 30 min), `today ALL`,
`today` split by hit-rate band, `hour` trend over 3 days.

## Definitions

- hit rate = `cached / prompt`. `cache_write` is always 0 here — the provider does not report writes.
- bands: cold `<5%`, partial `5-50%`, warm `50-90%`, hot `>90%`.

## Measured unit prices (fitted from today's 266 requests)

Cost is essentially linear in uncached prompt tokens; completion is ~0.5% of prompt
volume, so it is inside the fit.

| tokens            | milli-USD per 1k |
|-------------------|------------------|
| uncached prompt   | ~99              |
| cached prompt     | ~7.3             |

Ratio ≈ **13.6x**: a cached token costs about a fourteenth of an uncached one.
Check on independent bands: partial 30.7% cached → predicted 70.9, actual 69.2;
warm 62.1% → predicted 42.0, actual 39.2. Fit holds.

## Reading for 2026-11-15

| band | reqs | avg prompt | hit | USD | share of spend |
|---|---|---|---|---|---|
| hot >90% | 61 | 65.8k | 98.0% | 0.037 | 1.4% |
| warm 50-90% | 66 | 197k | 62.1% | 0.510 | 19.9% |
| partial 5-50% | 107 | 206k | 30.7% | 1.527 | 59.5% |
| cold <5% | 32 | 159k | 1.9% | 0.494 | 19.3% |
| **all** | **266** | **166k** | **42.8%** | **2.568** | |

Day trend: 11-13 90.8% hit → 11-14 60.1% → 11-15 42.8%. Average prompt grew
18k → 55k → 166k over the same three days. **Hit rate falls as context grows**,
which is the whole of the regression.

Same token volume at a higher hit rate would cost: 90% → ~$0.73, 98% → ~$0.40.
So ~$1.84 of today's $2.57 (72%) is attributable to the low hit rate, not to more work.

Gap analysis (time since previous request) does **not** explain it: hit rate is
51.7% for gaps ≤2 min, 41.7% for 2-10 min, 52.8% for 10-60 min. Cache TTL is not the driver.

## Cause

Cache is prefix-based. `agent_rows` holds 4376 rows for this branch but only 21
(`1852` tokens) are still `ctx='in'` — the conversation is trimmed hard, continuously.
Every removal that is not at the very tail shifts the prefix, and everything after it
must be re-read at the uncached price. The big-context minutes (150-240k prompt) are
exactly the ones in the partial/cold bands.

## Current conversation (healthy)

Last 5 requests: prompt 15.3k → 22.0k, hit 99.3 / 98.7 / 94.8 / 96.9 / 98.4%.
Append-only turns keep the prefix intact, so only the new tail is paid at full price.
The one 94.8% dip is the turn where a large tool result entered — expected.

Rule of thumb: **append, don't rewrite.** Trimming mid-context costs about
13x the price of the tokens removed, on every following turn until the prefix re-settles.
