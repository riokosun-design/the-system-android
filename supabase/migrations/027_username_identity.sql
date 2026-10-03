-- 027 USERNAME IDENTITY (STEP 16)
-- Display case is the hunter's own (San / SAN / SaN all legal display forms);
-- uniqueness is normalized lowercase. Reserved handles can never be claimed.
-- Availability is instant and anonymous-safe (pre-auth onboarding gate).

create or replace function public.reserved_handles() returns text[]
language sql immutable as $$
  select array[
    'admin','administrator','system','thesystem','support','help','helpdesk',
    'official','root','staff','mod','moderator','owner','founder',
    'solo','leveling','sololeveling','arise','hunter_association','association',
    'null','undefined','server','api','bot'
  ]
$$;

create or replace function public.claim_username(p_handle text) returns void
language plpgsql security definer set search_path = public as $$
declare
  h text := lower(trim(p_handle));
  typed text := trim(p_handle);
begin
  if h !~ '^[a-z0-9_]{3,20}$' then raise exception 'bad_handle'; end if;
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
declare h text := lower(trim(p_handle));
begin
  if h !~ '^[a-z0-9_]{3,20}$' then return false; end if;
  if h = any(public.reserved_handles()) then return false; end if;
  return not exists(
    select 1 from public.users
    where username = h and id is distinct from auth.uid()
  );
end $$;
