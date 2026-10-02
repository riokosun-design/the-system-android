-- ═══════════════════════════════════════════════════════════════════════════
-- MIGRATION 022 · SPORT PATHS (STEP 6)
--
-- The athlete branch: a hunter may declare ONE sport during onboarding (or
-- explicitly opt out — NULL stays the default and means NO sport content
-- anywhere). Opted-in hunters receive one CONDITIONING + one TECHNICAL floor
-- quest per day, rotated deterministically from seeded template pools.
--
-- Safety rails:
--  - feature flag engine_config 'sport_paths' starts 'off'; flipped live only
--    after the client that understands the surface ships green.
--  - existing hunters: column defaults NULL → nothing changes for a single
--    existing account.
--  - deterministic rotation: (utc_epoch_day + sport_ordinal) % pool — client
--    kotlin core/sport/Sport.kt carries the same formula (predict vs decide).
-- ═══════════════════════════════════════════════════════════════════════════

-- ── 1 · profile column: NULL = no path (default), else a declared sport ─────
alter table public.users add column if not exists sport text;
do $$
begin
  if not exists (select 1 from pg_constraint where conname = 'users_sport_check') then
    alter table public.users add constraint users_sport_check
      check (sport is null or sport in ('FOOTBALL','CRICKET','BASKETBALL','BADMINTON'));
  end if;
end $$;

-- ── 2 · feature flag (off until the client ships) ───────────────────────────
insert into public.engine_config (key, value)
values ('sport_paths', '"off"'::jsonb)
on conflict (key) do nothing;

-- ── 3 · template taxonomy for sport rows ────────────────────────────────────
alter table public.daily_quest_templates add column if not exists sport text;      -- NULL = base protocol
alter table public.daily_quest_templates add column if not exists pillar text;     -- CONDITIONING | TECHNICAL (sport rows)
alter table public.daily_quest_templates add column if not exists variant int;     -- 0..pool-1 rotation slot

-- ── 4 · seeds — four sports × (4 conditioning + 4 technical) ────────────────
--    Template `seq` is a UNIQUE row id (>= 101 for sport pools); the ORDER a
--    quest appears at for a user is fixed by the function (20 cond / 21 tech).
--    Proof law: only modes the app can VERIFY end-to-end — STEPS (meters
--    from the hardware counter) and TIMER (elapsed seconds, server-clamped).
--    Skill volume is time-boxed: a 10-minute wall-touch session is honest,
--    countable proof; a client-claimed '200 reps' is not. Seconds only.

insert into public.daily_quest_templates
  (seq, title, exercise_kind, target_light, target_steady, target_unit,
   est_duration_sec, rest_sec, xp, difficulty, verification, active, sport, pillar, variant)
values
  (101, 'FB ENGINE · AEROBIC BASE RUN', 'RUN', 2000, 3000, 'METERS', 1500, 0, 40, 'MEDIUM', 'STEPS', true, 'FOOTBALL', 'CONDITIONING', 0),
  (102, 'FB ENGINE · TEMPO INTERVAL RUN', 'RUN', 1600, 2400, 'METERS', 1200, 60, 40, 'MEDIUM', 'STEPS', true, 'FOOTBALL', 'CONDITIONING', 1),
  (103, 'FB ENGINE · SPRINT INTERVAL SESSION', 'SPRINT', 480, 720, 'SEC', 720, 60, 45, 'HARD', 'TIMER', true, 'FOOTBALL', 'CONDITIONING', 2),
  (104, 'FB ENGINE · MATCH-ENDURANCE CIRCUIT', 'CIRCUIT', 600, 900, 'SEC', 960, 90, 40, 'MEDIUM', 'TIMER', true, 'FOOTBALL', 'CONDITIONING', 3),
  (105, 'FB CRAFT · WALL FIRST-TOUCH SESSION', 'SKILL', 600, 900, 'SEC', 960, 0, 30, 'EASY', 'TIMER', true, 'FOOTBALL', 'TECHNICAL', 0),
  (106, 'FB CRAFT · BOTH-FOOT WALL PASS SESSION', 'SKILL', 600, 900, 'SEC', 960, 0, 30, 'EASY', 'TIMER', true, 'FOOTBALL', 'TECHNICAL', 1),
  (107, 'FB CRAFT · CONE DRIBBLE LAPS', 'DRIBBLE', 420, 600, 'SEC', 660, 30, 35, 'MEDIUM', 'TIMER', true, 'FOOTBALL', 'TECHNICAL', 2),
  (108, 'FB CRAFT · LONG-BALL TARGET SESSION', 'PASSING', 420, 600, 'SEC', 660, 30, 35, 'MEDIUM', 'TIMER', true, 'FOOTBALL', 'TECHNICAL', 3),
  (109, 'CR ENGINE · WICKET-SPRINT SHUTTLES', 'SPRINT', 420, 600, 'SEC', 660, 60, 40, 'MEDIUM', 'TIMER', true, 'CRICKET', 'CONDITIONING', 0),
  (110, 'CR ENGINE · AEROBIC BASE RUN', 'RUN', 2000, 3000, 'METERS', 1500, 0, 40, 'MEDIUM', 'STEPS', true, 'CRICKET', 'CONDITIONING', 1),
  (111, 'CR ENGINE · ROTATIONAL CORE CIRCUIT', 'CORE', 420, 600, 'SEC', 660, 30, 35, 'MEDIUM', 'TIMER', true, 'CRICKET', 'CONDITIONING', 2),
  (112, 'CR ENGINE · FIELDING AGILITY SQUARE', 'AGILITY', 480, 720, 'SEC', 780, 60, 40, 'MEDIUM', 'TIMER', true, 'CRICKET', 'CONDITIONING', 3),
  (113, 'CR CRAFT · SHADOW BATTING GROOVE', 'BATTING', 480, 720, 'SEC', 780, 0, 30, 'EASY', 'TIMER', true, 'CRICKET', 'TECHNICAL', 0),
  (114, 'CR CRAFT · TARGET BOWLING SPELL', 'BOWLING', 600, 900, 'SEC', 960, 30, 35, 'MEDIUM', 'TIMER', true, 'CRICKET', 'TECHNICAL', 1),
  (115, 'CR CRAFT · THROW ACCURACY SESSION', 'FIELDING', 420, 600, 'SEC', 660, 30, 30, 'EASY', 'TIMER', true, 'CRICKET', 'TECHNICAL', 2),
  (116, 'CR CRAFT · HIGH-CATCH ROUTINE', 'FIELDING', 360, 540, 'SEC', 600, 30, 30, 'EASY', 'TIMER', true, 'CRICKET', 'TECHNICAL', 3),
  (117, 'BB ENGINE · COURT SPRINT LADDER', 'SPRINT', 420, 600, 'SEC', 660, 60, 40, 'MEDIUM', 'TIMER', true, 'BASKETBALL', 'CONDITIONING', 0),
  (118, 'BB ENGINE · LATERAL SLIDE HOLD', 'DEFENSE', 360, 600, 'SEC', 660, 30, 35, 'MEDIUM', 'TIMER', true, 'BASKETBALL', 'CONDITIONING', 1),
  (119, 'BB ENGINE · VERTICAL POWER SESSION', 'PLYO', 420, 600, 'SEC', 660, 45, 40, 'MEDIUM', 'TIMER', true, 'BASKETBALL', 'CONDITIONING', 2),
  (120, 'BB ENGINE · INTERVAL RUN', 'RUN', 1600, 2400, 'METERS', 1200, 60, 40, 'MEDIUM', 'STEPS', true, 'BASKETBALL', 'CONDITIONING', 3),
  (121, 'BB CRAFT · FORM SHOOTING TOUCH', 'SHOOTING', 480, 720, 'SEC', 780, 0, 30, 'EASY', 'TIMER', true, 'BASKETBALL', 'TECHNICAL', 0),
  (122, 'BB CRAFT · WEAK-HAND HANDLE', 'DRIBBLE', 600, 900, 'SEC', 960, 0, 35, 'MEDIUM', 'TIMER', true, 'BASKETBALL', 'TECHNICAL', 1),
  (123, 'BB CRAFT · FOOTWORK SERIES SESSION', 'FOOTWORK', 420, 600, 'SEC', 660, 30, 35, 'MEDIUM', 'TIMER', true, 'BASKETBALL', 'TECHNICAL', 2),
  (124, 'BB CRAFT · FREE-THROW RITUAL', 'SHOOTING', 420, 600, 'SEC', 660, 30, 30, 'EASY', 'TIMER', true, 'BASKETBALL', 'TECHNICAL', 3),
  (125, 'BD ENGINE · SIX-POINT SHADOW FOOTWORK', 'FOOTWORK', 360, 540, 'SEC', 600, 45, 40, 'MEDIUM', 'TIMER', true, 'BADMINTON', 'CONDITIONING', 0),
  (126, 'BD ENGINE · COURT INTERVAL RUN', 'RUN', 1600, 2400, 'METERS', 1200, 60, 40, 'MEDIUM', 'STEPS', true, 'BADMINTON', 'CONDITIONING', 1),
  (127, 'BD ENGINE · LOW-LUNGE STABILITY', 'STABILITY', 360, 540, 'SEC', 600, 30, 35, 'MEDIUM', 'TIMER', true, 'BADMINTON', 'CONDITIONING', 2),
  (128, 'BD ENGINE · ROPE-SKIP ENGINE', 'CARDIO', 480, 720, 'SEC', 780, 30, 40, 'MEDIUM', 'TIMER', true, 'BADMINTON', 'CONDITIONING', 3),
  (129, 'BD CRAFT · WALL-RALLY CONTROL', 'RALLY', 480, 720, 'SEC', 780, 0, 30, 'EASY', 'TIMER', true, 'BADMINTON', 'TECHNICAL', 0),
  (130, 'BD CRAFT · SERVE ACCURACY BOX', 'SERVE', 420, 600, 'SEC', 660, 30, 30, 'EASY', 'TIMER', true, 'BADMINTON', 'TECHNICAL', 1),
  (131, 'BD CRAFT · CLEAR-LIFT CONSISTENCY', 'RALLY', 480, 720, 'SEC', 780, 30, 35, 'MEDIUM', 'TIMER', true, 'BADMINTON', 'TECHNICAL', 2),
  (132, 'BD CRAFT · NET-KILL PRECISION', 'NETPLAY', 360, 540, 'SEC', 600, 30, 35, 'MEDIUM', 'TIMER', true, 'BADMINTON', 'TECHNICAL', 3)
on conflict do nothing;

-- ── 5 · ensure_daily_quests v2 — base protocol first, then the sport floor ──
create or replace function public.ensure_daily_quests()
returns void
language plpgsql
security definer
set search_path to 'public'
as $function$
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
  if exists(select 1 from public.daily_quests where user_id = u.id and quest_date = current_date) then return; end if;

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
end $function$;
