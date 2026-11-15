-- Context + cache-hit monitor for project 'testing' / branch 'main'.
-- Run with:  pipe {"steps":[{"tool":"query","args":{"sql":{"file":"monitor/dashboard.sql"}}}]}
-- Sections: live (per-request, last 30 min) | today totals | today by hit-rate band | 3-day hourly trend.
WITH base AS (
  SELECT * FROM roll_minute
  WHERE project='testing' AND branch='main'
    AND bucket >= (CAST(strftime('%s','now') AS INTEGER)/86400)*86400*1000
), banded AS (
  SELECT CASE WHEN 1.0*cached/NULLIF(prompt,0) < 0.05 THEN 'cold <5%'
              WHEN 1.0*cached/NULLIF(prompt,0) < 0.50 THEN 'partial 5-50%'
              WHEN 1.0*cached/NULLIF(prompt,0) < 0.90 THEN 'warm 50-90%'
              ELSE 'hot >90%' END AS band, *
  FROM base
)
SELECT section, label, reqs, prompt, cached, hit_pct, avg_prompt, completion, usd FROM (
  SELECT 1 AS ord, 'live' AS section,
         strftime('%H:%M:%S', utc/1000, 'unixepoch') AS label,
         1 AS reqs, prompt, cached,
         ROUND(100.0*cached/NULLIF(prompt,0),1) AS hit_pct,
         prompt AS avg_prompt, completion, ROUND(cost/1000000.0,4) AS usd
  FROM requests
  WHERE project='testing' AND branch='main'
    AND utc >= (CAST(strftime('%s','now') AS INTEGER)-1800)*1000
  UNION ALL
  SELECT 2, 'today', 'ALL', SUM(n), SUM(prompt), SUM(cached),
         ROUND(100.0*SUM(cached)/NULLIF(SUM(prompt),0),1),
         CAST(SUM(prompt)/SUM(n) AS INTEGER), SUM(completion), ROUND(SUM(cost)/1000000.0,4)
  FROM base
  UNION ALL
  SELECT 3, 'today', band, SUM(n), SUM(prompt), SUM(cached),
         ROUND(100.0*SUM(cached)/NULLIF(SUM(prompt),0),1),
         CAST(SUM(prompt)/SUM(n) AS INTEGER), SUM(completion), ROUND(SUM(cost)/1000000.0,4)
  FROM banded GROUP BY band
  UNION ALL
  SELECT 4, 'hour', strftime('%m-%d %Hh', bucket/1000, 'unixepoch'), SUM(n), SUM(prompt), SUM(cached),
         ROUND(100.0*SUM(cached)/NULLIF(SUM(prompt),0),1),
         CAST(SUM(prompt)/SUM(n) AS INTEGER), SUM(completion), ROUND(SUM(cost)/1000000.0,4)
  FROM roll_hour
  WHERE project='testing' AND branch='main'
    AND bucket >= (CAST(strftime('%s','now') AS INTEGER)-259200)*1000
  GROUP BY bucket
) ORDER BY ord, label
