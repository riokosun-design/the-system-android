-- ═══════════════════════════════════════════════════════════════════════════
-- 006 — TOURNAMENT BRACKET ENGINE (single-elimination, seeded by fate)
-- generate_bracket: LOCKED tournament → shuffled round-1 battles (BYE for odd counts)
-- advance_bracket:  round complete → winners advance; last hunter standing crowned
--                   + prize pool paid via credit_vc + placements written
-- Both: admin-gated, row-locked, idempotent (no double-generation races).
-- ═══════════════════════════════════════════════════════════════════════════

alter table public.battles add column if not exists round integer not null default 1;

create or replace function public.generate_bracket(p_tournament_id uuid)
returns integer
language plpgsql security definer set search_path = public as $$
declare
    t           record;
    v_users     uuid[];
    v_clans     uuid[];
    n           int;
    i           int := 1;
    v_created   int := 0;
begin
    if not (public.is_super_admin() or public.is_admin()) then
        raise exception 'admin_only';
    end if;

    select * into t from public.tournaments where id = p_tournament_id for update; -- race lock
    if not found then raise exception 'tournament_not_found'; end if;
    if t.status::text <> 'LOCKED' then
        raise exception 'tournament_must_be_locked'; -- registration sealed first
    end if;
    if exists (select 1 from public.battles where tournament_id = p_tournament_id) then
        raise exception 'bracket_already_generated';
    end if;

    -- ONE shuffle (aligned arrays): SOLO → user_id reps; CLAN → representative + their clan
    select array_agg(s.user_id), array_agg(s.clan_id)
      into v_users, v_clans
      from (
        select user_id, clan_id
        from public.tournament_participants
        where tournament_id = p_tournament_id
        order by random()                                   -- seeding by fate
    ) s;

    n := coalesce(array_length(v_users, 1), 0);
    if n < 2 then raise exception 'not_enough_participants'; end if;

    while i + 1 <= n loop
        insert into public.battles (tournament_id, player_a, player_b, clan_a, clan_b, status, round)
        values (p_tournament_id, v_users[i], v_users[i + 1], v_clans[i], v_clans[i + 1], 'LOBBY', 1);
        v_created := v_created + 1;
        i := i + 2;
    end loop;
    -- odd hunter out advances on a BYE (no battle; still in the bracket flow next round)

    update public.tournaments set status = 'IN_PROGRESS' where id = p_tournament_id;
    insert into public.admin_audit_log (actor, action, payload)
    values (auth.uid(), 'GENERATE_BRACKET', jsonb_build_object('tournament', p_tournament_id, 'round_1_battles', v_created, 'hunters', n));
    return v_created;
end $$;

create or replace function public.advance_bracket(p_tournament_id uuid)
returns integer
language plpgsql security definer set search_path = public as $$
declare
    t         record;
    v_round   int;
    v_pending int;
    v_users   uuid[];
    v_clans   uuid[];
    n         int;
    i         int := 1;
    v_created int := 0;
    champ     uuid;
begin
    if not (public.is_super_admin() or public.is_admin()) then
        raise exception 'admin_only';
    end if;

    select * into t from public.tournaments where id = p_tournament_id for update;
    if not found then raise exception 'tournament_not_found'; end if;
    if t.status::text <> 'IN_PROGRESS' then raise exception 'tournament_not_in_progress'; end if;

    select max(round) into v_round from public.battles where tournament_id = p_tournament_id;
    if v_round is null then raise exception 'no_bracket_generated'; end if;

    select count(*) into v_pending
    from public.battles
    where tournament_id = p_tournament_id and round = v_round and status <> 'FINISHED';
    if v_pending > 0 then raise exception 'round_not_finished'; end if;

    -- winners advance in bracket order, carrying their clan banner with them
    select array_agg(b.winner order by b.created_at),
           array_agg((case when b.winner = b.player_a then b.clan_a else b.clan_b end) order by b.created_at)
      into v_users, v_clans
      from public.battles b
      where b.tournament_id = p_tournament_id and b.round = v_round;

    n := coalesce(array_length(v_users, 1), 0);

    -- final battle decided → crown the champion, pay the pool, write placements
    if n = 1 then
        champ := v_users[1];
        update public.tournaments set status = 'COMPLETED', ends_at = now() where id = p_tournament_id;
        update public.tournament_participants set placement = 1
        where tournament_id = p_tournament_id and user_id = champ;
        if t.prize_pool_vc > 0 then
            perform public.credit_vc(champ, t.prize_pool_vc, 'TOURNAMENT_PRIZE', p_tournament_id::text);
        end if;
        insert into public.admin_audit_log (actor, action, payload)
        values (auth.uid(), 'TOURNAMENT_CROWNED', jsonb_build_object('tournament', p_tournament_id, 'champion', champ, 'prize', t.prize_pool_vc));
        return 0;
    end if;

    while i + 1 <= n loop
        insert into public.battles (tournament_id, player_a, player_b, clan_a, clan_b, status, round)
        values (p_tournament_id, v_users[i], v_users[i + 1], v_clans[i], v_clans[i + 1], 'LOBBY', v_round + 1);
        v_created := v_created + 1;
        i := i + 2;
    end loop;

    insert into public.admin_audit_log (actor, action, payload)
    values (auth.uid(), 'ADVANCE_BRACKET', jsonb_build_object('tournament', p_tournament_id, 'round', v_round + 1, 'battles', v_created));
    return v_created;
end $$;

-- RPC exposure follows the existing grant pattern (functions are admin-gated internally)
grant execute on function public.generate_bracket(uuid) to authenticated;
grant execute on function public.advance_bracket(uuid) to authenticated;
