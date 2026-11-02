-- ═══════════════════════════════════════════════════════════════════════════
-- 035 — PREDICTION & BETTING MODULE: COMPLETE REMOVAL (directive §1)
-- Wager/points history is ARCHIVED, never deleted (immutability law). All 11
-- live functions that touched the pool get clean rebuilds minus pool logic;
-- the five prediction-only RPCs are dropped outright.
-- ═══════════════════════════════════════════════════════════════════════════

-- 0. Snapshot remaining points balances, then retire the column.
drop table if exists public.archived_prediction_points_snapshot;
create table public.archived_prediction_points_snapshot as
  select id, prediction_points, now() as archived_at
    from public.users where prediction_points <> 0;
revoke all on public.archived_prediction_points_snapshot from anon, authenticated;

alter table public.users drop column if exists prediction_points;

-- 1. Wager history → cold archive (RLS policies ride along with the rename).
alter table public.prediction_pools     rename to archived_prediction_pools;
alter table public.prediction_bets      rename to archived_prediction_bets;
alter table public.prediction_allowance rename to archived_prediction_allowance;
revoke all on public.archived_prediction_pools     from anon, authenticated;
revoke all on public.archived_prediction_bets      from anon, authenticated;
revoke all on public.archived_prediction_allowance from anon, authenticated;
alter publication supabase_realtime drop table public.archived_prediction_pools;

-- 2. Battle/industry functions rebuilt WITHOUT the pool seam.

-- rebuilt: create_battle (oid 18279)
CREATE OR REPLACE FUNCTION public.create_battle(p_opponent uuid)
 RETURNS uuid
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare bid uuid; me uuid := auth.uid();
begin
  if p_opponent = me then raise exception 'no_self_battle'; end if;
  insert into public.battles (player_a, player_b) values (me, p_opponent) returning id into bid;
  return bid;
end $function$
;

-- rebuilt: create_battle (oid 18618)
CREATE OR REPLACE FUNCTION public.create_battle(p_opponent uuid, p_duration_sec integer DEFAULT 60)
 RETURNS uuid
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare bid uuid; me uuid := auth.uid();
begin
  if p_opponent = me then raise exception 'no_self_battle'; end if;
  if p_duration_sec not in (30, 60, 120) then raise exception 'bad_duration'; end if;
  insert into public.battles (player_a, player_b, duration_sec, host)
  values (me, p_opponent, p_duration_sec, me)
  returning id into bid;
  return bid;
end $function$
;

-- rebuilt: create_battle (oid 18894)
CREATE OR REPLACE FUNCTION public.create_battle(p_opponent uuid, p_duration_sec integer DEFAULT 60, p_exercise text DEFAULT 'PUSHUP'::text)
 RETURNS uuid
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare bid uuid; me uuid := auth.uid();
begin
  if p_opponent = me then raise exception 'no_self_battle'; end if;
  if p_duration_sec not in (30, 60, 120) then raise exception 'bad_duration'; end if;
  if p_exercise not in ('PUSHUP','SQUAT') then raise exception 'bad_exercise'; end if;
  insert into public.battles (player_a, player_b, duration_sec, exercise_type, host)
  values (me, p_opponent, p_duration_sec, p_exercise, me)
  returning id into bid;
  return bid;
end $function$
;

-- rebuilt: random_queue_enter (oid 19150)
CREATE OR REPLACE FUNCTION public.random_queue_enter(p_exercise text, p_duration_sec integer DEFAULT 60)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
    me uuid := auth.uid();
    mine public.arena_queue%rowtype;
    cand public.arena_queue%rowtype;
    bid uuid;
    opp_name text;
begin
    if me is null then
        raise exception 'not_authenticated';
    end if;
    if p_exercise not in ('PUSHUP','SQUAT') then
        raise exception 'bad_exercise';
    end if;
    if p_duration_sec not in (30,60,120) then
        raise exception 'bad_duration';
    end if;

    perform public.arena_queue_gc();

    -- already matched? resume idempotently
    select * into mine from public.arena_queue where user_id = me for update;
    if found and mine.status = 'MATCHED' and mine.battle_id is not null then
        select coalesce(u.display_name, u.username, 'HUNTER') into opp_name
          from public.users u where u.id = mine.opponent;
        return jsonb_build_object(
            'status','MATCHED','battle_id',mine.battle_id,
            'opponent_name',coalesce(opp_name,'HUNTER'),
            'exercise',mine.exercise,'duration_sec',mine.duration_sec);
    end if;

    -- find the oldest compatible hunter; skip locked so two callers can
    -- never pair with the same person
    select * into cand
      from public.arena_queue
     where status = 'WAITING'
       and exercise = p_exercise
       and user_id <> me
       and heartbeat_at > now() - interval '120 seconds'
     order by heartbeat_at asc
     limit 1
     for update skip locked;

    if found then
        -- pair! candidate hosts as player A, caller joins as player B
        insert into public.battles (player_a, player_b, duration_sec, exercise_type, host)
        values (cand.user_id, me, least(cand.duration_sec, p_duration_sec), p_exercise, cand.user_id)
        returning id into bid;

        update public.arena_queue
           set status = 'MATCHED', battle_id = bid, opponent = me,
               matched_at = now(), heartbeat_at = now()
         where user_id = cand.user_id;

        insert into public.arena_queue (user_id, exercise, duration_sec, status, battle_id, opponent, heartbeat_at, matched_at)
        values (me, p_exercise, p_duration_sec, 'MATCHED', bid, cand.user_id, now(), now())
        on conflict (user_id) do update
           set status = 'MATCHED', battle_id = bid, opponent = cand.user_id,
               matched_at = now(), heartbeat_at = now();

        select coalesce(u.display_name, u.username, 'HUNTER') into opp_name
          from public.users u where u.id = cand.user_id;
        return jsonb_build_object(
            'status','MATCHED','battle_id',bid,
            'opponent_name',coalesce(opp_name,'HUNTER'),
            'exercise',p_exercise,'duration_sec',least(cand.duration_sec, p_duration_sec));
    end if;

    -- nobody waiting → take a number
    insert into public.arena_queue (user_id, exercise, duration_sec, status, heartbeat_at)
    values (me, p_exercise, p_duration_sec, 'WAITING', now())
    on conflict (user_id) do update
       set exercise = excluded.exercise, duration_sec = excluded.duration_sec,
           status = 'WAITING', battle_id = null, opponent = null,
           matched_at = null, heartbeat_at = now();

    return jsonb_build_object('status','WAITING','queued_sec',0,
        'exercise',p_exercise,'duration_sec',p_duration_sec);
end $function$
;

-- rebuilt: respond_match_challenge (oid 19673)
CREATE OR REPLACE FUNCTION public.respond_match_challenge(p_id uuid, p_accept boolean)
 RETURNS uuid
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare me uuid := auth.uid(); c public.match_challenges%rowtype; bid uuid;
begin
  select * into c from public.match_challenges where id = p_id for update;
  if not found then raise exception 'no_challenge'; end if;
  if c.opponent_id <> me then raise exception 'forbidden'; end if;
  if c.status <> 'pending' then raise exception 'already_answered'; end if;
  if c.created_at < now() - interval '30 minutes' then
    update public.match_challenges set status='expired', responded_at=now() where id = c.id;
    raise exception 'challenge_expired';
  end if;
  if not p_accept then
    update public.match_challenges set status='declined', responded_at=now() where id = c.id;
    return null;
  end if;
  -- ACCEPTED → the war spawns NOW (same shape as create_battle)
  insert into public.battles (player_a, player_b, duration_sec, exercise_type, host)
  values (c.challenger_id, me, c.duration_sec, c.exercise, c.challenger_id)
  returning id into bid;
  update public.match_challenges
     set status='accepted', battle_id=bid, responded_at=now() where id = c.id;
  return bid;
end $function$
;

-- rebuilt: schedule_duel (oid 18895)
CREATE OR REPLACE FUNCTION public.schedule_duel(p_opponent uuid, p_exercise text, p_duration integer, p_at timestamp with time zone)
 RETURNS uuid
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare bid uuid; mid uuid;
begin
  if p_opponent = auth.uid() then raise exception 'no_self_battle'; end if;
  if p_exercise not in ('PUSHUP','SQUAT') then raise exception 'bad_exercise'; end if;
  if p_duration not in (30,60,120) then raise exception 'bad_duration'; end if;
  if p_at < now() - interval '5 minutes' then raise exception 'bad_time'; end if;

  insert into public.battles (player_a, player_b, duration_sec, exercise_type, status, scheduled_at, host)
  values (auth.uid(), p_opponent, p_duration, p_exercise, 'SCHEDULED', p_at, auth.uid())
  returning id into bid;
  insert into public.scheduled_matches
    (battle_id, player1_id, player2_id, exercise_type, scheduled_time, duration_seconds)
  values (bid, auth.uid(), p_opponent, p_exercise, p_at, p_duration)
  returning id into mid;
  return mid;
end $function$
;

-- rebuilt: set_battle_ready (oid 18619)
CREATE OR REPLACE FUNCTION public.set_battle_ready(p_battle_id uuid, p_ready boolean DEFAULT true)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare b public.battles%rowtype; me uuid := auth.uid();
begin
  select * into b from public.battles where id = p_battle_id for update;
  if not found then raise exception 'no_battle'; end if;
  if me not in (b.player_a, b.player_b) then raise exception 'forbidden'; end if;
  if b.status not in ('LOBBY','SCHEDULED') then raise exception 'not_in_lobby'; end if;
  if b.status = 'SCHEDULED' and b.scheduled_at is not null
     and now() < b.scheduled_at - interval '5 minutes' then
    raise exception 'too_early';
  end if;

  if me = b.player_a then
    update public.battles set player_a_ready = p_ready where id = b.id;
  else
    update public.battles set player_b_ready = p_ready where id = b.id;
  end if;

  if p_ready and
     (select player_a_ready and player_b_ready from public.battles where id = b.id) then
    update public.battles set status = 'LIVE', started_at = now()
     where id = b.id and status in ('LOBBY','SCHEDULED');
update public.scheduled_matches set status = 'DONE'
     where battle_id = b.id and status = 'ACCEPTED';
  end if;
end $function$
;

-- rebuilt: cancel_battle (oid 18620)
CREATE OR REPLACE FUNCTION public.cancel_battle(p_battle_id uuid)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare b public.battles%rowtype;
begin
  select * into b from public.battles where id = p_battle_id for update;
  if not found then raise exception 'no_battle'; end if;
  if auth.uid() not in (b.player_a, b.player_b, b.host) and not public.is_admin() then
    raise exception 'forbidden';
  end if;
  if b.status not in ('LOBBY','SCHEDULED') then raise exception 'not_in_lobby'; end if;
  update public.scheduled_matches set status = 'CANCELLED'
   where battle_id = b.id and status in ('PENDING','ACCEPTED');
  update public.battles set status = 'CANCELLED' where id = b.id;
end $function$
;

-- rebuilt: admin_overview (oid 18286)
CREATE OR REPLACE FUNCTION public.admin_overview()
 RETURNS jsonb
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select case when public.is_admin() then jsonb_build_object(
    'users', (select count(*) from public.users),
    'clans', (select count(*) from public.clans),
    'pending_payments', (select count(*) from public.manual_payments where status = 'PENDING'),
    'open_tournaments', (select count(*) from public.tournaments where status = 'OPEN')
  ) else '{}'::jsonb end
$function$
;

-- rebuilt: admin_ops_metrics (oid 19916)
CREATE OR REPLACE FUNCTION public.admin_ops_metrics()
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
begin
  perform public.require_admin_perm('ANALYTICS');
  return jsonb_build_object(
    'users_total', (select count(*) from public.users),
    'users_today', (select count(*) from public.users where created_at >= date_trunc('day', now())),
    'users_7d', (select count(*) from public.users where created_at >= now() - interval '7 days'),
    'users_30d', (select count(*) from public.users where created_at >= now() - interval '30 days'),
    'active_today', (select count(*) from public.users where updated_at >= date_trunc('day', now())),
    'active_7d', (select count(*) from public.users where updated_at >= now() - interval '7 days'),
    'active_30d', (select count(*) from public.users where updated_at >= now() - interval '30 days'),
    'online_now', null,  -- no heartbeat rail exists; null = honestly uncollected
    'vc_issued', coalesce((select sum(amount) from public.vc_transactions where amount > 0), 0),
    'vc_spent', coalesce((select -sum(amount) from public.vc_transactions where amount < 0), 0),
    'vc_held', coalesce((select sum(vc_held) from public.users), 0),
    'vc_outstanding', coalesce((select sum(vc_balance) from public.users), 0),
    'orders_credited', (select count(*) from public.payment_orders where status = 'CREDITED'),
    'orders_gross_inr', coalesce((select sum(price_inr) from public.payment_orders where status in ('CREDITED','VERIFIED')), 0),
    'orders_pending', (select count(*) from public.payment_orders where status in ('SUBMITTED','VERIFYING','MANUAL_REVIEW')),
    'orders_rejected', (select count(*) from public.payment_orders where status = 'REJECTED'),
    'manual_pay_pending', (select count(*) from public.manual_payments where status = 'PENDING'),
    'withdrawals_pending', (select count(*) from public.withdrawal_requests where status in ('PENDING_REVIEW','APPROVED','PROCESSING')),
    'withdrawals_pending_inr', coalesce((select sum(payout_inr) from public.withdrawal_requests where status in ('PENDING_REVIEW','APPROVED','PROCESSING')), 0),
    'withdrawals_paid', (select count(*) from public.withdrawal_requests where status = 'PAID'),
    'withdrawals_paid_inr', coalesce((select sum(payout_inr) from public.withdrawal_requests where status = 'PAID'), 0),
    'tournaments_active', (select count(*) from public.tournaments where status in ('OPEN','IN_PROGRESS')),
    'support_open', (select count(*) from public.support_conversations where status not in ('RESOLVED')),
    'support_waiting', (select count(*) from public.support_conversations where status = 'WAITING_FOR_SUPPORT')
  );
end $function$
;

-- rebuilt: guard_user_columns (oid 18262)
CREATE OR REPLACE FUNCTION public.guard_user_columns()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
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
end $function$
;


-- 3. Prediction-only RPCs: EXECUTED.
drop function if exists public.place_bet(uuid, text, bigint);
drop function if exists public.settle_prediction_pool(uuid, text);
drop function if exists public.claim_prediction_allowance();
drop function if exists public.prediction_hub();
drop function if exists public.refund_pool(uuid);
