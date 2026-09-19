-- 013 — RANDOM MATCHMAKING QUEUE (Arena section 2)
-- A real, server-authoritative queue: no fake opponents, ever.
--   enter  → pair with the oldest compatible hunter ATOMICALLY, else WAITING
--   status → WAITING (+seconds) | MATCHED (+battle_id + opponent) | IDLE
--   leave  → exit the queue; if a fresh match is aborted, the partner is freed
-- Queue entries die after 2 minutes without a heartbeat (the client polls
-- status() while searching), so ghosts can never occupy the queue.

create table if not exists public.arena_queue (
    user_id     uuid primary key references auth.users(id) on delete cascade,
    exercise    text not null check (exercise in ('PUSHUP','SQUAT')),
    duration_sec int not null default 60 check (duration_sec in (30,60,120)),
    status      text not null default 'WAITING' check (status in ('WAITING','MATCHED')),
    battle_id   uuid references public.battles(id) on delete set null,
    opponent    uuid,
    heartbeat_at timestamptz not null default now(),
    matched_at  timestamptz
);

alter table public.arena_queue enable row level security;

-- Hunters can read only their own queue row. ALL mutations go through the
-- security-definer RPCs below (server-authoritative pairing).
drop policy if exists arena_queue_select_own on public.arena_queue;
create policy arena_queue_select_own on public.arena_queue
    for select to authenticated using (auth.uid() = user_id);

-- ── internal: purge stale hearts ────────────────────────────────────────────
create or replace function public.arena_queue_gc() returns void
language sql security definer set search_path = public as $$
    delete from public.arena_queue
     where status = 'WAITING' and heartbeat_at < now() - interval '120 seconds';
$$;

-- ── enter (or re-enter) the queue ───────────────────────────────────────────
create or replace function public.random_queue_enter(
    p_exercise text,
    p_duration_sec int default 60
) returns jsonb
language plpgsql security definer set search_path = public as $$
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
        insert into public.prediction_pools (battle_id) values (bid);

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
end $$;

-- ── heartbeat + status (client polls while searching) ───────────────────────
create or replace function public.random_queue_status() returns jsonb
language plpgsql security definer set search_path = public as $$
declare
    me uuid := auth.uid();
    q public.arena_queue%rowtype;
    b public.battles%rowtype;
    opp_name text;
begin
    if me is null then
        raise exception 'not_authenticated';
    end if;
    perform public.arena_queue_gc();

    select * into q from public.arena_queue where user_id = me;
    if not found then
        return jsonb_build_object('status','IDLE');
    end if;

    if q.status = 'MATCHED' and q.battle_id is not null then
        select * into b from public.battles where id = q.battle_id;
        if found and b.status not in ('FINISHED','CANCELLED') then
            select coalesce(u.display_name, u.username, 'HUNTER') into opp_name
              from public.users u where u.id = q.opponent;
            return jsonb_build_object(
                'status','MATCHED','battle_id',q.battle_id,
                'opponent_name',coalesce(opp_name,'HUNTER'),
                'exercise',q.exercise,'duration_sec',q.duration_sec);
        end if;
        -- battle is over/gone → close the queue card
        delete from public.arena_queue where user_id = me;
        return jsonb_build_object('status','IDLE');
    end if;

    -- still hunting: prove we are alive
    update public.arena_queue set heartbeat_at = now() where user_id = me;
    return jsonb_build_object('status','WAITING',
        'queued_sec', extract(epoch from (now() - q.heartbeat_at))::int,
        'exercise', q.exercise, 'duration_sec', q.duration_sec);
end $$;

-- ── leave the queue ─────────────────────────────────────────────────────────
create or replace function public.random_queue_leave() returns jsonb
language plpgsql security definer set search_path = public as $$
declare
    me uuid := auth.uid();
    q public.arena_queue%rowtype;
    b public.battles%rowtype;
begin
    if me is null then
        raise exception 'not_authenticated';
    end if;

    select * into q from public.arena_queue where user_id = me for update;
    if not found then
        return jsonb_build_object('status','IDLE');
    end if;

    -- matched but the war room was never entered: free the partner too, and
    -- cancel the unused battle so no ghost lobby lingers
    if q.status = 'MATCHED' and q.battle_id is not null then
        select * into b from public.battles where id = q.battle_id;
        delete from public.arena_queue where battle_id = q.battle_id;
        if found and b.started_at is null and b.status in ('PENDING','LOBBY') then
            update public.battles
               set status = 'CANCELLED', finished_at = now()
             where id = q.battle_id;
        end if;
        return jsonb_build_object('status','IDLE');
    end if;

    delete from public.arena_queue where user_id = me;
    return jsonb_build_object('status','IDLE');
end $$;

grant execute on function public.random_queue_enter(text, int) to authenticated;
grant execute on function public.random_queue_status() to authenticated;
grant execute on function public.random_queue_leave() to authenticated;
grant execute on function public.arena_queue_gc() to authenticated;
