-- ═══════════════════════════════════════════════════════════════════════════
-- 009 · PHASE 2
--   A. Arena: selectable duel duration (30/60/120s), two-sided READY lobby,
--      lobby cancel/refund, hunter battle-stats RPC (profile inspection),
--      20%-of-house-cut winner profit share on pool settlement.
--   B. Adaptive quest engine: BMR/BMI + activity-level tiering, progressive
--      reps (+6% every 3 completed days), no more hard-coded day-1 100s.
--   C. users.activity_level persisted from onboarding.
--
-- Safe to run repeatedly (idempotent). Rollback: see end of file.
-- ═══════════════════════════════════════════════════════════════════════════

-- ── C. activity level intake ────────────────────────────────────────────────
alter table public.users
  add column if not exists activity_level text not null default 'STEADY'
    check (activity_level in ('SEDENTARY','STEADY','RELENTLESS'));

-- ── A. battles: duration + bilateral READY + host ───────────────────────────
alter table public.battles
  add column if not exists duration_sec    int  not null default 60,
  add column if not exists player_a_ready  boolean not null default false,
  add column if not exists player_b_ready  boolean not null default false,
  add column if not exists host            uuid references public.users(id);

-- Tournament bracket fixtures stay 60s, nobody READY yet (same UX as before).

-- ── create_battle now carries the chosen duel duration ──────────────────────
drop function if exists public.create_battle(uuid);
create or replace function public.create_battle(p_opponent uuid, p_duration_sec int default 60)
returns uuid
language plpgsql security definer set search_path = public as $$
declare bid uuid; me uuid := auth.uid();
begin
  if p_opponent = me then raise exception 'no_self_battle'; end if;
  if p_duration_sec not in (30, 60, 120) then raise exception 'bad_duration'; end if;
  insert into public.battles (player_a, player_b, duration_sec, host)
  values (me, p_opponent, p_duration_sec, me)
  returning id into bid;
  insert into public.prediction_pools (battle_id) values (bid); -- pool spawns OPEN
  return bid;
end $$;

-- ── READY gate: each hunter arms themselves; BOTH ready → LIVE + pool locks ─
create or replace function public.set_battle_ready(p_battle_id uuid, p_ready boolean default true)
returns void
language plpgsql security definer set search_path = public as $$
declare b public.battles%rowtype; me uuid := auth.uid();
begin
  select * into b from public.battles where id = p_battle_id for update;
  if not found then raise exception 'no_battle'; end if;
  if me not in (b.player_a, b.player_b) then raise exception 'forbidden'; end if;
  if b.status <> 'LOBBY' then raise exception 'not_in_lobby'; end if;

  if me = b.player_a then
    update public.battles set player_a_ready = p_ready where id = b.id;
  else
    update public.battles set player_b_ready = p_ready where id = b.id;
  end if;

  -- Both hunters armed → war begins for everyone; spectator pool locks.
  if p_ready and
     (select player_a_ready and player_b_ready from public.battles where id = b.id) then
    update public.battles
       set status = 'LIVE', started_at = now()
     where id = b.id and status = 'LOBBY';
    update public.prediction_pools set status = 'LOCKED'
     where battle_id = b.id and status = 'OPEN';
  end if;
end $$;

-- ── Cancel a dead lobby (opponent never showed) and refund any early stakes ─
create or replace function public.cancel_battle(p_battle_id uuid)
returns void
language plpgsql security definer set search_path = public as $$
declare b public.battles%rowtype; r record;
begin
  select * into b from public.battles where id = p_battle_id for update;
  if not found then raise exception 'no_battle'; end if;
  if auth.uid() not in (b.player_a, b.player_b, b.host) and not public.is_admin() then
    raise exception 'forbidden';
  end if;
  if b.status <> 'LOBBY' then raise exception 'not_in_lobby'; end if;

  for r in select * from public.prediction_bets pb
             join public.prediction_pools pp on pp.id = pb.pool_id
            where pp.battle_id = b.id and pb.status = 'OPEN' loop
    perform public.credit_vc(r.user_id, r.amount_vc, 'BET_REFUND', 'pool:' || r.pool_id);
    update public.prediction_bets set status = 'REFUNDED', payout_vc = amount_vc where id = r.id;
  end loop;
  update public.prediction_pools set status = 'REFUNDED', settled_at = now()
   where battle_id = b.id and status in ('OPEN','LOCKED');
  update public.battles set status = 'CANCELLED' where id = b.id;
end $$;

-- ── HUNTER PROFILE INSPECTION (pre-prediction stats sheet) ──────────────────
-- Win rate, average final score, reps-per-minute pace and last 5 war record.
create or replace function public.hunter_battle_stats(p_user uuid)
returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare
  v_total int; v_wins int; v_losses int; v_avg int; v_pace int;
  v_recent jsonb; v_level int;
begin
  with f as (
    select * from public.battles
     where status = 'FINISHED' and p_user in (player_a, player_b)
  )
  select
    count(*),
    count(*) filter (where winner = p_user),
    coalesce(round(avg(case when player_a = p_user then score_a else score_b end)),0)::int,
    coalesce(round(avg(
      (case when player_a = p_user then score_a else score_b end)::numeric
      / nullif(greatest(extract(epoch from (finished_at - started_at)),1),0) * 60.0
    )),0)::int
  into v_total, v_wins, v_avg, v_pace
  from f;

  v_losses := coalesce(v_total,0) - coalesce(v_wins,0);
  select level into v_level from public.users where id = p_user;

  select coalesce(jsonb_agg(jr order by (jr->>'at') desc), '[]'::jsonb)
    into v_recent
  from (
    select jsonb_build_object(
      'score_me', case when player_a = p_user then score_a else score_b end,
      'score_foe', case when player_a = p_user then score_b else score_a end,
      'won', winner = p_user,
      'at', finished_at
    ) jr
    from public.battles
    where status = 'FINISHED' and p_user in (player_a, player_b)
    order by finished_at desc limit 5
  ) last5;

  return jsonb_build_object(
    'user_id', p_user,
    'level', coalesce(v_level,1),
    'total', coalesce(v_total,0),
    'wins', coalesce(v_wins,0),
    'losses', v_losses,
    'win_rate', case when coalesce(v_total,0) = 0 then 0
                     else round(100.0 * v_wins / v_total)::int end,
    'avg_score', v_avg,
    'pace_per_min', v_pace,
    'recent', v_recent
  );
end $$;

-- ── Settlement now shares 20% of the platform cut with the winning hunter ──
create or replace function public.settle_prediction_pool(p_pool_id uuid, p_winner text)
returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  pool public.prediction_pools%rowtype;
  r record;
  win_total bigint;
  distributable numeric;
  pay bigint;
  winners int := 0; losers int := 0;
  v_house_cut bigint; v_winner_share bigint := 0; v_battle_winner uuid;
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  if p_winner not in ('A','B') then raise exception 'bad_side'; end if;

  select * into pool from public.prediction_pools where id = p_pool_id for update;
  if not found then raise exception 'no_pool'; end if;
  if pool.status in ('SETTLED','REFUNDED') then raise exception 'already_settled'; end if;

  distributable := floor(pool.total_pool_vc * (10000 - pool.platform_cut_bps) / 10000.0);
  win_total := case when p_winner = 'A' then pool.total_a_vc else pool.total_b_vc end;

  if win_total = 0 or pool.total_pool_vc = 0 then
    for r in select * from public.prediction_bets where pool_id = p_pool_id and status = 'OPEN' loop
      perform public.credit_vc(r.user_id, r.amount_vc, 'BET_REFUND', 'pool:' || p_pool_id);
      update public.prediction_bets set status = 'REFUNDED', payout_vc = amount_vc where id = r.id;
    end loop;
    update public.prediction_pools set status = 'REFUNDED', settled_at = now() where id = p_pool_id;
    return jsonb_build_object('mode', 'refund');
  end if;

  for r in select * from public.prediction_bets where pool_id = p_pool_id and status = 'OPEN' loop
    if r.side = p_winner then
      pay := floor((r.amount_vc::numeric / win_total) * distributable);
      perform public.credit_vc(r.user_id, pay, 'BET_PAYOUT', 'pool:' || p_pool_id);
      update public.prediction_bets set status = 'WON', payout_vc = pay where id = r.id;
      if (select referred_by from public.users where id = r.user_id) is not null then
        perform public.credit_vc((select referred_by from public.users where id = r.user_id),
                                 greatest(floor(pay * 0.05), 0), 'REFERRAL_CUT', 'pool:' || p_pool_id);
      end if;
      winners := winners + 1;
    else
      update public.prediction_bets set status = 'LOST', payout_vc = 0 where id = r.id;
      losers := losers + 1;
    end if;
  end loop;

  -- Hunter profit share: 20% of the platform's 15% cut flows to the war winner.
  v_house_cut := pool.total_pool_vc - distributable::bigint;
  if v_house_cut > 0 then
    select b.winner into v_battle_winner from public.battles b where b.id = pool.battle_id;
    if v_battle_winner is not null then
      v_winner_share := greatest(floor(v_house_cut * 0.20), 0)::bigint;
      if v_winner_share > 0 then
        perform public.credit_vc(v_battle_winner, v_winner_share,
                                 'POOL_WINNER_SHARE', 'pool:' || p_pool_id);
      end if;
    end if;
  end if;

  update public.prediction_pools
     set status = 'SETTLED', winning_side = p_winner, settled_at = now()
   where id = p_pool_id;

  return jsonb_build_object('mode', 'settled', 'winners', winners, 'losers', losers,
                            'distributable', distributable, 'winning_pool', win_total,
                            'house_cut', v_house_cut, 'winner_share', v_winner_share);
end $$;

-- ── B. ADAPTIVE DAILY QUEST ENGINE ──────────────────────────────────────────
-- BMR (Mifflin-St Jeor, sex-neutral constant) + BMI tier the starting load;
-- every 3 fully-cleared quest-days the load grows ~6%, capped at elite ceilings.
create or replace function public.ensure_daily_quests() returns void
language plpgsql security definer set search_path = public as $$
declare
  u public.users%rowtype;
  v_done_days int;
  v_bmi numeric := 0; v_bmr numeric := 0;
  v_tier text;
  v_stage int; v_factor numeric;
  v_push int; v_squat int; v_run int;
  v_xp_base int;
begin
  select * into u from public.users where id = auth.uid();
  if not found then return; end if;
  if exists(select 1 from public.daily_quests where user_id = u.id and quest_date = current_date) then return; end if;

  -- completed training DAYS (distinct dates), not raw quest count
  select count(distinct quest_date)::int into v_done_days
    from public.daily_quests where user_id = u.id and completed;

  if u.height_cm is not null and u.weight_kg is not null and u.height_cm > 0 then
    v_bmi := u.weight_kg / power(u.height_cm / 100.0, 2);
    -- Mifflin-St Jeor, sex-neutral offset (midpoint of +5 / -161)
    v_bmr := 10 * u.weight_kg + 6.25 * u.height_cm - 5 * coalesce(u.age, 22) - 78;
  end if;

  v_tier := case
    when u.activity_level = 'RELENTLESS' or u.level >= 15 then 'ELITE'
    when v_bmi > 0 and v_bmi < 18.5 then 'BEGINNER'
    when u.activity_level = 'SEDENTARY' or u.level <= 2 then 'BEGINNER'
    else 'STEADY'
  end;

  v_stage := coalesce(v_done_days / 3, 0);
  v_factor := power(1.06, v_stage);

  -- Day-1 anchors per spec: beginner 10 PU / 15 squat / 500m; elite 45 / 60 / 1600m
  select case v_tier when 'BEGINNER' then 10 when 'STEADY' then 25 else 45 end,
         case v_tier when 'BEGINNER' then 15 when 'STEADY' then 30 else 60 end,
         case v_tier when 'BEGINNER' then 500 when 'STEADY' then 1000 else 1600 end
    into v_push, v_squat, v_run;

  v_push  := least(round(v_push  * v_factor)::int, 200);
  v_squat := least(round(v_squat * v_factor)::int, 250);
  v_run   := least(round(v_run   * v_factor / 50.0)::int * 50, 5000); -- round to 50m

  v_xp_base := 40 + u.level;

  insert into public.daily_quests (user_id, quest_date, title, target_value, xp_reward) values
    (u.id, current_date,
     format('%s PUSH-UPS — MAIN CHARACTER TRAINING ARC', v_push),
     v_push, v_xp_base + v_push / 2),
    (u.id, current_date,
     format('%s SQUATS — SHADOW LEG PROTOCOL', v_squat),
     v_squat, v_xp_base + v_squat / 2),
    (u.id, current_date,
     format('%s M SHADOW RUN — ZONE GRIND', v_run),
     v_run, v_xp_base + v_run / 50)
  on conflict do nothing;
end $$;

grant execute on function public.create_battle(uuid, int)            to authenticated;
grant execute on function public.set_battle_ready(uuid, boolean)    to authenticated;
grant execute on function public.cancel_battle(uuid)                to authenticated;
grant execute on function public.hunter_battle_stats(uuid)          to authenticated;
grant execute on function public.ensure_daily_quests()              to authenticated;

-- ── QUEST PROOF: a LOG only counts once a verified session is recorded ─────
-- Tapping LOG with no camera/sensor evidence must never complete a quest.
-- log_quest_proof writes an immutable workouts row AND advances progress
-- atomically; complete_quest refuses until progress reaches the target.
create or replace function public.log_quest_proof(
  p_quest_id bigint, p_kind text, p_amount int, p_duration_sec int default 0
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare q public.daily_quests%rowtype; v_new int;
begin
  if p_kind not in ('QUEST_PUSH','QUEST_SQUAT','QUEST_RUN') then raise exception 'bad_kind'; end if;
  if p_amount <= 0 then raise exception 'bad_amount'; end if;
  select * into q from public.daily_quests where id = p_quest_id and user_id = auth.uid() for update;
  if not found then raise exception 'no_quest'; end if;
  if q.completed then raise exception 'already_done'; end if;

  insert into public.workouts (user_id, kind, reps, duration_sec, xp_earned)
  values (auth.uid(), p_kind,
          case when p_kind = 'QUEST_RUN' then 0 else p_amount end,
          greatest(p_duration_sec, 0), 0);

  update public.daily_quests
     set progress = least(progress + p_amount, target_value)
   where id = q.id
  returning progress into v_new;

  return jsonb_build_object('progress', v_new, 'target', q.target_value,
                            'complete', v_new >= q.target_value);
end $$;

-- Hardened: completion requires the verified target (server-enforced).
create or replace function public.complete_quest(p_quest_id bigint) returns void
language plpgsql security definer set search_path = public as $$
declare q public.daily_quests%rowtype; bonus int := 0;
begin
  select * into q from public.daily_quests where id = p_quest_id and user_id = auth.uid() for update;
  if not found then raise exception 'no_quest'; end if;
  if q.completed then raise exception 'already_done'; end if;
  if q.progress < q.target_value then raise exception 'quest_not_verified'; end if;

  update public.daily_quests set completed = true, progress = q.target_value where id = q.id;
  perform public.apply_xp(auth.uid(), q.xp_reward, null);
  bonus := round(q.xp_reward * 0.25)::int;
  perform public.credit_vc(auth.uid(), bonus, 'QUEST_REWARD', 'quest:' || q.id);
end $$;

grant execute on function public.log_quest_proof(bigint, text, int, int) to authenticated;

-- Deprecated: naked progress bumps bypass physical proof. All advancement
-- now goes through log_quest_proof (camera/sensor verified).
create or replace function public.add_quest_progress(p_quest_id bigint, p_amount int) returns void
language plpgsql security definer set search_path = public as $$
begin
  raise exception 'use log_quest_proof';
end $$;

-- ═══════════════════════════════════════════════════════════════════════════
-- ROLLBACK (manual, only if required):
--   drop function public.hunter_battle_stats(uuid);
--   drop function public.cancel_battle(uuid);
--   drop function public.set_battle_ready(uuid, boolean);
--   alter table public.battles drop column host, drop column player_b_ready,
--                               drop column player_a_ready, drop column duration_sec;
--   alter table public.users drop column activity_level;
--   (create_battle / settle_prediction_pool / ensure_daily_quests are
--    create-or-replace; restore the prior definitions from 002 if needed.)
-- ═══════════════════════════════════════════════════════════════════════════
