-- ═══════════════════════════════════════════════════════════════════════════
-- THE SYSTEM — 004: SCHEDULED JOBS (pg_cron)
-- Requires pg_cron + pg_net enabled (Supabase: Database → Extensions → pg_cron).
-- If you prefer Edge Function scheduling, the equivalent HTTP jobs are listed
-- at the bottom (commented) and the functions live in supabase/functions/.
-- ═══════════════════════════════════════════════════════════════════════════

create extension if not exists pg_cron;
create extension if not exists pg_net;

-- 1. 10-MINUTE MEDIA AUTO-PURGE — runs every minute, expires media at +10:00.
select cron.schedule(
  'purge-workout-media',
  '* * * * *',
  $$select public.purge_workout_media();$$
);

-- 2. DAILY PENALTY ENGINE — 00:05 UTC: XP decay, degradation to GARBAGE/LOSER,
--    arc timeline extensions.
select cron.schedule(
  'daily-penalty-engine',
  '5 0 * * *',
  $$select public.apply_daily_penalties();$$
);

-- ── Alternative: route the same jobs through the Edge Functions ─────────────
-- (set your service role JWT as a DB custom setting first, then uncomment,
--  or simply call them from your ops tooling — both functions are idempotent)
--
-- select cron.schedule('purge-via-edge', '* * * * *', $$
--   select net.http_post(
--     url := 'https://<PROJECT_REF>.supabase.co/functions/v1/purge-verification-media',
--     headers := jsonb_build_object(
--       'Authorization', 'Bearer ' || current_setting('app.settings.service_role_key'),
--       'Content-Type', 'application/json'),
--     body := '{}'::jsonb);
-- $$);
--
-- select cron.schedule('penalties-via-edge', '5 0 * * *', $$
--   select net.http_post(
--     url := 'https://<PROJECT_REF>.supabase.co/functions/v1/apply-daily-penalties',
--     headers := jsonb_build_object(
--       'Authorization', 'Bearer ' || current_setting('app.settings.service_role_key'),
--       'Content-Type', 'application/json'),
--     body := '{}'::jsonb);
-- $$);
