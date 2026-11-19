-- ═══════════════════════════════════════════════════════════════════════════
-- MIGRATION 044 · v1.0 §4 — LIVE LEADERBOARD RAILS
--
-- medal_trial_enrollments is RLS-locked to its owner (payment-adjacent rail).
-- The MEDAL TRIALS board needs cross-hunter progress, so this security-definer
-- view exposes ONLY the board columns — no user ids, no order refs, no
-- shipping data. The OVERALL board reads public.users (users_select is
-- already open: public hunter profiles are the game's public face).
-- ═══════════════════════════════════════════════════════════════════════════

create or replace function public.medal_trial_leaderboard()
returns table (
  username   text,
  level      int,
  trial_id   text,
  status     text,
  reps_done  int,
  km_done    numeric,
  city       text,
  country    text
)
language sql stable security definer set search_path to 'public' as $$
  select u.username, u.level, e.trial_id, e.status, e.reps_done, e.km_done,
         u.city, u.country
    from public.medal_trial_enrollments e
    join public.users u on u.id = e.user_id
   where e.status in ('ACTIVE','COMPLETED')
   order by e.reps_done desc, e.km_done desc
   limit 200;
$$;
grant execute on function public.medal_trial_leaderboard() to authenticated;

-- medal completion count per hunter (board's "medals earned" column)
create or replace function public.medal_completion_counts()
returns table (username text, medals bigint)
language sql stable security definer set search_path to 'public' as $$
  select u.username, count(*) as medals
    from public.medal_trial_enrollments e
    join public.users u on u.id = e.user_id
   where e.status = 'COMPLETED'
   group by u.username;
$$;
grant execute on function public.medal_completion_counts() to authenticated;
