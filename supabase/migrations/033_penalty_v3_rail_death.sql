-- ════════════════════════════════════════════════════════════════════════════
-- THE SYSTEM — 033: HARDCORE PENALTY ENGINE v3 + MANUAL PAYMENT RAIL DEATH
-- ─────────────────────────────────────────────────────────────────────────────
-- 2026-10-20 directive (§4 & §8):
--   §8 PENALTY v3 — tiered, ruthless, server-owned; admins strictly exempt:
--     1 missed day  → Penalty Zone: XP decay + streak 0 + camera-verified
--                     Survival Trial (50 push-ups + 50 squats, 0 XP).
--     3+ days       → heavier per-day decay; rank demotion is inherent via
--                     level = level_for_xp(xp). Trial grows to 100/100.
--     7+ days       → SYSTEM LOCKDOWN & STAT CORRUPTION: XP collapses to the
--                     floor of its tier; trial_active/lockdown flags seal the
--                     app until the Redemption Trial clears (150/150).
--   Trials live in daily_quests (source='PENALTY_TRIAL', CAMERA-verified,
--   xp_reward 0) so the ENTIRE existing proof pipeline stays authoritative.
--   complete_quest clears the flags only when NO trial rows remain open.
--   §4 RAIL DEATH — the manual UPI/QR/UTR rail dies server-side too: the
--   three client-facing mint/proof/withdrawal RPCs lose authenticated+anon
--   execute. Tables, audit rows and admin review functions stay (financial
--   history is never deleted).
-- ════════════════════════════════════════════════════════════════════════════

-- ── A. COLUMNS + ENUM ────────────────────────────────────────────────────────
alter table public.users add column if not exists penalty_trial_active boolean not null default false;
alter table public.users add column if not exists penalty_lockdown boolean not null default false;
alter table public.users add column if not exists penalty_xp_lost_total bigint not null default 0;
alter type public.penalty_state add value if not exists 'LOCKDOWN';

-- ── B. USER GUARD v4 — trial flags/join ledger join the forbidden set ────────
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
       or new.penalty_trial_active is distinct from old.penalty_trial_active
       or new.penalty_lockdown is distinct from old.penalty_lockdown
       or new.penalty_xp_lost_total is distinct from old.penalty_xp_lost_total
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

-- ── C. TRIAL LEDGER — camera-verified punishment rows (idempotent per day) ───
create or replace function public.ensure_penalty_trial(p_user uuid, p_pushups int, p_squats int)
returns void language plpgsql security definer set search_path = public as $$
begin
  insert into public.daily_quests
    (user_id, quest_date, seq, title, target_value, xp_reward, exercise_kind,
     target_unit, est_duration_sec, rest_sec, difficulty, verification, status, source)
  values
    (p_user, current_date, 90, '[PENALTY TRIAL] ' || p_pushups || ' VERIFIED PUSH-UPS',
     p_pushups, 0, 'PUSHUP', 'REPS', 420, 120, 'BRUTAL', 'CAMERA', 'READY', 'PENALTY_TRIAL'),
    (p_user, current_date, 91, '[PENALTY TRIAL] ' || p_squats || ' VERIFIED SQUATS',
     p_squats, 0, 'SQUAT', 'REPS', 420, 120, 'BRUTAL', 'CAMERA', 'READY', 'PENALTY_TRIAL')
  on conflict do nothing;
end $$;
revoke all on function public.ensure_penalty_trial(uuid, int, int) from public, anon, authenticated;

-- ── D. PENALTY WORKER — one law, called by the cron AND the client startup ───
create or replace function public.penalize_user_row(p_user uuid) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  u public.users%rowtype;
  idle int; i int; total numeric := 0; decayed bigint;
  v_newxp bigint; v_floor bigint;
  missed_total int; tier int; final_state text; v_reps int;
begin
  perform set_config('system.internal_write', 'on', true);
  select * into u from public.users where id = p_user for update;
  if not found then return jsonb_build_object('result', 'no_profile'); end if;
  if u.role <> 'USER' then
    return jsonb_build_object('result', 'EXEMPT', 'missed', 0);   -- §8.2: admins immune, always
  end if;
  idle := case when u.last_activity_date is null then 0
               else greatest(current_date - u.last_activity_date, 0) end;
  if idle < 1 then
    return jsonb_build_object('result', 'CLEAR', 'missed', 0);
  end if;
  if u.penalty_synced_at = current_date then
    -- already bled today — keep the sentence provisioned, never double-charge
    if u.penalty_trial_active then
      v_reps := case when u.penalty_lockdown then 150 when u.missed_days >= 3 then 100 else 50 end;
      perform public.ensure_penalty_trial(p_user, v_reps, v_reps);
    end if;
    return jsonb_build_object('result', 'ALREADY_SYNCED', 'missed', u.missed_days);
  end if;

  -- §8.3 tiered decay: day1 3% · days 2–3 6% · days 4–6 8% · day 7+ 10% (75% cap)
  for i in 1 .. least(idle, 14) loop
    total := total + case when u.missed_days + i = 1 then 0.03
                          when u.missed_days + i <= 3 then 0.06
                          when u.missed_days + i <= 6 then 0.08
                          else 0.10 end;
  end loop;
  decayed := floor(u.xp * least(total, 0.75))::bigint;
  v_newxp := greatest(u.xp - decayed, 0);
  missed_total := u.missed_days + idle;
  tier := case when missed_total >= 7 then 7 when missed_total >= 3 then 3 else 1 end;
  if tier = 7 then
    -- STAT CORRUPTION: XP collapses to the floor of its current tier
    v_floor := public.xp_required_for_level(public.level_for_xp(v_newxp));
    decayed := decayed + (v_newxp - v_floor);
    v_newxp := v_floor;
  end if;
  final_state := case when missed_total = 1 then 'WARNING'
                      when missed_total = 2 then 'DECAY'
                      when missed_total <= 6 then 'GARBAGE'
                      else 'LOCKDOWN' end;

  update public.users
     set xp = v_newxp,
         level = public.level_for_xp(v_newxp),           -- demotion is inherent here
         missed_days = missed_total,
         streak_days = 0,
         penalty_state = final_state::public.penalty_state,
         penalty_synced_at = current_date,
         penalty_trial_active = true,
         penalty_lockdown = (tier = 7),
         penalty_xp_lost_total = penalty_xp_lost_total + decayed
   where id = p_user;

  update public.user_arc_progress set penalty_extra_days = penalty_extra_days + idle
    where user_id = p_user and completed = false;

  v_reps := case tier when 7 then 150 when 3 then 100 else 50 end;
  perform public.ensure_penalty_trial(p_user, v_reps, v_reps);

  insert into public.user_notifications (user_id, kind, title, body)
  values (p_user, 'PENALTY',
    case tier when 7 then '[ ! ] SYSTEM LOCKDOWN' when 3 then '[ ! ] RANK DEMOTION PENALTY' else 'INACTIVITY PENALTY APPLIED' end,
    idle || ' day(s) absent — ' || decayed || ' XP burned. State: ' || final_state ||
    case tier when 7 then '. Stats corrupted to tier floor. Arena, chat and rewards are sealed until the Redemption Trial clears.'
              when 3 then '. Hunter rank demoted. Clear the survival trial to reopen the System.'
              else '. Streak reset to zero. Clear the survival trial to reopen the System.' end);

  return jsonb_build_object('result', 'PENALIZED', 'missed', idle, 'tier', tier,
                            'decayed', decayed, 'state', final_state);
end $$;
-- lethal in the wrong hands: internal-only, no client surface
revoke all on function public.penalize_user_row(uuid) from public, anon, authenticated;

-- ── E. CLIENT-STARTUP CATCH-UP (unchanged name/signature; now tier-brutal) ───
create or replace function public.sync_inactivity() returns jsonb
language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid();
begin
  if me is null then raise exception 'unauthorized'; end if;
  return public.penalize_user_row(me);
end $$;

-- ── F. FULL PENALTY VERDICT — audit first, then the complete truth ───────────
create or replace function public.penalty_status() returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  me uuid := auth.uid(); u public.users%rowtype;
  v_req_p int := 0; v_done_p int := 0; v_req_s int := 0; v_done_s int := 0;
  v_rem int := 0; v_total int := 0;
begin
  if me is null then raise exception 'unauthorized'; end if;
  perform public.sync_inactivity();                      -- single audit authority
  select * into u from public.users where id = me;
  if not found then raise exception 'no_profile'; end if;
  if u.role <> 'USER' then
    return jsonb_build_object('state', 'EXEMPT', 'missed', 0, 'tier', 0,
      'trial_active', false, 'lockdown', false, 'xp_lost', 0, 'remaining', 0, 'trial_rows', 0);
  end if;
  select count(*),
         coalesce(sum(case when exercise_kind = 'PUSHUP' then target_value end), 0),
         coalesce(sum(case when exercise_kind = 'PUSHUP' then least(progress, target_value) end), 0),
         coalesce(sum(case when exercise_kind = 'SQUAT' then target_value end), 0),
         coalesce(sum(case when exercise_kind = 'SQUAT' then least(progress, target_value) end), 0)
    into v_total, v_req_p, v_done_p, v_req_s, v_done_s
    from public.daily_quests where user_id = me and source = 'PENALTY_TRIAL';
  select count(*) into v_rem from public.daily_quests
   where user_id = me and source = 'PENALTY_TRIAL' and not completed;
  return jsonb_build_object(
    'state', u.penalty_state::text,
    'missed', u.missed_days,
    'tier', case when u.penalty_lockdown then 7 when u.missed_days >= 3 then 3
                 when u.penalty_trial_active then 1 else 0 end,
    'trial_active', u.penalty_trial_active,
    'lockdown', u.penalty_lockdown,
    'xp_lost', u.penalty_xp_lost_total,
    'pushups_required', v_req_p, 'pushups_done', v_done_p,
    'squats_required', v_req_s, 'squats_done', v_done_s,
    'remaining', v_rem,
    'trial_rows', v_total);
end $$;
grant execute on function public.penalty_status() to authenticated;
revoke execute on function public.penalty_status() from anon;

-- ── G. NIGHTLY ENGINE v3 — same job, same name, now tier-brutal + admin-exempt ─
create or replace function public.apply_daily_penalties() returns int
language plpgsql security definer set search_path = public as $$
declare r record; affected int := 0;
begin
  for r in
    select id from public.users
     where role = 'USER'
       and last_activity_date is not null
       and last_activity_date < current_date
       and coalesce(penalty_synced_at, current_date - 1) < current_date
  loop
    perform public.penalize_user_row(r.id);
    affected := affected + 1;
  end loop;
  return affected;
end $$;

-- ── H. QUEST COMPLETION v2 — the trial ledger rules the flags ────────────────
create or replace function public.complete_quest(p_quest_id bigint) returns void
language plpgsql security definer set search_path = public as $$
declare
  q public.daily_quests%rowtype;
  v_remaining int; v_lock boolean; v_trial boolean;
begin
  select * into q from public.daily_quests where id = p_quest_id and user_id = auth.uid() for update;
  if not found then raise exception 'no_quest'; end if;
  if q.completed or q.status = 'COMPLETE' then raise exception 'already_done'; end if;
  if q.progress < q.target_value then raise exception 'quest_not_verified'; end if;

  update public.daily_quests set completed = true, progress = target_value, status = 'COMPLETE'
   where id = q.id;
  perform public.apply_xp(auth.uid(), q.xp_reward, null);

  -- unlock the next block in today's sequence
  update public.daily_quests set status = 'READY'
   where user_id = auth.uid() and quest_date = q.quest_date
     and status = 'LOCKED' and seq = (
       select min(seq) from public.daily_quests
        where user_id = auth.uid() and quest_date = q.quest_date and status = 'LOCKED');

  -- ── 0.24.0 §8: the redemption ledger ─────────────────────────────────────
  perform set_config('system.internal_write', 'on', true);
  select count(*) into v_remaining from public.daily_quests
   where user_id = auth.uid() and source = 'PENALTY_TRIAL' and not completed;
  select coalesce(penalty_trial_active, false), coalesce(penalty_lockdown, false)
    into v_trial, v_lock from public.users where id = auth.uid();
  if v_trial and v_remaining = 0 then
    update public.users
       set penalty_trial_active = false,
           penalty_lockdown = false,
           penalty_state = 'CLEAR',
           penalty_synced_at = current_date
     where id = auth.uid();
    insert into public.user_notifications (user_id, kind, title, body)
    values (auth.uid(), 'PENALTY', 'PENALTY TRIAL CLEARED',
            'Debt paid in sweat, on camera. The seal is lifted — the System expects you before it has to ask next time.');
  elsif v_trial and v_remaining > 0 then
    -- apply_xp wiped the display state; the sentence still stands
    update public.users
       set penalty_state = case when v_lock then 'LOCKDOWN'::public.penalty_state else 'WARNING'::public.penalty_state end
     where id = auth.uid();
  end if;
end $$;

-- ── I. DAILY GENERATOR — trial rows must NEVER suppress protocol generation ──
create or replace function public.ensure_daily_quests() returns void
language plpgsql security definer set search_path = public as $$
declare
  u public.users%rowtype;
  v_tier text;
  t record;
  v_seq int := 0;
  v_target int;
  v_status text;
  v_flag boolean;
  v_sport_ord int;
  v_epoch_day bigint;
begin
  select * into u from public.users where id = auth.uid();
  if not found then return; end if;
  -- PATCHED: only real protocol rows count — '[PENALTY TRIAL]' rows do not
  -- satisfy the generator (else a penalty morning would erase the protocol).
  if exists(select 1 from public.daily_quests
             where user_id = u.id and quest_date = current_date
               and coalesce(source, '') <> 'PENALTY_TRIAL') then return; end if;

  -- adaptive tier: age is a hard conservative gate (≤16 always LIGHT);
  -- never BMR alone — experience, level and activity all weigh in.
  v_tier := case
    when u.age is not null and u.age <= 16 then 'LIGHT'
    when u.activity_level = 'RELENTLESS'
      or u.level >= 15
      or u.athletic_experience in ('ADVANCED','ATHLETE','EXPERIENCED') then 'STEADY'
    when u.activity_level = 'SEDENTARY' or u.level <= 2 then 'LIGHT'
    else 'STEADY' end;

  -- base protocol (unchanged law, unchanged order)
  for t in select * from public.daily_quest_templates where active and sport is null order by seq loop
    v_seq := v_seq + 1;
    v_target := case v_tier when 'LIGHT' then t.target_light else t.target_steady end;
    v_status := case v_seq when 1 then 'READY' else 'LOCKED' end;
    insert into public.daily_quests
      (user_id, quest_date, seq, title, target_value, xp_reward, exercise_kind,
       target_unit, est_duration_sec, rest_sec, difficulty, verification, status, source)
    values
      (u.id, current_date, t.seq, t.title, v_target, t.xp, t.exercise_kind,
       t.target_unit, t.est_duration_sec, t.rest_sec, t.difficulty, t.verification, v_status,
       'DAILY_PROTOCOL')
    on conflict do nothing;
  end loop;

  -- sport floor: one conditioning + one technical quest, deterministic cycle.
  -- NULL sport (default / explicit opt-out) = no sport rows, ever.
  select (value #>> '{}') = 'on' into v_flag from public.engine_config where key = 'sport_paths';
  if u.sport is not null and coalesce(v_flag, false) then
    v_sport_ord := case u.sport
      when 'FOOTBALL' then 0 when 'CRICKET' then 1
      when 'BASKETBALL' then 2 when 'BADMINTON' then 3 else 0 end;
    v_epoch_day := floor(extract(epoch from current_date::timestamp) / 86400)::bigint;

    for t in
      select st.*, row_number() over (partition by st.pillar order by st.variant) - 1 as pool_pos,
             count(*) over (partition by st.pillar) as pool_size
      from public.daily_quest_templates st
      where st.active and st.sport = u.sport
    loop
      if mod(v_epoch_day + v_sport_ord, t.pool_size) = t.pool_pos then
        v_target := case v_tier when 'LIGHT' then t.target_light else t.target_steady end;
        insert into public.daily_quests
          (user_id, quest_date, seq, title, target_value, xp_reward, exercise_kind,
           target_unit, est_duration_sec, rest_sec, difficulty, verification, status, source)
        values
          (u.id, current_date, case t.pillar when 'CONDITIONING' then 20 else 21 end,
           t.title, v_target, t.xp, t.exercise_kind, t.target_unit, t.est_duration_sec,
           t.rest_sec, t.difficulty, t.verification, 'LOCKED',
           'SPORT_' || t.pillar)
        on conflict do nothing;
      end if;
    end loop;
  end if;
end $$;

-- ── J. MANUAL PAYMENT RAIL — EXECUTION DEATH (rows & history preserved) ──────
revoke execute on function public.create_vc_order(text) from authenticated, anon;
revoke execute on function public.submit_vc_order_proof(uuid, text, text, jsonb) from authenticated, anon;
revoke execute on function public.request_withdrawal(bigint, text, jsonb) from authenticated, anon;

-- the redacted config now tells the truth: the manual rail is gone
update public.engine_config
   set value = coalesce(value, '{}'::jsonb)
       || '{"gateway": "OFFLINE_RAZORPAY_PENDING", "manual_rail_removed": "0.24.0"}'::jsonb
 where key = 'monetization';
