-- ═══════════════════════════════════════════════════════════════════════════
-- THE SYSTEM — 007: PLAY COMPLIANCE
-- Google Play "Account Deletion" policy: any app that lets users create an
-- account must also let them DELETE it from inside the app.
-- Every user-owned table cascades from auth.users (verified 001_schema.sql),
-- so a single delete purges: profile, quests, arcs, forms, workouts, clans,
-- battles, bets, messages, payments, VC ledger, referrals.
-- ═══════════════════════════════════════════════════════════════════════════

create or replace function public.delete_account()
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  if auth.uid() is null then
    raise exception 'not_authenticated';
  end if;

  -- Cascades wipe public.users + every dependent row across the schema.
  delete from auth.users where id = auth.uid();
end;
$$;

-- Only signed-in users may execute; never expose to anon/public.
revoke all on function public.delete_account() from public;
revoke all on function public.delete_account() from anon;
grant execute on function public.delete_account() to authenticated;
