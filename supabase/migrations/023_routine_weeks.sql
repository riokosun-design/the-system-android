-- ═══════════════════════════════════════════════════════════════════════════
-- MIGRATION 023 · ROUTINE WEEKS (STEP 7 — the adaptive routine arc)
--
-- The week state is SERVER-OWNED progression: the client reads it, the server
-- advances it from MEASURED adherence only (completed quests vs issued — the
-- same records the economy already trusts). Verdict thresholds mirror
-- core/routine/RoutineEngine.kt exactly:
--     ≥5 active days (last 7)  → ADVANCE   (cap 4)
--     ≤2 active days           → CONSOLIDATE (floor 1, never punishment)
--     else                     → HOLD
-- Idempotent inside the current ISO week (re-calls return CACHED).
-- Existing users start at week 1 with a NULL last-evaluation — their first
-- evaluation runs the week after the client asks for it.
-- ═══════════════════════════════════════════════════════════════════════════

alter table public.users add column if not exists routine_week int not null default 1;
alter table public.users add column if not exists routine_week_evaluated_on date;
do $$
begin
  if not exists (select 1 from pg_constraint where conname = 'users_routine_week_check') then
    alter table public.users add constraint users_routine_week_check
      check (routine_week between 1 and 4);
  end if;
end $$;

create or replace function public.evaluate_routine_week()
returns json
language plpgsql
security definer
set search_path to 'public'
as $function$
declare
  u public.users%rowtype;
  v_active int;
  v_verdict text;
  v_new int;
  v_week_start date := date_trunc('week', current_date)::date;  -- ISO Monday
begin
  select * into u from public.users where id = auth.uid();
  if not found then return null; end if;

  -- once per ISO week: later calls just report the settled state
  if u.routine_week_evaluated_on is not null and u.routine_week_evaluated_on >= v_week_start then
    return json_build_object(
      'week', u.routine_week, 'prev_week', u.routine_week,
      'verdict', 'CACHED', 'active_days', null);
  end if;

  -- ACTIVE DAY = completed ≥ 60% of that day's issued quests (last 7 full days;
  -- grace for brand-new hunters: days with ZERO issued quests simply don't count)
  select count(*) into v_active from (
    select quest_date,
           count(*) as total,
           count(*) filter (where completed) as done
    from public.daily_quests
    where user_id = u.id
      and quest_date between current_date - 7 and current_date - 1
    group by quest_date
  ) d
  where d.done >= greatest(1, ceil(d.total * 0.6));

  v_verdict := case
    when v_active >= 5 then 'ADVANCE'
    when v_active <= 2 then 'CONSOLIDATE'
    else 'HOLD' end;
  v_new := case v_verdict
    when 'ADVANCE' then least(4, u.routine_week + 1)
    when 'CONSOLIDATE' then greatest(1, u.routine_week - 1)
    else u.routine_week end;

  update public.users
  set routine_week = v_new, routine_week_evaluated_on = current_date
  where id = u.id;

  return json_build_object(
    'week', v_new, 'prev_week', u.routine_week,
    'verdict', v_verdict, 'active_days', v_active);
end $function$;
