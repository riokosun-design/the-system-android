-- ═══════════════════════════════════════════════════════════════════════════
-- MIGRATION 026 · SQUAD CORE (STEPS 11–14)
--
-- Squad progression law (spec — contribution weights, not probability):
--     SCORE = 41% SQUAD WORK + 9% MEMBER LEVEL + 50% SQUAD WARS
-- Every component is MEASURED:
--   work  = this ISO week's rotating task completion (aggregated from the
--           same workouts/daily_quests rows the economy already trusts)
--   level = avg member level ÷ 50 (E-rank hunter average = full bar)
--   wars  = war_points ÷ 100 — ZERO until the first war season ships, and the
--           UI says exactly that (honest incubation, never a fake war).
-- Weekly task rotation is deterministic worldwide (ISO week % pool). Nothing
-- here grants XP/VC — squad score is honor, not economy.
-- ═══════════════════════════════════════════════════════════════════════════

create table if not exists public.squad_weekly_tasks (
  seq int primary key,
  title text not null,
  target int not null,
  unit text not null check (unit in ('REPS','METERS','QUESTS','MINUTES')),
  active boolean not null default true
);

insert into public.squad_weekly_tasks (seq, title, target, unit) values
  (0, '500 TOTAL REPS AS A PACK',      500,   'REPS'),
  (1, '25 KM AS A PACK',               25000, 'METERS'),
  (2, '120 QUESTS CLEARED AS A PACK',  120,   'QUESTS'),
  (3, '300 ACTIVE MINUTES AS A PACK',  300,   'MINUTES'),
  (4, '750 TOTAL REPS AS A PACK',      750,   'REPS'),
  (5, '40 KM AS A PACK',               40000, 'METERS'),
  (6, '200 QUESTS CLEARED AS A PACK',  200,   'QUESTS'),
  (7, '500 ACTIVE MINUTES AS A PACK',  500,   'MINUTES')
on conflict (seq) do nothing;

alter table public.clans add column if not exists war_points int not null default 0;

-- task for the CURRENT ISO week (server clock is the truth)
create or replace function public.squad_current_task()
returns public.squad_weekly_tasks
language sql stable security definer set search_path to 'public' as $$
  select t.* from public.squad_weekly_tasks t
  where t.active and t.seq = mod(floor(extract(epoch from now()) / (7*86400))::bigint,
                                 (select count(*) from public.squad_weekly_tasks where active))
  limit 1
$$;

-- THE WAR FORMULA — one place, both my-squad status and the leaderboard use it
create or replace function public.squad_score(p_clan uuid)
returns jsonb
language plpgsql stable security definer set search_path to 'public' as $function$
declare
  v_task public.squad_weekly_tasks;
  v_progress numeric := 0;
  v_work numeric; v_level numeric; v_wars numeric;
  v_avg numeric; v_wp int; v_members int;
begin
  v_task := public.squad_current_task();
  select count(*), coalesce(avg(u2.level),0) into v_members, v_avg
    from public.clan_members m join public.users u2 on u2.id = m.user_id where m.clan_id = p_clan;
  select war_points into v_wp from public.clans where id = p_clan;

  if v_task.unit = 'REPS' then
    select coalesce(sum(w.reps),0) into v_progress from public.workouts w
      join public.clan_members m on m.user_id = w.user_id
     where m.clan_id = p_clan and w.kind in ('QUEST_PUSH','QUEST_SQUAT')
       and w.created_at >= date_trunc('week', now());
  elsif v_task.unit = 'METERS' then
    select coalesce(sum(w.reps),0) into v_progress from public.workouts w
      join public.clan_members m on m.user_id = w.user_id
     where m.clan_id = p_clan and w.kind = 'QUEST_RUN'
       and w.created_at >= date_trunc('week', now());
  elsif v_task.unit = 'MINUTES' then
    select coalesce(sum(w.duration_sec),0)/60.0 into v_progress from public.workouts w
      join public.clan_members m on m.user_id = w.user_id
     where m.clan_id = p_clan and w.created_at >= date_trunc('week', now());
  else -- QUESTS
    select count(*) into v_progress from public.daily_quests dq
      join public.clan_members m on m.user_id = dq.user_id
     where m.clan_id = p_clan and dq.completed
       and dq.quest_date >= date_trunc('week', current_date)::date;
  end if;

  v_work  := least(1.0, v_progress / greatest(1, v_task.target));
  v_level := least(1.0, v_avg / 50.0);
  v_wars  := least(1.0, coalesce(v_wp,0) / 100.0);

  return jsonb_build_object(
    'score', round(41*v_work + 9*v_level + 50*v_wars),
    'work_component', round(v_work::numeric, 3),
    'level_component', round(v_level::numeric, 3),
    'wars_component', round(v_wars::numeric, 3),
    'task_title', v_task.title, 'task_target', v_task.target,
    'task_unit', v_task.unit, 'task_progress', round(v_progress),
    'members', v_members, 'war_points', coalesce(v_wp,0));
end $function$;

create or replace function public.squad_status()
returns jsonb
language plpgsql stable security definer set search_path to 'public' as $function$
declare
  c public.clans%rowtype;
  v_score jsonb;
begin
  select cl.* into c from public.clans cl
    join public.clan_members m on m.clan_id = cl.id and m.user_id = auth.uid()
  limit 1;
  if not found then return jsonb_build_object('in_squad', false); end if;
  v_score := public.squad_score(c.id);
  return jsonb_build_object(
    'in_squad', true, 'clan_id', c.id, 'name', c.name, 'tag', c.tag,
    'stored_level', c.level, 'treasury_vc', c.treasury_vc,
    'guild_master', c.guild_master) || v_score;
end $function$;

create or replace function public.squad_leaderboard()
returns jsonb
language plpgsql stable security definer set search_path to 'public' as $$
begin
  return coalesce((
    select jsonb_agg(row_to_json(x) order by (x.score_json->>'score')::int desc)
    from (
      select c.id, c.name, c.tag,
             (select count(*) from public.clan_members m where m.clan_id = c.id) as members,
             public.squad_score(c.id) as score_json
      from public.clans c
      order by (public.squad_score(c.id)->>'score')::int desc
      limit 50
    ) x
  ), '[]'::jsonb);
end $$;
