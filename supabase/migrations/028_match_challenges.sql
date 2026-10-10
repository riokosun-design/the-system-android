-- 028 MATCH CHALLENGES — arena invite dispatch + notification rail
-- User A challenges User B by handle → row lands pending → B's bell badge
-- lights up → one-tap ACCEPT spawns the battle row (player_a = challenger,
-- pool spawns OPEN) and both hunters drop into the war room.

create table if not exists public.match_challenges (
  id            uuid primary key default gen_random_uuid(),
  challenger_id uuid not null references public.users(id) on delete cascade,
  opponent_id   uuid not null references public.users(id) on delete cascade,
  exercise      text not null default 'PUSHUP' check (exercise in ('PUSHUP','SQUAT')),
  duration_sec  int  not null default 60 check (duration_sec in (30, 60, 120)),
  status        text not null default 'pending' check (status in ('pending','accepted','declined','expired','cancelled')),
  battle_id     uuid references public.battles(id),
  created_at    timestamptz not null default now(),
  responded_at  timestamptz
);

create unique index if not exists match_challenges_one_pending
  on public.match_challenges (challenger_id, opponent_id) where status = 'pending';

alter table public.match_challenges enable row level security;

-- both parties can READ their own challenges (the bell polls this directly)
drop policy if exists match_challenges_select on public.match_challenges;
create policy match_challenges_select on public.match_challenges for select
  using (auth.uid() in (challenger_id, opponent_id));

-- writes flow through the RPCs below only (security definer owns the law)

create or replace function public.create_match_challenge(
  p_opponent uuid, p_exercise text default 'PUSHUP', p_duration_sec int default 60
) returns uuid
language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); cid uuid;
begin
  if me is null then raise exception 'unauthorized'; end if;
  if p_opponent = me then raise exception 'no_self_challenge'; end if;
  if upper(p_exercise) not in ('PUSHUP','SQUAT') then raise exception 'bad_exercise'; end if;
  if p_duration_sec not in (30, 60, 120) then raise exception 'bad_duration'; end if;
  if not exists (select 1 from public.users where id = p_opponent) then
    raise exception 'no_such_hunter';
  end if;
  -- stale pendings die silently (30-minute shelf life)
  update public.match_challenges set status='expired', responded_at=now()
   where status='pending' and created_at < now() - interval '30 minutes'
     and (challenger_id=me or opponent_id=me);
  -- one live invite per direction: my older pending to the same hunter dies
  update public.match_challenges set status='cancelled', responded_at=now()
   where status='pending' and challenger_id=me and opponent_id=p_opponent;
  insert into public.match_challenges (challenger_id, opponent_id, exercise, duration_sec)
  values (me, p_opponent, upper(p_exercise), p_duration_sec) returning id into cid;
  return cid;
end $$;

create or replace function public.respond_match_challenge(p_id uuid, p_accept boolean)
returns uuid
language plpgsql security definer set search_path = public as $$
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
  -- ACCEPTED → the war spawns NOW (same shape as create_battle: pool opens)
  insert into public.battles (player_a, player_b, duration_sec, exercise_type, host)
  values (c.challenger_id, me, c.duration_sec, c.exercise, c.challenger_id)
  returning id into bid;
  insert into public.prediction_pools (battle_id) values (bid);
  update public.match_challenges
     set status='accepted', battle_id=bid, responded_at=now() where id = c.id;
  return bid;
end $$;

create or replace function public.cancel_match_challenge(p_id uuid)
returns void
language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid();
begin
  update public.match_challenges set status='cancelled', responded_at=now()
   where id = p_id and challenger_id = me and status = 'pending';
end $$;
