-- 034 · HANDLE CHARSET RELAX (0.23.0-LIVE directive §2.4)
-- Field law: handles may carry capitals, digits, hyphen, underscore and
-- slash — 3..24 chars, displayed EXACTLY as typed. Uniqueness stays
-- normalized lowercase, so 'Rio', 'RIO' and 'rio' remain ONE identity.
-- The old '^[a-z0-9_]{3,20}$' wall is torn down in both gate functions.

create or replace function public.claim_username(p_handle text) returns void
language plpgsql security definer set search_path = public as $$
declare
  typed text := trim(p_handle);
  h text := lower(trim(p_handle));
begin
  if typed !~ '^[A-Za-z0-9_/-]{3,24}$' then raise exception 'bad_handle'; end if;
  if h = any(public.reserved_handles()) then raise exception 'reserved_handle'; end if;
  update public.users
     set username = h,
         display_name = typed
   where id = auth.uid();
  if not found then raise exception 'no_profile'; end if;
exception when unique_violation then
  raise exception 'handle_taken';
end $$;

create or replace function public.username_available(p_handle text) returns boolean
language plpgsql stable security definer set search_path = public as $$
declare
  typed text := trim(p_handle);
  h text := lower(trim(p_handle));
begin
  if typed !~ '^[A-Za-z0-9_/-]{3,24}$' then return false; end if;
  if h = any(public.reserved_handles()) then return false; end if;
  return not exists(
    select 1 from public.users
    where username = h and id is distinct from auth.uid()
  );
end $$;
