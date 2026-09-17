-- 012 PHASE 3.2 — sequential daily quests, free prediction points, squat daily
-- challenges, real-named course catalog v2, admin-authored course quests,
-- personalized Black Room programs, missed-quest rollover.
-- Idempotent: safe to re-run on top of 010/011.

-- ── A. daily quest templates (admin-controlled via panel) ──────────────────
create table if not exists public.daily_quest_templates (
  id              bigint generated always as identity primary key,
  seq             int not null unique,
  title           text not null,
  exercise_kind   text not null,            -- PUSHUP SQUAT RUN WALK JACKS PLANK ...
  target_light    int not null,
  target_steady   int not null,
  target_unit     text not null default 'REPS', -- REPS | METERS | SECONDS
  est_duration_sec int not null default 300,
  rest_sec        int not null default 150,
  xp              int not null default 35,
  difficulty      text not null default 'EASY',
  verification    text not null default 'CAMERA', -- CAMERA|STEPS|TIMER|MANUAL
  active          boolean not null default true
);

insert into public.daily_quest_templates
  (seq,title,exercise_kind,target_light,target_steady,target_unit,est_duration_sec,rest_sec,xp,difficulty,verification)
values
 (1,'MORNING ACTIVATION','PUSHUP',8,15,'REPS',480,150,35,'EASY','CAMERA'),
 (2,'LOWER BODY BURST','SQUAT',12,20,'REPS',480,150,35,'EASY','CAMERA'),
 (3,'ENDURANCE RUN','RUN',1600,3200,'METERS',1500,0,50,'MEDIUM','STEPS')
on conflict (seq) do nothing;

-- ── B. daily_quests gains the protocol columns ─────────────────────────────
alter table public.daily_quests add column if not exists seq int not null default 1;
alter table public.daily_quests add column if not exists exercise_kind text;
alter table public.daily_quests add column if not exists target_unit text not null default 'REPS';
alter table public.daily_quests add column if not exists est_duration_sec int not null default 300;
alter table public.daily_quests add column if not exists rest_sec int not null default 150;
alter table public.daily_quests add column if not exists difficulty text not null default 'EASY';
alter table public.daily_quests add column if not exists verification text not null default 'CAMERA';
alter table public.daily_quests add column if not exists status text not null default 'READY';
-- LOCKED | READY | ACTIVE | VERIFYING | COMPLETE | MISSED | PENALIZED

update public.daily_quests
   set status = case when completed then 'COMPLETE'
                     when quest_date < current_date then 'MISSED'
                     else 'READY' end
 where status is null or status = '';

-- de-duplicate legacy rows (all pre-protocol quests defaulted to seq 1)
update public.daily_quests q set seq = sub.rn
from (
  select id, row_number() over (partition by user_id, quest_date order by id)::int as rn
    from public.daily_quests
) sub
where q.id = sub.id and sub.rn <> q.seq;

create unique index if not exists dq_user_day_seq
  on public.daily_quests (user_id, quest_date, seq);

-- ── C. free, non-redeemable spectator prediction points ────────────────────
alter table public.users
  add column if not exists prediction_points bigint not null default 0;

create table if not exists public.prediction_allowance (
  user_id    uuid not null references public.users(id) on delete cascade,
  day        date not null default current_date,
  points     int not null default 50,
  claimed_at timestamptz not null default now(),
  primary key (user_id, day)
);

create or replace function public.claim_prediction_allowance() returns int
language plpgsql security definer set search_path = public as $$
declare v_balance bigint;
begin
  insert into public.prediction_allowance (user_id, day)
  values (auth.uid(), current_date)
  on conflict do nothing;
  if not found then
    raise exception 'allowance_claimed';
  end if;
  update public.users set prediction_points = prediction_points + 50
   where id = auth.uid() returning prediction_points into v_balance;
  return v_balance;
end $$;

-- predictions now use FREE points only — never VC, never cashable
create or replace function public.place_bet(p_pool_id uuid, p_side text, p_amount bigint)
returns void
language plpgsql security definer set search_path = public as $$
declare p public.prediction_pools%rowtype; me uuid := auth.uid(); v_odds numeric;
begin
  if p_side not in ('A','B') then raise exception 'bad_side'; end if;
  if p_amount < 5 then raise exception 'min_backing_5'; end if;
  select * into p from public.prediction_pools where id = p_pool_id for update;
  if not found then raise exception 'no_pool'; end if;
  if p.status <> 'OPEN' then raise exception 'pool_locked'; end if;
  if exists(select 1 from public.battles b where b.id = p.battle_id and me in (b.player_a, b.player_b)) then
    raise exception 'no_self_bet';
  end if;
  if (select prediction_points from public.users where id = me) < p_amount then
    raise exception 'not_enough_points';
  end if;

  v_odds := round(
    (p.total_pool_vc + p_amount) * (10000 - p.platform_cut_bps) / 10000.0
    / nullif(case p_side when 'A' then p.total_a_vc else p.total_b_vc end + p_amount, 0), 2);

  insert into public.prediction_bets (pool_id, user_id, side, amount_vc, odds_multiplier)
  values (p.id, me, p_side, p_amount, v_odds)
  on conflict (pool_id, user_id) do nothing;
  if not found then raise exception 'already_bet'; end if;

  update public.users set prediction_points = prediction_points - p_amount where id = me;
  update public.prediction_pools
     set total_pool_vc = total_pool_vc + p_amount,
         total_a_vc = total_a_vc + case when p_side = 'A' then p_amount else 0 end,
         total_b_vc = total_b_vc + case when p_side = 'B' then p_amount else 0 end
   where id = p.id;
end $$;

create or replace function public.refund_pool(p_battle_id uuid)
returns void language plpgsql security definer set search_path = public as $$
declare p public.prediction_pools%rowtype; r record;
begin
  select * into p from public.prediction_pools where battle_id = p_battle_id for update;
  if not found or p.status = 'REFUNDED' then return; end if;
  for r in select * from public.prediction_bets where pool_id = p.id and status = 'OPEN' loop
    update public.users set prediction_points = prediction_points + r.amount_vc where id = r.user_id;
    update public.prediction_bets set status = 'REFUNDED', payout_vc = amount_vc where id = r.id;
  end loop;
  update public.prediction_pools set status = 'REFUNDED', settled_at = now() where id = p.id;
end $$;

create or replace function public.settle_prediction_pool(p_pool_id uuid, p_winner text)
returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  pool public.prediction_pools%rowtype;
  r record; win_total bigint; distributable numeric; pay bigint;
  winners int := 0; losers int := 0;
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  if p_winner not in ('A','B') then raise exception 'bad_side'; end if;
  select * into pool from public.prediction_pools where id = p_pool_id for update;
  if not found then raise exception 'no_pool'; end if;
  if pool.status in ('SETTLED','REFUNDED') then raise exception 'already_settled'; end if;

  distributable := floor(pool.total_pool_vc * (10000 - pool.platform_cut_bps) / 10000.0);
  win_total := case when p_winner = 'A' then pool.total_a_vc else pool.total_b_vc end;

  if win_total = 0 or pool.total_pool_vc = 0 then
    perform public.refund_pool(pool.battle_id);
    return jsonb_build_object('mode', 'refund');
  end if;

  for r in select * from public.prediction_bets where pool_id = p_pool_id and status = 'OPEN' loop
    if r.side = p_winner then
      pay := floor((r.amount_vc::numeric / win_total) * distributable);
      -- winnings are FREE non-redeemable points, never VC or cash
      update public.users set prediction_points = prediction_points + pay where id = r.user_id;
      update public.prediction_bets set status = 'WON', payout_vc = pay where id = r.id;
      winners := winners + 1;
    else
      update public.prediction_bets set status = 'LOST', payout_vc = 0 where id = r.id;
      losers := losers + 1;
    end if;
  end loop;

  update public.prediction_pools
     set status = 'SETTLED', winning_side = p_winner, settled_at = now()
   where id = p_pool_id;
  return jsonb_build_object('mode','settled','winners',winners,'losers',losers,
                            'distributable',distributable,'winning_pool',win_total);
end $$;

-- ── D. sequential daily quest engine ───────────────────────────────────────
create or replace function public.ensure_daily_quests() returns void
language plpgsql security definer set search_path = public as $$
declare u public.users%rowtype; v_tier text; t record; v_seq int := 0; v_target int; v_status text;
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

  for t in select * from public.daily_quest_templates where active order by seq loop
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
end $$;

create or replace function public.log_quest_proof(
  p_quest_id bigint, p_kind text, p_amount int, p_duration_sec int default 0
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare q public.daily_quests%rowtype; v_new int;
begin
  if p_kind not in ('QUEST_PUSH','QUEST_SQUAT','QUEST_RUN','QUEST_WALK',
                    'QUEST_STEPS','QUEST_TIMER','QUEST_MANUAL','QUEST_CAMERA') then
    raise exception 'bad_kind';
  end if;
  if p_amount <= 0 then raise exception 'bad_amount'; end if;
  select * into q from public.daily_quests where id = p_quest_id and user_id = auth.uid() for update;
  if not found then raise exception 'no_quest'; end if;
  if q.completed or q.status = 'COMPLETE' then raise exception 'already_done'; end if;
  if q.status = 'LOCKED' then raise exception 'quest_locked'; end if;
  if q.status in ('MISSED','PENALIZED') then raise exception 'quest_closed'; end if;

  insert into public.workouts (user_id, kind, reps, duration_sec)
  values (auth.uid(), p_kind,
          case when p_kind in ('QUEST_RUN','QUEST_WALK','QUEST_STEPS','QUEST_TIMER') then 0 else p_amount end,
          greatest(p_duration_sec, 0));

  v_new := least(q.progress + p_amount, q.target_value);
  update public.daily_quests
     set progress = v_new,
         status = case when v_new >= target_value then 'VERIFYING' else 'ACTIVE' end
   where id = q.id;
  return jsonb_build_object('progress', v_new, 'target', q.target_value,
                            'complete', v_new >= q.target_value, 'status',
                            case when v_new >= q.target_value then 'VERIFYING' else 'ACTIVE' end);
end $$;

create or replace function public.complete_quest(p_quest_id bigint) returns void
language plpgsql security definer set search_path = public as $$
declare q public.daily_quests%rowtype;
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
end $$;

-- ── E. daily battle challenges: PUSH-UP + SQUAT, rank-adapted ──────────────
create or replace function public.daily_battle_challenges() returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare u public.users%rowtype; v_required int; out jsonb := '[]'::jsonb; k text;
begin
  select * into u from public.users where id = auth.uid();
  if not found then return out; end if;
  -- conservative scaling: no extreme tiers
  v_required := case when u.level >= 20 then 6 when u.level >= 8 then 3 else 2 end;
  foreach k in array array['PUSH','SQUAT'] loop
    out := out || jsonb_build_object(
      'id', k,
      'required', v_required,
      'wins', (select count(*) from public.battles
                where status = 'FINISHED' and winner = auth.uid()
                  and exercise_type = case k when 'PUSH' then 'PUSHUP' else 'SQUAT' end
                  and finished_at::date = current_date),
      'claimed', exists(select 1 from public.workouts where user_id = auth.uid()
                         and kind = 'BATTLE_CHALLENGE_' || k and created_at::date = current_date),
      'exercise', case k when 'PUSH' then 'PUSH-UP BATTLES' else 'SQUAT BATTLES' end);
  end loop;
  return out;
end $$;

-- keep the old singular name working for older clients
create or replace function public.daily_battle_challenge()
returns jsonb language sql stable security definer set search_path = public as $$
  select public.daily_battle_challenges() -> 0;
$$;

create or replace function public.claim_battle_challenge(p_kind text default 'PUSH')
returns void language plpgsql security definer set search_path = public as $$
declare c jsonb; v_required int; v_wins int; v_ex text;
begin
  if p_kind not in ('PUSH','SQUAT') then raise exception 'bad_kind'; end if;
  select x into c from unnest(public.daily_battle_challenges()) x
   where x->>'id' = p_kind;
  if c is null then raise exception 'no_challenge'; end if;
  v_required := (c->>'required')::int;
  v_wins := (c->>'wins')::int;
  if (c->>'claimed')::boolean then raise exception 'already_claimed'; end if;
  if v_wins < v_required then raise exception 'challenge_incomplete'; end if;
  insert into public.workouts (user_id, kind, reps, duration_sec, xp_earned)
  values (auth.uid(), 'BATTLE_CHALLENGE_' || p_kind, v_required, 0, 120);
  perform public.apply_xp(auth.uid(), 120, null);
  perform public.credit_vc(auth.uid(), 25, 'QUEST_REWARD', 'battle_challenge_' || p_kind || ':' || current_date);
end $$;

-- ── F. catalog v2: real-world training names only ──────────────────────────
alter table public.courses add column if not exists difficulty text not null default 'MEDIUM';
alter table public.courses add column if not exists target text;
alter table public.courses add column if not exists equipment text;

-- retire every fantasy-name / archetype seed; canonical set is upserted below
update public.courses set active = false;

insert into public.courses
  (slug, title, type, attribute, target, equipment, difficulty, description,
   duration_months, schedule_json, image_url, sort)
values
 ('adaptive-fighter-physique','Adaptive Fighter Physique','MUSCLE',null,'FULL BODY','BODYWEIGHT / HOUSEHOLD','MEDIUM',
  'The flagship adaptive track: upper/lower/legs split that scales sets and rest from verified performance, age and recovery. Sessions 30-120 minutes with bodyweight, bricks or water bottles.',
  6, '{"days":{"0":"UPPER","1":"REST","2":"LOWER","3":"REST","4":"LEGS","5":"MOBILITY","6":"UPPER"},"session_min":[30,120],"rest_sec_between_sets":[120,300],"recommended_hour":"04:30"}'::jsonb,
  'file:///android_asset/courses/adaptive-fighter-physique.webp', 10),
 ('calisthenics-foundation','Calisthenics Foundation','MUSCLE',null,'PUSH/PULL/CORE','BODYWEIGHT','EASY',
  'Bodyweight strength fundamentals: push-up, pull, squat and hinge progressions with strict form gates.',
  3, '{"days":{"0":"PUSH","1":"REST","2":"PULL","3":"REST","4":"LEGS_CORE","5":"REST","6":"SKILL"},"session_min":[30,60],"rest_sec_between_sets":[90,240]}'::jsonb,
  'file:///android_asset/courses/calisthenics-foundation.webp', 11),
 ('athletic-strength-build','Athletic Strength Build','MUSCLE',null,'FULL BODY','BODYWEIGHT / LOADED','HARD',
  'Strength plus athletic conditioning — explosive lifts, carries and conditioning circuits.',
  6, '{"days":{"0":"STRENGTH","1":"CONDITIONING","2":"REST","3":"STRENGTH","4":"REST","5":"CONDITIONING","6":"REST"},"session_min":[40,100],"rest_sec_between_sets":[120,300]}'::jsonb,
  'file:///android_asset/courses/athletic-strength-build.webp', 12),
 ('lean-muscle-development','Lean Muscle Development','MUSCLE',null,'V-TAPER PHYSIQUE','BODYWEIGHT / BANDS','MEDIUM',
  'Progressive bodyweight and limited-equipment muscle development for a lean, sculpted V-taper.',
  6, '{"days":{"0":"CHEST_BACK","1":"REST","2":"ARMS","3":"REST","4":"SHOULDERS","5":"REST","6":"LATS"},"session_min":[40,90],"rest_sec_between_sets":[90,240]}'::jsonb,
  'file:///android_asset/courses/lean-muscle-development.webp', 13),
 ('home-strength-system','Home Strength System','MUSCLE',null,'FULL BODY','HOUSEHOLD OBJECTS','EASY',
  'Home-based strength training using bodyweight, water bottles, bricks and doorframe pulls.',
  3, '{"days":{"0":"FULL","1":"REST","2":"FULL","3":"MOBILITY","4":"REST","5":"FULL","6":"REST"},"session_min":[30,70],"rest_sec_between_sets":[90,240]}'::jsonb,
  'file:///android_asset/courses/home-strength-system.webp', 14),
 ('functional-muscle-build','Functional Muscle Build','MUSCLE',null,'MOVEMENT PATTERNS','BODYWEIGHT / LOADED CARRY','MEDIUM',
  'Strength built through practical movement patterns: squat, hinge, carry, push, pull and locomotion.',
  6, '{"days":{"0":"HINGE_PULL","1":"REST","2":"CARRY_CORE","3":"REST","4":"SQUAT_PUSH","5":"REST","6":"FLOW"},"session_min":[35,90],"rest_sec_between_sets":[120,300]}'::jsonb,
  'file:///android_asset/courses/functional-muscle-build.webp', 15),
 ('bodyweight-hypertrophy','Bodyweight Hypertrophy','MUSCLE',null,'CHEST/ARMS/BACK','BODYWEIGHT','MEDIUM',
  'Progressive bodyweight muscle development with hypertrophy rep ranges and density blocks.',
  6, '{"days":{"0":"PUSH_VOL","1":"REST","2":"PULL_VOL","3":"REST","4":"LEGS","5":"REST","6":"ARMS_CORE"},"session_min":[40,95],"rest_sec_between_sets":[90,240]}'::jsonb,
  'file:///android_asset/courses/bodyweight-hypertrophy.webp', 16),
 ('full-body-strength-cycle','Full-Body Strength Cycle','MUSCLE',null,'FULL BODY','BODYWEIGHT / LOADED','HARD',
  'Structured full-body strength cycles with wave-loaded progression and deload weeks.',
  9, '{"days":{"0":"FULL_A","1":"REST","2":"MOBILITY","3":"FULL_B","4":"REST","5":"CONDITIONING","6":"REST"},"session_min":[45,120],"rest_sec_between_sets":[150,300]}'::jsonb,
  'file:///android_asset/courses/full-body-strength-cycle.webp', 17),
 ('upper-lower-split','Upper/Lower Split','MUSCLE',null,'UPPER / LOWER','BODYWEIGHT / LOADED','MEDIUM',
  'Classic upper-body / lower-body structured progression, four sessions per week.',
  6, '{"days":{"0":"UPPER","1":"LOWER","2":"REST","3":"UPPER","4":"LOWER","5":"REST","6":"MOBILITY"},"session_min":[40,100],"rest_sec_between_sets":[120,300]}'::jsonb,
  'file:///android_asset/courses/upper-lower-split.webp', 18),
 ('beginner-strength-foundation','Beginner Strength Foundation','MUSCLE',null,'FOUNDATION','BODYWEIGHT','EASY',
  'Entry-level progressive training. Conservative load, movement mastery, daily consistency first.',
  2, '{"days":{"0":"FULL","1":"WALK","2":"REST","3":"FULL","4":"MOBILITY","5":"WALK","6":"REST"},"session_min":[25,45],"rest_sec_between_sets":[90,180]}'::jsonb,
  'file:///android_asset/courses/beginner-strength-foundation.webp', 19)
on conflict (slug) do update set
  title=excluded.title, type=excluded.type, target=excluded.target, equipment=excluded.equipment,
  difficulty=excluded.difficulty, description=excluded.description,
  duration_months=excluded.duration_months, schedule_json=excluded.schedule_json,
  image_url=excluded.image_url, sort=excluded.sort, active=true;

-- PERFORMANCE DEVELOPMENT AREA (specials): max 5 active per user, rest-day slots
insert into public.courses
  (slug, title, type, attribute, target, equipment, difficulty, description, duration_months, schedule_json, image_url, sort)
values
 ('speed-development','Speed Development','SPECIAL','SPEED','SPRINT MECHANICS','BODYWEIGHT','MEDIUM','Hill sprints, stride openers and acceleration repeats. Sessions sit on recovery days.',6,'{"rest_days_only":true,"session_min":[20,40]}'::jsonb,'file:///android_asset/courses/speed-development.webp',30),
 ('agility-training','Agility Training','SPECIAL','AGILITY','FOOTWORK','BODYWEIGHT','MEDIUM','Ladders, direction changes and shadow rounds.',6,'{"rest_days_only":true,"session_min":[20,40]}'::jsonb,'file:///android_asset/courses/agility-training.webp',31),
 ('reflex-training','Reflex Training','SPECIAL','REFLEXES','REACTION','BODYWEIGHT','MEDIUM','Reaction drills, slip rope and hand-speed intervals.',6,'{"rest_days_only":true,"session_min":[20,40]}'::jsonb,'file:///android_asset/courses/reflex-training.webp',32),
 ('mobility-training','Mobility Training','SPECIAL','MOBILITY','RANGE OF MOTION','BODYWEIGHT','EASY','Hip opening, squat depth and morning mobility protocol.',6,'{"rest_days_only":true,"session_min":[20,40]}'::jsonb,'file:///android_asset/courses/mobility-training.webp',33),
 ('flexibility-training','Flexibility Training','SPECIAL','FLEXIBILITY','DEEP STRETCH','BODYWEIGHT','EASY','Deep stretching chains, split work and pass-throughs.',6,'{"rest_days_only":true,"session_min":[20,40]}'::jsonb,'file:///android_asset/courses/flexibility-training.webp',34),
 ('endurance-training','Endurance Training','SPECIAL','ENDURANCE','AEROBIC ENGINE','BODYWEIGHT','MEDIUM','Plank progressions, loaded carries and zone breathing.',6,'{"rest_days_only":true,"session_min":[20,40]}'::jsonb,'file:///android_asset/courses/endurance-training.webp',35),
 ('stamina-conditioning','Stamina Conditioning','SPECIAL','STAMINA','CARDIOVASCULAR','BODYWEIGHT','HARD','Tempo runs, shadow rounds and breathing-under-load intervals.',6,'{"rest_days_only":true,"session_min":[20,40]}'::jsonb,'file:///android_asset/courses/stamina-conditioning.webp',36),
 ('grip-strength','Grip Strength','SPECIAL','GRIP','FOREARMS / GRIP','BAR / TOWEL','MEDIUM','Dead hangs, towel holds and brick carries.',6,'{"rest_days_only":true,"session_min":[20,40]}'::jsonb,'file:///android_asset/courses/grip-strength.webp',37),
 ('leg-power-development','Leg Power Development','SPECIAL','LEG_POWER','EXPLOSIVE LEGS','BODYWEIGHT','HARD','Jump progressions, depth landings and single-leg explosive lifts.',6,'{"rest_days_only":true,"session_min":[20,40]}'::jsonb,'file:///android_asset/courses/leg-power-development.webp',38),
 ('punch-power-athletics','Punch Power / Striking Athletics','SPECIAL','PUNCH_POWER','STRIKING CHAIN','BODYWEIGHT','HARD','Explosive push patterns, brick-bag chains and hip-drive kinetic linking.',6,'{"rest_days_only":true,"session_min":[20,40]}'::jsonb,'file:///android_asset/courses/punch-power-athletics.webp',39),
 ('the-black-room','Forbidden Course: Black Room','FORBIDDEN',null,'BESPOKE 2-YEAR TRACK','ADMIN DESIGNED','HARD',
  'A two-year personalized transformation protocol, renewed every six months and hand-built from the hunter''s biometric logs by the Super Admin.',
  24,'{"renewal_days":180,"review":"super_admin_bespoke"}'::jsonb,'file:///android_asset/courses/black-room.webp',90)
on conflict (slug) do update set
  title=excluded.title, type=excluded.type, attribute=excluded.attribute, target=excluded.target,
  equipment=excluded.equipment, difficulty=excluded.difficulty, description=excluded.description,
  duration_months=excluded.duration_months, schedule_json=excluded.schedule_json,
  image_url=excluded.image_url, sort=excluded.sort, active=true;

-- ADMIN-AUTHORED course quests: COURSE → WEEK → DAY → QUEST
create table if not exists public.course_quests (
  id           bigint generated always as identity primary key,
  course_id    uuid not null references public.courses(id) on delete cascade,
  week         int not null check (week between 0 and 104),
  day          int not null check (day between 0 and 6),
  sort         int not null default 0,
  title        text not null,
  description  text not null default '',
  exercise     text not null default 'GENERAL',
  sets         int not null default 3,
  reps         text not null default '10',
  duration_sec int not null default 0,
  rest_sec     int not null default 120,
  xp           int not null default 20,
  difficulty   text not null default 'MEDIUM',
  verification text not null default 'CAMERA',
  equipment    text not null default 'BODYWEIGHT',
  alternatives text not null default '',
  min_age      int,
  max_age      int,
  min_level    int not null default 1,
  active       boolean not null default true,
  created_at   timestamptz not null default now(),
  unique (course_id, week, day, sort)
);

insert into public.course_quests
  (course_id, week, day, sort, title, description, exercise, sets, reps, duration_sec, rest_sec, xp, difficulty, verification)
select c.id, v.week, v.day, v.sort, v.title, v.description, v.exercise, v.sets, v.reps, v.duration, v.rest, v.xp, v.diff, v.verif
from public.courses c
cross join (values
 (0,0,1,'UPPER PUSH ACTIVATION','Scapular wake-up then push-up ladder.','PUSHUP',4,'8-15',0,120,25,'EASY','CAMERA'),
 (0,2,1,'LOWER PULL FOUNDATION','Deep squat holds and split squats.','SQUAT',4,'10-15',0,120,25,'EASY','CAMERA'),
 (0,4,1,'LEG ENGINE','Step-up and calf drive circuit.','LUNGE',3,'12',0,120,25,'MEDIUM','CAMERA'),
 (0,6,1,'MOBILITY + CORE FINISHER','Hip openers and hollow hold.','CORE',3,'30s',180,90,20,'EASY','TIMER')
) as v(week,day,sort,title,description,exercise,sets,reps,duration,rest,xp,diff,verif)
where c.slug = 'adaptive-fighter-physique'
on conflict do nothing;

-- ── G. PERSONALIZED BLACK ROOM PROGRAMS ────────────────────────────────────
create table if not exists public.black_room_programs (
  id             bigint generated always as identity primary key,
  application_id uuid references public.black_room_applications(id) on delete set null,
  user_id        uuid not null references public.users(id) on delete cascade,
  week           int not null default 0,
  day            int not null default 0,
  title          text not null,
  exercise       text not null default 'GENERAL',
  sets           int not null default 3,
  reps           text not null default '10',
  duration_sec   int not null default 0,
  rest_sec       int not null default 150,
  notes          text not null default '',
  created_by     uuid references public.users(id),
  created_at     timestamptz not null default now(),
  unique (user_id, week, day, title)
);

create or replace function public.black_room_program() returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare rows jsonb;
begin
  select coalesce(jsonb_agg(j order by week, day), '[]'::jsonb) into rows from (
    select jsonb_build_object('week', week, 'day', day, 'title', title, 'exercise', exercise,
      'sets', sets, 'reps', reps, 'duration_sec', duration_sec, 'rest_sec', rest_sec, 'notes', notes) j
    from public.black_room_programs where user_id = auth.uid()
  ) t;
  return rows;
end $$;

-- ── H. Black Room gate: spec requires ≥5 historical missed/penalty events ──
create or replace function public.black_room_eligibility(p_country text default 'IN')
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare
  u public.users%rowtype;
  v_forms int; v_muscle_pct numeric; v_specials int;
  v_upi numeric; v_usd numeric; v_upi_idx int; v_consistency numeric;
  v_win_rate int; v_wars int; stats jsonb;
  c_forms boolean; c_level boolean; c_missed boolean; c_muscle boolean; c_special boolean;
begin
  select * into u from public.users where id = auth.uid();
  if not found then return '{}'::jsonb; end if;

  select count(*) into v_forms from public.user_forms where user_id = u.id;
  select coalesce(max(uc.progress_percent),0) into v_muscle_pct
    from public.user_courses uc join public.courses c on c.id = uc.course_id
   where uc.user_id = u.id and c.type = 'MUSCLE';
  select count(*) into v_specials from public.user_courses uc
    join public.courses c on c.id = uc.course_id
   where uc.user_id = u.id and c.type = 'SPECIAL' and uc.status = 'COMPLETED';

  c_forms   := v_forms >= 1;
  c_level   := u.level >= 50;
  c_missed  := u.missed_days >= 5;  -- history/depth gate per spec
  c_muscle  := v_muscle_pct >= 50;
  c_special := v_specials >= 3;

  stats := public.hunter_battle_stats(u.id);
  v_win_rate := coalesce((stats->>'win_rate')::int, 0);
  v_wars := coalesce((stats->>'total')::int, 0);
  v_consistency := least(1.0, u.streak_days / 30.0) * 0.6
                 + least(1.0, v_wars / 20.0) * 0.2
                 + (v_win_rate / 100.0) * 0.2;
  v_upi_idx := greatest(0, least(4, round(v_consistency * 4)::int));
  v_upi := (array[199,299,399,499,599])[v_upi_idx + 1];
  v_usd := (array[5,8,11,15,19])[v_upi_idx + 1];

  return jsonb_build_object(
    'eligible', c_forms and c_level and c_missed and c_muscle and c_special,
    'checks', jsonb_build_object(
      'form_unlocked', c_forms, 'level_50', c_level,
      'penalty_events_min5', c_missed, 'muscle_progress_ok', c_muscle,
      'specials_completed_ok', c_special,
      'forms', v_forms, 'muscle_percent', v_muscle_pct, 'specials_completed', v_specials,
      'missed_days', u.missed_days),
    'upi', case when upper(coalesce(p_country,'IN')) in ('IN','PK','BD','NP','LK') then v_upi end,
    'usd', v_usd,
    'potential_index', v_upi_idx);
end $$;

-- scheduled matches with opponent names for the client
create or replace view public.scheduled_matches_with_names
with (security_invoker = true) as
select m.*, p1.username as player1_name, p2.username as player2_name
  from public.scheduled_matches m
  join public.users p1 on p1.id = m.player1_id
  join public.users p2 on p2.id = m.player2_id;
grant select on public.scheduled_matches_with_names to authenticated;

-- ── I. ADMIN RPCs (content ships without an app update) ────────────────────
create or replace function public.admin_upsert_course(
  p_slug text, p_title text, p_type text, p_description text,
  p_duration_months int, p_schedule jsonb, p_attribute text default null,
  p_image_url text default null, p_difficulty text default 'MEDIUM',
  p_target text default 'FULL BODY', p_equipment text default 'BODYWEIGHT',
  p_active boolean default true, p_sort int default 50
) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  insert into public.courses
    (slug,title,type,attribute,target,equipment,difficulty,description,duration_months,schedule_json,image_url,active,sort)
  values (p_slug,p_title,p_type,p_attribute,p_target,p_equipment,p_difficulty,p_description,
          p_duration_months,p_schedule,p_image_url,p_active,p_sort)
  on conflict (slug) do update set
    title=excluded.title, type=excluded.type, attribute=excluded.attribute,
    target=excluded.target, equipment=excluded.equipment, difficulty=excluded.difficulty,
    description=excluded.description, duration_months=excluded.duration_months,
    schedule_json=excluded.schedule_json, image_url=excluded.image_url,
    active=excluded.active, sort=excluded.sort;
end $$;

create or replace function public.admin_upsert_course_quest(
  p_course_slug text, p_week int, p_day int, p_sort int,
  p_title text, p_description text, p_exercise text, p_sets int, p_reps text,
  p_duration_sec int, p_rest_sec int, p_xp int, p_difficulty text, p_verification text,
  p_equipment text, p_alternatives text, p_min_age int default null, p_max_age int default null,
  p_min_level int default 1, p_active boolean default true
) returns void
language plpgsql security definer set search_path = public as $$
declare v_course uuid;
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  select id into v_course from public.courses where slug = p_course_slug;
  if v_course is null then raise exception 'no_course'; end if;
  insert into public.course_quests
    (course_id,week,day,sort,title,description,exercise,sets,reps,duration_sec,rest_sec,xp,
     difficulty,verification,equipment,alternatives,min_age,max_age,min_level,active)
  values (v_course,p_week,p_day,p_sort,p_title,p_description,p_exercise,p_sets,p_reps,
          p_duration_sec,p_rest_sec,p_xp,p_difficulty,p_verification,p_equipment,p_alternatives,
          p_min_age,p_max_age,p_min_level,p_active)
  on conflict (course_id,week,day,sort) do update set
    title=excluded.title, description=excluded.description, exercise=excluded.exercise,
    sets=excluded.sets, reps=excluded.reps, duration_sec=excluded.duration_sec,
    rest_sec=excluded.rest_sec, xp=excluded.xp, difficulty=excluded.difficulty,
    verification=excluded.verification, equipment=excluded.equipment,
    alternatives=excluded.alternatives, min_age=excluded.min_age, max_age=excluded.max_age,
    min_level=excluded.min_level, active=excluded.active;
end $$;

create or replace function public.admin_upsert_quest_template(
  p_seq int, p_title text, p_kind text, p_light int, p_steady int, p_unit text,
  p_est_sec int, p_rest_sec int, p_xp int, p_difficulty text, p_verification text, p_active boolean
) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  insert into public.daily_quest_templates
    (seq,title,exercise_kind,target_light,target_steady,target_unit,
     est_duration_sec,rest_sec,xp,difficulty,verification,active)
  values (p_seq,p_title,p_kind,p_light,p_steady,p_unit,p_est_sec,p_rest_sec,p_xp,
          p_difficulty,p_verification,p_active)
  on conflict (seq) do update set
    title=excluded.title, exercise_kind=excluded.exercise_kind,
    target_light=excluded.target_light, target_steady=excluded.target_steady,
    target_unit=excluded.target_unit, est_duration_sec=excluded.est_duration_sec,
    rest_sec=excluded.rest_sec, xp=excluded.xp, difficulty=excluded.difficulty,
    verification=excluded.verification, active=excluded.active;
end $$;

create or replace function public.admin_save_black_room_program(
  p_application uuid, p_week int, p_day int, p_title text, p_exercise text,
  p_sets int, p_reps text, p_duration_sec int, p_rest_sec int, p_notes text
) returns void
language plpgsql security definer set search_path = public as $$
declare a public.black_room_applications%rowtype;
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  select * into a from public.black_room_applications where id = p_application;
  if not found then raise exception 'no_application'; end if;
  insert into public.black_room_programs
    (application_id,user_id,week,day,title,exercise,sets,reps,duration_sec,rest_sec,notes,created_by)
  values (p_application,a.user_id,p_week,p_day,p_title,p_exercise,p_sets,p_reps,
          p_duration_sec,p_rest_sec,p_notes,auth.uid())
  on conflict (user_id,week,day,title) do update set
    exercise=excluded.exercise, sets=excluded.sets, reps=excluded.reps,
    duration_sec=excluded.duration_sec, rest_sec=excluded.rest_sec, notes=excluded.notes,
    application_id=excluded.application_id;
end $$;

-- ── J. missed-quest rollover (penalty decay stays in apply_daily_penalties) ─
create or replace function public.mark_missed_quests() returns int
language plpgsql security definer set search_path = public as $$
declare n int;
begin
  with upd as (
    update public.daily_quests set status = 'MISSED'
     where completed = false and status not in ('MISSED','PENALIZED')
       and quest_date < current_date
     returning 1
  ) select count(*) into n from upd;
  return n;
end $$;

do $do$ begin
  if not exists(select 1 from cron.job where jobname = 'daily-missed-quests') then
    perform cron.schedule('daily-missed-quests', '8 0 * * *',
      $$select public.mark_missed_quests();$$);
  end if;
exception when undefined_table then null;
end $do$;

-- ── K. RLS / grants ────────────────────────────────────────────────────────
alter table public.daily_quest_templates enable row level security;
alter table public.course_quests enable row level security;
alter table public.black_room_programs enable row level security;
alter table public.prediction_allowance enable row level security;

drop policy if exists dqt_read on public.daily_quest_templates;
create policy dqt_read on public.daily_quest_templates for select using (active or public.is_admin());
drop policy if exists dqt_admin on public.daily_quest_templates;
create policy dqt_admin on public.daily_quest_templates for all using (public.is_admin()) with check (public.is_admin());

drop policy if exists cq_read on public.course_quests;
create policy cq_read on public.course_quests for select using (active or public.is_admin());
drop policy if exists cq_admin on public.course_quests;
create policy cq_admin on public.course_quests for all using (public.is_admin()) with check (public.is_admin());

drop policy if exists brp_owner on public.black_room_programs;
create policy brp_owner on public.black_room_programs for select
  using (user_id = auth.uid() or public.is_admin());
drop policy if exists brp_admin on public.black_room_programs;
create policy brp_admin on public.black_room_programs for all
  using (public.is_admin()) with check (public.is_admin());

drop policy if exists pa_owner on public.prediction_allowance;
create policy pa_owner on public.prediction_allowance for select
  using (user_id = auth.uid());

grant select on public.daily_quest_templates, public.course_quests to authenticated;
grant execute on function public.claim_prediction_allowance() to authenticated;
grant execute on function public.daily_battle_challenges() to authenticated;
grant execute on function public.daily_battle_challenge() to authenticated;
grant execute on function public.claim_battle_challenge(text) to authenticated;
grant execute on function public.black_room_program() to authenticated;
grant execute on function public.admin_upsert_course(text,text,text,text,int,jsonb,text,text,text,text,text,boolean,int) to authenticated;
grant execute on function public.admin_upsert_course_quest(text,int,int,int,text,text,text,int,text,int,int,int,text,text,text,text,int,int,int,boolean) to authenticated;
grant execute on function public.admin_upsert_quest_template(int,text,text,int,int,text,int,int,int,text,text,boolean) to authenticated;
grant execute on function public.admin_save_black_room_program(uuid,int,int,text,text,int,text,int,int,text) to authenticated;
