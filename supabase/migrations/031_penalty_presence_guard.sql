-- ════════════════════════════════════════════════════════════════════════════
-- THE SYSTEM — 031: PENALTY ENFORCEMENT + PRESENCE + WALLET GUARD HARDENING
-- ─────────────────────────────────────────────────────────────────────────────
-- Forensics (2026-10-16, live cron.job_run_details):
--   · daily-penalty-engine (jobid 2) has FAILED every night — uncaught cast:
--     "column penalty_state is of type penalty_state but expression is of type
--      text". Zero penalties were ever applied. Fixed below + admin exemption.
--   · purge_workout_media (jobid 1) fails every minute — protect_delete blocks
--     raw storage.objects deletes. Row-purge is salvaged; storage cleanup is a
--     service-key concern (documented), errors no longer kill the job.
--   · users.vc_held (mig 030) was missing from users_guard's forbidden set.
-- New rails:
--   · users.last_seen_at  — chat presence ("Online" / "Last seen")
--   · users.penalty_synced_at — client-startup penalty catch-up idempotency
--   · touch_presence() + sync_inactivity() RPCs
-- ════════════════════════════════════════════════════════════════════════════

-- ── A. COLUMNS ───────────────────────────────────────────────────────────────
alter table public.users add column if not exists last_seen_at timestamptz;
alter table public.users add column if not exists penalty_synced_at date;

-- ── B. USER GUARD v2 — internal-write GUC bypass + vc_held/penalty_synced_at ──
-- PostgREST clients cannot set custom GUCs, so 'system.internal_write' flips
-- only inside trusted security-definer RPCs — never from the APK.
create or replace function public.guard_user_columns() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if (auth.jwt() ->> 'role') = 'service_role' then return new; end if;
  if current_setting('system.internal_write', true) = 'on' then
    new.updated_at := now();
    return new;
  end if;
  if not public.is_admin() then
    if new.role is distinct from old.role
       or new.level is distinct from old.level
       or new.xp is distinct from old.xp
       or new.vc_balance is distinct from old.vc_balance
       or new.vc_held is distinct from old.vc_held
       or new.prediction_points is distinct from old.prediction_points
       or new.penalty_state is distinct from old.penalty_state
       or new.penalty_synced_at is distinct from old.penalty_synced_at
       or new.black_room_until is distinct from old.black_room_until then
      raise exception 'forbidden_columns';
    end if;
  end if;
  if new.role is distinct from old.role and not public.is_super_admin() then
    raise exception 'forbidden_columns';
  end if;
  new.updated_at := now();
  return new;
end $$;

-- ── C. PRESENCE — heartbeat the chat rail calls (own row only) ───────────────
create or replace function public.touch_presence()
returns void language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then raise exception 'unauthorized'; end if;
  perform set_config('system.internal_write', 'on', true);
  update public.users set last_seen_at = now() where id = auth.uid();
end $$;
grant execute on function public.touch_presence() to authenticated;
revoke execute on function public.touch_presence() from anon;

-- ── D. PENALTY ENGINE v2 — cast-fixed, admin-exempt, cron-safe ───────────────
create or replace function public.apply_daily_penalties() returns int
language plpgsql security definer set search_path = public as $$
declare r record; decayed bigint; affected int := 0;
begin
  perform set_config('system.internal_write', 'on', true);
  for r in
    select id, xp, missed_days, streak_days from public.users
     where role = 'USER'                                   -- §6: admins exempt, strictly standard users
       and last_activity_date is not null and last_activity_date < current_date
  loop
    decayed := floor(r.xp * (0.03 * (r.missed_days + 1)))::bigint;
    update public.users
       set xp = xp - decayed,
           level = public.level_for_xp(xp - decayed),
           missed_days = missed_days + 1,
           streak_days = 0,
           penalty_state = (case
             when missed_days + 1 = 1 then 'WARNING'
             when missed_days + 1 = 2 then 'DECAY'
             when missed_days + 1 in (3,4) then 'GARBAGE'
             else 'LOSER' end)::penalty_state,             -- the cast that killed jobid 2
           penalty_synced_at = current_date
     where id = r.id;
    update public.user_arc_progress set penalty_extra_days = penalty_extra_days + 1
      where user_id = r.id and completed = false;
    affected := affected + 1;
  end loop;
  return affected;
end $$;

-- ── E. CLIENT-STARTUP PENALTY CATCH-UP — idempotent per day ──────────────────
-- Called by the app at startup/sync. Catches up missed days even if the cron
-- never ran (bounded compounding, 75% max bite). Admins exempt by law.
create or replace function public.sync_inactivity()
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  me uuid := auth.uid(); u public.users%rowtype;
  idle int; i int; total numeric := 0; decayed bigint; final_state text;
begin
  if me is null then raise exception 'unauthorized'; end if;
  select * into u from public.users where id = me;
  if not found then raise exception 'no_profile'; end if;
  if u.role <> 'USER' then
    return jsonb_build_object('result', 'EXEMPT', 'missed', 0);
  end if;
  idle := case when u.last_activity_date is null then 0
               else greatest(current_date - u.last_activity_date, 0) end;
  if idle < 1 then
    return jsonb_build_object('result', 'CLEAR', 'missed', 0);
  end if;
  if u.penalty_synced_at = current_date then
    return jsonb_build_object('result', 'ALREADY_SYNCED', 'missed', u.missed_days);
  end if;
  -- compound like the nightly engine would, capped: 14 idle days max bite, 75% floor-0
  for i in 1 .. least(idle, 14) loop
    total := total + 0.03 * (u.missed_days + i);
  end loop;
  decayed := floor(u.xp * least(total, 0.75))::bigint;
  final_state := case
    when u.missed_days + idle = 1 then 'WARNING'
    when u.missed_days + idle = 2 then 'DECAY'
    when u.missed_days + idle in (3,4) then 'GARBAGE'
    else 'LOSER' end;
  perform set_config('system.internal_write', 'on', true);
  update public.users
     set xp = xp - decayed,
         level = public.level_for_xp(xp - decayed),
         missed_days = missed_days + idle,
         streak_days = 0,
         penalty_state = final_state::penalty_state,
         penalty_synced_at = current_date
   where id = me;
  update public.user_arc_progress set penalty_extra_days = penalty_extra_days + idle
    where user_id = me and completed = false;
  insert into public.user_notifications (user_id, kind, title, body)
  values (me, 'PENALTY', 'INACTIVITY PENALTY APPLIED',
          idle || ' day(s) absent — ' || decayed || ' XP decayed. Status: ' || final_state ||
          '. Complete today''s quests to reset the clock.');
  return jsonb_build_object('result', 'PENALIZED', 'missed', idle, 'decayed', decayed, 'state', final_state);
end $$;
grant execute on function public.sync_inactivity() to authenticated;
revoke execute on function public.sync_inactivity() from anon;

-- ── F. PURGE JOB SALVAGE — row purge survives the storage trigger ────────────
create or replace function public.purge_workout_media() returns int
language plpgsql security definer set search_path = public as $$
declare n int;
begin
  with dead as (
    delete from public.workout_media
     where created_at < now() - interval '10 minutes'
    returning storage_path
  ) select count(*) into n from dead;
  -- storage.objects raw deletes are blocked by protect_delete (by design);
  -- object cleanup runs through the Storage API with the service key (ops runbook).
  return n;
end $$;
