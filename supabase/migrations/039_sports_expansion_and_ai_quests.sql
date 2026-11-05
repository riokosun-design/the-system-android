-- 039 — EXPANDED SPORTS ONBOARDING PROFILE + AI DAILY-QUEST SCHEMA (directive §4/§5)
-- Sport catalog 4 → 11; the hunter's sport experience level and training
-- environment persist on the profile; daily_quests gains the AI stamp
-- (ai_generated + scheduled_window) so the Groq brain writes directly into the
-- TODAY QUEST ledger. The template generator below stays the honest fall-back
-- floor — now wrapped in the SAME weekly periodization law the AI follows.

-- ── 1. SPORT CATALOG: 4 → 11 disciplines ────────────────────────────────────
alter table public.users drop constraint if exists users_sport_check;
alter table public.users add constraint users_sport_check
  check (sport is null or sport in
    ('FOOTBALL','CRICKET','BASKETBALL','BADMINTON','ATHLETICS','BOXING_MMA',
     'CALISTHENICS','SWIMMING','TENNIS','VOLLEYBALL','POWERLIFTING'));

-- ── 2. ATHLETE CALIBRATION COLUMNS (onboarding page 13 · BODY STATS editor) ──
alter table public.users add column if not exists sport_experience_level text
  check (sport_experience_level is null or sport_experience_level in
    ('BEGINNER','INTERMEDIATE','ADVANCED','COMPETITIVE'));
alter table public.users add column if not exists training_location text
  check (training_location is null or training_location in ('HOME','FIELD','BOTH'));

-- ── 3. DAILY QUESTS: the AI stamp + circadian window ────────────────────────
alter table public.daily_quests add column if not exists scheduled_window text;            -- 'HH:MM' circadian slot (directive §6), NULL on the floor
alter table public.daily_quests add column if not exists ai_generated boolean not null default false; -- honesty stamp: Groq-written rows carry TRUE

-- ── 4. NEW SPORT TEMPLATE POOLS (7 sports × 4 CONDITIONING + 4 TECHNICAL) ───
--    Proof law unchanged: sport rows verify by STEPS or TIMER only.
insert into public.daily_quest_templates
  (seq, title, exercise_kind, target_light, target_steady, target_unit,
   est_duration_sec, rest_sec, xp, difficulty, verification, active, sport, pillar, variant)
values
  -- ATHLETICS / SPRINTING
  (141,'AT ENGINE · 60M SPRINT REPEATS','SPRINT',420,600,'SEC',720,60,45,'HARD','TIMER',true,'ATHLETICS','CONDITIONING',0),
  (142,'AT ENGINE · TEMPO RUN','RUN',2000,3000,'METERS',1500,0,40,'MEDIUM','STEPS',true,'ATHLETICS','CONDITIONING',1),
  (143,'AT ENGINE · PLYO POWER CIRCUIT','CIRCUIT',480,720,'SEC',780,60,40,'MEDIUM','TIMER',true,'ATHLETICS','CONDITIONING',2),
  (144,'AT ENGINE · CORE & STABILITY BLOCK','CORE',420,600,'SEC',660,30,35,'MEDIUM','TIMER',true,'ATHLETICS','CONDITIONING',3),
  (145,'AT CRAFT · ACCELERATION WALL DRIVES','SKILL',420,600,'SEC',660,0,30,'EASY','TIMER',true,'ATHLETICS','TECHNICAL',0),
  (146,'AT CRAFT · SPRINT FORM DRILLS (A/B-SKIP)','SKILL',480,720,'SEC',720,0,30,'EASY','TIMER',true,'ATHLETICS','TECHNICAL',1),
  (147,'AT CRAFT · BLOCK-START SHADOW SETS','SKILL',360,540,'SEC',600,0,35,'MEDIUM','TIMER',true,'ATHLETICS','TECHNICAL',2),
  (148,'AT CRAFT · STRIDE-LINE CADENCE WORK','SKILL',420,600,'SEC',660,0,30,'EASY','TIMER',true,'ATHLETICS','TECHNICAL',3),
  -- BOXING / MMA
  (151,'BX ENGINE · ROADWORK RUN','RUN',2000,3000,'METERS',1500,0,40,'MEDIUM','STEPS',true,'BOXING_MMA','CONDITIONING',0),
  (152,'BX ENGINE · SHADOWBOX ROUND ENGINE','CIRCUIT',600,900,'SEC',960,60,45,'HARD','TIMER',true,'BOXING_MMA','CONDITIONING',1),
  (153,'BX ENGINE · FIGHTER CIRCUIT (BURPEE/CORE)','CIRCUIT',540,780,'SEC',840,60,40,'MEDIUM','TIMER',true,'BOXING_MMA','CONDITIONING',2),
  (154,'BX ENGINE · NECK & CORE ARMOUR','CORE',420,600,'SEC',660,30,35,'MEDIUM','TIMER',true,'BOXING_MMA','CONDITIONING',3),
  (155,'BX CRAFT · SHADOW COMBO SHARPNESS','SKILL',600,900,'SEC',960,0,30,'EASY','TIMER',true,'BOXING_MMA','TECHNICAL',0),
  (156,'BX CRAFT · SLIP-ROLL DEFENSE DRILL','SKILL',480,720,'SEC',720,0,30,'EASY','TIMER',true,'BOXING_MMA','TECHNICAL',1),
  (157,'BX CRAFT · WALL TARGET PAD-WORK','SKILL',540,780,'SEC',780,0,35,'MEDIUM','TIMER',true,'BOXING_MMA','TECHNICAL',2),
  (158,'BX CRAFT · PIVOT & FOOTWORK LADDER','SKILL',420,600,'SEC',660,0,30,'EASY','TIMER',true,'BOXING_MMA','TECHNICAL',3),
  -- CALISTHENICS
  (161,'CA ENGINE · DENSITY CIRCUIT','CIRCUIT',600,900,'SEC',960,60,40,'MEDIUM','TIMER',true,'CALISTHENICS','CONDITIONING',0),
  (162,'CA ENGINE · TEMPO PUSH/PULL VOLUME','STRENGTH',540,780,'SEC',840,60,40,'MEDIUM','TIMER',true,'CALISTHENICS','CONDITIONING',1),
  (163,'CA ENGINE · LEG VOLUME BLOCK','SQUAT',420,600,'SEC',660,45,40,'MEDIUM','TIMER',true,'CALISTHENICS','CONDITIONING',2),
  (164,'CA ENGINE · HOLLOW CORE BLOCK','CORE',420,600,'SEC',660,30,35,'MEDIUM','TIMER',true,'CALISTHENICS','CONDITIONING',3),
  (165,'CA CRAFT · WALL HANDSTAND SKILL','SKILL',360,540,'SEC',600,0,30,'EASY','TIMER',true,'CALISTHENICS','TECHNICAL',0),
  (166,'CA CRAFT · SCAPULA CONTROL DRILLS','SKILL',420,600,'SEC',660,0,30,'EASY','TIMER',true,'CALISTHENICS','TECHNICAL',1),
  (167,'CA CRAFT · L-SIT PROGRESSION HOLDS','SKILL',300,480,'SEC',540,0,35,'MEDIUM','TIMER',true,'CALISTHENICS','TECHNICAL',2),
  (168,'CA CRAFT · SLOW ECCENTRIC CONTROL SET','SKILL',420,600,'SEC',660,0,35,'MEDIUM','TIMER',true,'CALISTHENICS','TECHNICAL',3),
  -- SWIMMING
  (171,'SW ENGINE · DRYLAND SHOULDER CIRCUIT','CIRCUIT',420,600,'SEC',660,30,35,'MEDIUM','TIMER',true,'SWIMMING','CONDITIONING',0),
  (172,'SW ENGINE · AEROBIC BASE RUN','RUN',2000,3000,'METERS',1500,0,40,'MEDIUM','STEPS',true,'SWIMMING','CONDITIONING',1),
  (173,'SW ENGINE · BREATH-LADDER STAMINA SET','CORE',360,540,'SEC',600,30,35,'MEDIUM','TIMER',true,'SWIMMING','CONDITIONING',2),
  (174,'SW ENGINE · CORE LINE BLOCK','CORE',420,600,'SEC',660,30,35,'MEDIUM','TIMER',true,'SWIMMING','CONDITIONING',3),
  (175,'SW CRAFT · STREAMLINE WALL PUSH & HOLD','SKILL',300,480,'SEC',540,0,30,'EASY','TIMER',true,'SWIMMING','TECHNICAL',0),
  (176,'SW CRAFT · DRYLAND STROKE FORM REPS','SKILL',480,720,'SEC',720,0,30,'EASY','TIMER',true,'SWIMMING','TECHNICAL',1),
  (177,'SW CRAFT · TOWEL-STROKE PATH DRILL','SKILL',420,600,'SEC',660,0,30,'EASY','TIMER',true,'SWIMMING','TECHNICAL',2),
  (178,'SW CRAFT · FLUTTER-KICK WALL SETS','SKILL',360,540,'SEC',600,0,35,'MEDIUM','TIMER',true,'SWIMMING','TECHNICAL',3),
  -- TENNIS
  (181,'TE ENGINE · LATERAL SHUFFLE CIRCUIT','CIRCUIT',480,720,'SEC',780,60,40,'MEDIUM','TIMER',true,'TENNIS','CONDITIONING',0),
  (182,'TE ENGINE · AEROBIC BASE RUN','RUN',2000,3000,'METERS',1500,0,40,'MEDIUM','STEPS',true,'TENNIS','CONDITIONING',1),
  (183,'TE ENGINE · SPLIT-STEP PLYO BLOCK','PLYO',360,540,'SEC',600,45,40,'MEDIUM','TIMER',true,'TENNIS','CONDITIONING',2),
  (184,'TE ENGINE · ROTATIONAL CORE BLOCK','CORE',420,600,'SEC',660,30,35,'MEDIUM','TIMER',true,'TENNIS','CONDITIONING',3),
  (185,'TE CRAFT · SHADOW SWING VOLUME','SKILL',540,780,'SEC',780,0,30,'EASY','TIMER',true,'TENNIS','TECHNICAL',0),
  (186,'TE CRAFT · WALL VOLLEY TOUCH SESSION','SKILL',420,600,'SEC',660,0,30,'EASY','TIMER',true,'TENNIS','TECHNICAL',1),
  (187,'TE CRAFT · SERVE SHADOW SETS','SKILL',420,600,'SEC',660,0,30,'EASY','TIMER',true,'TENNIS','TECHNICAL',2),
  (188,'TE CRAFT · COURT FOOTWORK PATTERNS','SKILL',480,720,'SEC',720,0,35,'MEDIUM','TIMER',true,'TENNIS','TECHNICAL',3),
  -- VOLLEYBALL
  (191,'VB ENGINE · VERTICAL PLYO BLOCK','PLYO',360,540,'SEC',600,45,40,'MEDIUM','TIMER',true,'VOLLEYBALL','CONDITIONING',0),
  (192,'VB ENGINE · AGILITY SHUTTLE INTERVALS','SPRINT',420,600,'SEC',660,60,40,'MEDIUM','TIMER',true,'VOLLEYBALL','CONDITIONING',1),
  (193,'VB ENGINE · AEROBIC BASE RUN','RUN',2000,3000,'METERS',1500,0,40,'MEDIUM','STEPS',true,'VOLLEYBALL','CONDITIONING',2),
  (194,'VB ENGINE · SHOULDER & CORE ARMOUR','CORE',420,600,'SEC',660,30,35,'MEDIUM','TIMER',true,'VOLLEYBALL','CONDITIONING',3),
  (195,'VB CRAFT · WALL SET PRECISION SESSION','SKILL',420,600,'SEC',660,0,30,'EASY','TIMER',true,'VOLLEYBALL','TECHNICAL',0),
  (196,'VB CRAFT · WALL PASS PLATFORM DRILL','SKILL',420,600,'SEC',660,0,30,'EASY','TIMER',true,'VOLLEYBALL','TECHNICAL',1),
  (197,'VB CRAFT · APPROACH FOOTWORK STEPS','SKILL',360,540,'SEC',600,0,35,'MEDIUM','TIMER',true,'VOLLEYBALL','TECHNICAL',2),
  (198,'VB CRAFT · SHADOW SPIKE TIMING SETS','SKILL',420,600,'SEC',660,0,35,'MEDIUM','TIMER',true,'VOLLEYBALL','TECHNICAL',3),
  -- POWERLIFTING
  (201,'PL ENGINE · SQUAT VOLUME BLOCK','SQUAT',480,720,'SEC',780,60,45,'HARD','TIMER',true,'POWERLIFTING','CONDITIONING',0),
  (202,'PL ENGINE · HINGE PATTERN VOLUME','STRENGTH',420,600,'SEC',720,60,40,'MEDIUM','TIMER',true,'POWERLIFTING','CONDITIONING',1),
  (203,'PL ENGINE · LOADED-CARRY MARCH','WALK',1200,2000,'METERS',1200,0,40,'MEDIUM','STEPS',true,'POWERLIFTING','CONDITIONING',2),
  (204,'PL ENGINE · BRACING & CORE COLUMN','CORE',360,540,'SEC',600,30,35,'MEDIUM','TIMER',true,'POWERLIFTING','CONDITIONING',3),
  (205,'PL CRAFT · STICK BAR-PATH SHADOW LIFTS','SKILL',420,600,'SEC',660,0,30,'EASY','TIMER',true,'POWERLIFTING','TECHNICAL',0),
  (206,'PL CRAFT · PAUSE-TEMPO SQUAT SETS','SKILL',420,600,'SEC',660,0,35,'MEDIUM','TIMER',true,'POWERLIFTING','TECHNICAL',1),
  (207,'PL CRAFT · WALL HIP-HINGE PATTERNING','SKILL',360,540,'SEC',600,0,30,'EASY','TIMER',true,'POWERLIFTING','TECHNICAL',2),
  (208,'PL CRAFT · BRACE & SET-UP REHEARSAL','SKILL',300,480,'SEC',540,0,30,'EASY','TIMER',true,'POWERLIFTING','TECHNICAL',3)
on conflict (seq) do nothing;

-- ── 5. FLOOR GENERATOR: same template law, now under the PERIODIZATION LAW ──
--    §4.3 mandatory weekly cycle (isodow 1=Mon … 7=Sun):
--      Mon LIGHT · Tue HEAVY · Wed SUPER LIGHT · Thu LIGHT · Fri HEAVY
--      Sat MODERATE · Sun REST — floor targets scale by the same multipliers
--      the AI is instructed with; Sunday becomes an honest recovery protocol.
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
  v_dow int;
  v_intensity text;
  v_scale numeric;
begin
  select * into u from public.users where id = auth.uid();
  if not found then return; end if;
  -- PATCH LAW: only real protocol rows count — '[PENALTY TRIAL]' rows never
  -- satisfy the generator (else a penalty morning would erase the protocol).
  if exists(select 1 from public.daily_quests
             where user_id = u.id and quest_date = current_date
               and coalesce(source, '') <> 'PENALTY_TRIAL') then return; end if;

  -- §4.3 PERIODIZATION LAW (fallback floor honors it too)
  v_dow := extract(isodow from current_date)::int;
  v_intensity := case v_dow
    when 1 then 'LIGHT' when 2 then 'HEAVY' when 3 then 'SUPER LIGHT'
    when 4 then 'LIGHT' when 5 then 'HEAVY' when 6 then 'MODERATE'
    else 'REST' end;
  v_scale := case v_intensity
    when 'SUPER LIGHT' then 0.55 when 'LIGHT' then 0.85 when 'HEAVY' then 1.2
    when 'MODERATE' then 1.0 else 0.4 end; -- REST = 0.4 recovery floor

  -- adaptive tier: age is a hard conservative gate (≤16 always LIGHT);
  -- never BMR alone — experience, level and activity all weigh in.
  v_tier := case
    when u.age is not null and u.age <= 16 then 'LIGHT'
    when u.activity_level = 'RELENTLESS'
      or u.level >= 15
      or u.athletic_experience in ('ADVANCED','ATHLETE','EXPERIENCED') then 'STEADY'
    when u.activity_level = 'SEDENTARY' or u.level <= 2 then 'LIGHT'
    else 'STEADY' end;

  -- base protocol (unchanged law, unchanged order) — targets scaled by the
  -- periodization intensity of THIS weekday, titles honestly stamped.
  for t in select * from public.daily_quest_templates where active and sport is null order by seq loop
    v_seq := v_seq + 1;
    v_target := greatest(5, round((case v_tier when 'LIGHT' then t.target_light else t.target_steady end) * v_scale));
    v_status := case v_seq when 1 then 'READY' else 'LOCKED' end;
    insert into public.daily_quests
      (user_id, quest_date, seq, title, target_value, xp_reward, exercise_kind,
       target_unit, est_duration_sec, rest_sec, difficulty, verification, status, source)
    values
      (u.id, current_date, t.seq, v_intensity || ' · ' || t.title, v_target, t.xp, t.exercise_kind,
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
      when 'BASKETBALL' then 2 when 'BADMINTON' then 3
      when 'ATHLETICS' then 4 when 'BOXING_MMA' then 5
      when 'CALISTHENICS' then 6 when 'SWIMMING' then 7
      when 'TENNIS' then 8 when 'VOLLEYBALL' then 9
      when 'POWERLIFTING' then 10 else 0 end;
    v_epoch_day := floor(extract(epoch from current_date::timestamp) / 86400)::bigint;

    for t in
      select st.*, row_number() over (partition by st.pillar order by st.variant) - 1 as pool_pos,
             count(*) over (partition by st.pillar) as pool_size
      from public.daily_quest_templates st
      where st.active and st.sport = u.sport
    loop
      if mod(v_epoch_day + v_sport_ord, t.pool_size) = t.pool_pos then
        v_target := greatest(5, round((case v_tier when 'LIGHT' then t.target_light else t.target_steady end) * v_scale));
        insert into public.daily_quests
          (user_id, quest_date, seq, title, target_value, xp_reward, exercise_kind,
           target_unit, est_duration_sec, rest_sec, difficulty, verification, status, source)
        values
          (u.id, current_date, case t.pillar when 'CONDITIONING' then 20 else 21 end,
           v_intensity || ' · ' || t.title, v_target, t.xp, t.exercise_kind, t.target_unit, t.est_duration_sec,
           t.rest_sec, t.difficulty, t.verification, 'LOCKED',
           'SPORT_' || t.pillar)
        on conflict do nothing;
      end if;
    end loop;
  end if;
end $$;

update public.engine_config
   set value = coalesce(value, '{}'::jsonb) || '{"ai_daily_quests": "LIVE_039", "periodization": "MON_LIGHT TUE_HEAVY WED_SUPERLIGHT THU_LIGHT FRI_HEAVY SAT_MODERATE SUN_REST"}'::jsonb
 where key = 'sport_paths';
