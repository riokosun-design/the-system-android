-- 030 ADMIN COMMAND + MONETIZATION SPINE
-- Permission lattice · immutable audit · server-priced VC packages · QR order
-- machine · held-balance withdrawals · support inbox · ops metrics · notices.
-- Every financial mutation runs here, server-side, behind has_admin_perm.

-- ── A. PERMISSIONS + AUDIT ───────────────────────────────────────────────────
create table if not exists public.admin_perms (
  user_id    uuid not null references public.users(id) on delete cascade,
  perm       text not null check (perm in ('FINANCE','SUPPORT','CONTENT','TOURNAMENT','ANALYTICS','PEOPLE')),
  granted_by uuid references public.users(id),
  granted_at timestamptz not null default now(),
  primary key (user_id, perm)
);

create table if not exists public.admin_audit_log (
  id         bigint generated always as identity primary key,
  actor      uuid not null references public.users(id),
  action     text not null,
  target     text,
  outcome    text not null default 'OK',
  meta       jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);
create index if not exists admin_audit_recent_idx on public.admin_audit_log (created_at desc);

alter table public.admin_perms enable row level security;
alter table public.admin_audit_log enable row level security;

create or replace function public.has_admin_perm(p_user uuid, p_perm text)
returns boolean language plpgsql security definer stable set search_path = public as $$
begin
  if p_user is null then return false; end if;
  -- SUPER_ADMIN holds every key, always
  if exists (select 1 from public.users where id = p_user and role = 'SUPER_ADMIN') then return true; end if;
  -- base staff read analytics; nothing else without an explicit grant
  if p_perm = 'ANALYTICS' and exists (select 1 from public.users where id = p_user and role in ('ADMIN','MODERATOR','SUPER_ADMIN')) then
    return true;
  end if;
  return exists (select 1 from public.admin_perms where user_id = p_user and perm = p_perm);
end $$;

create or replace function public.require_admin_perm(p_perm text)
returns void language plpgsql security definer stable set search_path = public as $$
begin
  if not public.has_admin_perm(auth.uid(), p_perm) then raise exception 'forbidden'; end if;
end $$;

create or replace function public.admin_grant_perm(p_user uuid, p_perm text)
returns void language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid();
begin
  if not exists (select 1 from public.users where id = me and role = 'SUPER_ADMIN') then
    raise exception 'super_admin_only';
  end if;
  if not exists (select 1 from public.users where id = p_user) then raise exception 'no_such_user'; end if;
  insert into public.admin_perms (user_id, perm, granted_by) values (p_user, p_perm, me)
  on conflict (user_id, perm) do nothing;
  insert into public.admin_audit_log (actor, action, target, meta)
  values (me, 'GRANT_PERM', p_user::text, jsonb_build_object('perm', p_perm));
end $$;

create or replace function public.admin_revoke_perm(p_user uuid, p_perm text)
returns void language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid();
begin
  if not exists (select 1 from public.users where id = me and role = 'SUPER_ADMIN') then
    raise exception 'super_admin_only';
  end if;
  delete from public.admin_perms where user_id = p_user and perm = p_perm;
  insert into public.admin_audit_log (actor, action, target, meta)
  values (me, 'REVOKE_PERM', p_user::text, jsonb_build_object('perm', p_perm));
end $$;

-- read paths: staff only
drop policy if exists admin_perms_read on public.admin_perms;
create policy admin_perms_read on public.admin_perms for select
  using (public.has_admin_perm(auth.uid(), 'ANALYTICS'));

drop policy if exists admin_audit_read on public.admin_audit_log;
create policy admin_audit_read on public.admin_audit_log for select
  using (public.has_admin_perm(auth.uid(), 'ANALYTICS'));

-- ── B. CONFIG (audited, permission-split) ────────────────────────────────────
create or replace function public.admin_set_config(p_key text, p_value jsonb)
returns void language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); old jsonb;
begin
  if p_key in ('monetization','payments','withdrawals') then
    perform public.require_admin_perm('FINANCE');
  else
    perform public.require_admin_perm('CONTENT');
  end if;
  select value into old from public.engine_config where key = p_key;
  insert into public.engine_config (key, value) values (p_key, p_value)
  on conflict (key) do update set value = excluded.value;
  insert into public.admin_audit_log (actor, action, target, meta)
  values (me, 'SET_CONFIG', p_key, jsonb_build_object('old', old, 'new', p_value));
end $$;

insert into public.engine_config (key, value) values (
  'monetization',
  '{
    "upi_id": "7003283024@fam",
    "payee_name": "THE SYSTEM",
    "purchase_enabled": true,
    "withdrawal_enabled": true,
    "withdrawal_min_vc": 1000,
    "withdrawal_max_vc": 100000,
    "vc_per_inr": 2,
    "order_expiry_min": 30,
    "instructions": "Pay the EXACT amount shown to this UPI ID, then submit the UPI reference/UTR and a screenshot of the payment. VC lands only after verification."
  }'::jsonb
) on conflict (key) do nothing;

-- public, redacted view of monetization knobs the client may render
create or replace function public.monetization_public()
returns jsonb language plpgsql security definer stable set search_path = public as $$
declare cfg jsonb;
begin
  select value into cfg from public.engine_config where key = 'monetization';
  return jsonb_build_object(
    'upi_id', coalesce(cfg->>'upi_id', '7003283024@fam'),
    'payee_name', coalesce(cfg->>'payee_name', 'THE SYSTEM'),
    'purchase_enabled', coalesce((cfg->>'purchase_enabled')::boolean, true),
    'withdrawal_enabled', coalesce((cfg->>'withdrawal_enabled')::boolean, true),
    'withdrawal_min_vc', coalesce((cfg->>'withdrawal_min_vc')::bigint, 1000),
    'withdrawal_max_vc', coalesce((cfg->>'withdrawal_max_vc')::bigint, 100000),
    'vc_per_inr', coalesce((cfg->>'vc_per_inr')::numeric, 2),
    'instructions', coalesce(cfg->>'instructions', 'Pay the exact amount, then submit UTR + screenshot.')
  );
end $$;

-- ── C. VC PACKAGES + PAYMENT ORDERS ──────────────────────────────────────────
create table if not exists public.vc_packages (
  id         text primary key,
  label      text not null,
  vc_amount  bigint not null check (vc_amount > 0),
  price_inr  numeric(10,2) not null check (price_inr > 0),
  active     boolean not null default true,
  sort       int not null default 0
);
alter table public.vc_packages enable row level security;
drop policy if exists vc_packages_read on public.vc_packages;
create policy vc_packages_read on public.vc_packages for select using (active or public.has_admin_perm(auth.uid(),'FINANCE'));

-- 200 VC = ₹100 business rate (₹0.50 / VC)
insert into public.vc_packages (id, label, vc_amount, price_inr, sort) values
  ('pack_200',  '200 VC',   200,   100.00, 1),
  ('pack_500',  '500 VC',   500,   250.00, 2),
  ('pack_1000', '1,000 VC', 1000,  500.00, 3),
  ('pack_2500', '2,500 VC', 2500, 1250.00, 4)
on conflict (id) do nothing;

create table if not exists public.payment_orders (
  id              uuid primary key default gen_random_uuid(),
  order_ref       text not null unique default ('SYS-' || upper(substr(replace(gen_random_uuid()::text,'-',''),1,10))),
  user_id         uuid not null references public.users(id),
  package_id      text not null references public.vc_packages(id),
  vc_amount       bigint not null check (vc_amount > 0),
  price_inr       numeric(10,2) not null check (price_inr > 0),
  status          text not null default 'AWAITING_PAYMENT' check (status in
    ('CREATED','AWAITING_PAYMENT','SUBMITTED','VERIFYING','MANUAL_REVIEW','VERIFIED','CREDITED','REJECTED','EXPIRED','REFUNDED','REVERSED')),
  upi_utr         text,
  screenshot_path text,
  ocr             jsonb,                    -- client ML Kit extraction HINT — untrusted
  review_note     text,
  reviewed_by     uuid references public.users(id),
  reviewed_at     timestamptz,
  created_at      timestamptz not null default now(),
  expires_at      timestamptz not null default now() + interval '30 minutes'
);
-- the exactly-once spine: one UTR can ever bind to one order
create unique index if not exists payment_orders_utr_unique
  on public.payment_orders (upi_utr) where upi_utr is not null;
create index if not exists payment_orders_user_idx on public.payment_orders (user_id, created_at desc);
create index if not exists payment_orders_queue_idx on public.payment_orders (status, created_at);

alter table public.payment_orders enable row level security;
drop policy if exists payment_orders_read_own on public.payment_orders;
create policy payment_orders_read_own on public.payment_orders for select
  using (auth.uid() = user_id or public.has_admin_perm(auth.uid(),'FINANCE'));
-- writes flow through RPCs only — no direct insert/update policies

alter table public.users add column if not exists vc_held bigint not null default 0;

create or replace function public.expire_stale_vc_orders() returns void
language plpgsql security definer set search_path = public as $$
begin
  update public.payment_orders set status = 'EXPIRED'
   where status = 'AWAITING_PAYMENT' and expires_at < now();
end $$;

create or replace function public.create_vc_order(p_package text)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  me uuid := auth.uid(); pkg public.vc_packages%rowtype; cfg jsonb;
  oid uuid; oref text; exp_min int;
begin
  if me is null then raise exception 'unauthorized'; end if;
  perform public.expire_stale_vc_orders();
  select * into pkg from public.vc_packages where id = p_package and active;
  if not found then raise exception 'package_unavailable'; end if;
  select value into cfg from public.engine_config where key = 'monetization';
  if not coalesce((cfg->>'purchase_enabled')::boolean, true) then raise exception 'purchases_paused'; end if;
  exp_min := coalesce((cfg->>'order_expiry_min')::int, 30);
  -- one live order per hunter: older ones lapse
  update public.payment_orders set status='EXPIRED'
   where user_id = me and status = 'AWAITING_PAYMENT';
  insert into public.payment_orders (user_id, package_id, vc_amount, price_inr, expires_at)
  values (me, pkg.id, pkg.vc_amount, pkg.price_inr, now() + make_interval(mins => exp_min))
  returning id, order_ref into oid, oref;
  return jsonb_build_object(
    'order_id', oid, 'order_ref', oref,
    'vc_amount', pkg.vc_amount, 'price_inr', pkg.price_inr,
    'upi_id', coalesce(cfg->>'upi_id', '7003283024@fam'),
    'payee_name', coalesce(cfg->>'payee_name', 'THE SYSTEM'),
    'instructions', coalesce(cfg->>'instructions', 'Pay the exact amount, then submit UTR + screenshot.')
  );
end $$;

create or replace function public.submit_vc_order_proof(
  p_order uuid, p_utr text, p_screenshot text, p_ocr jsonb default '{}'::jsonb
) returns void language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); o public.payment_orders%rowtype; utr text := upper(trim(p_utr));
begin
  select * into o from public.payment_orders where id = p_order for update;
  if not found or o.user_id <> me then raise exception 'no_order'; end if;
  if o.expires_at < now() then
    update public.payment_orders set status='EXPIRED' where id = o.id;
    raise exception 'order_expired';
  end if;
  if o.status <> 'AWAITING_PAYMENT' then raise exception 'order_not_payable'; end if;
  if utr !~ '^[A-Z0-9]{8,22}$' then raise exception 'bad_utr'; end if;
  if exists (select 1 from public.payment_orders where upi_utr = utr and id <> o.id) then
    raise exception 'duplicate_utr';
  end if;
  update public.payment_orders
     set upi_utr = utr, screenshot_path = p_screenshot, ocr = p_ocr, status = 'SUBMITTED'
   where id = o.id;
exception when unique_violation then
  raise exception 'duplicate_utr';
end $$;

create or replace function public.admin_review_vc_order(
  p_order uuid, p_approve boolean, p_note text default null
) returns text language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); o public.payment_orders%rowtype;
begin
  perform public.require_admin_perm('FINANCE');
  select * into o from public.payment_orders where id = p_order for update;
  if not found then raise exception 'no_order'; end if;
  if o.status not in ('SUBMITTED','VERIFYING','MANUAL_REVIEW') then
    raise exception 'already_final';
  end if;
  if p_approve then
    -- exactly-once credit: the row lock + status guard make a second call a no-op
    perform public.credit_vc(o.user_id, o.vc_amount, 'VC_PURCHASE ' || o.package_id, o.order_ref);
    update public.payment_orders
       set status='CREDITED', reviewed_by=me, reviewed_at=now(), review_note=p_note
     where id = o.id;
    insert into public.user_notifications (user_id, kind, title, body, meta)
    values (o.user_id, 'PAYMENT', 'VC CREDITED',
            o.vc_amount || ' VC landed — order ' || o.order_ref || ' verified. Spend them well, Hunter.',
            jsonb_build_object('order', o.order_ref, 'vc', o.vc_amount));
    insert into public.admin_audit_log (actor, action, target, meta)
    values (me, 'APPROVE_VC_ORDER', o.id::text,
            jsonb_build_object('order_ref', o.order_ref, 'vc', o.vc_amount, 'utr', o.upi_utr));
    return 'CREDITED';
  else
    if p_note is null or btrim(p_note) = '' then raise exception 'rejection_reason_required'; end if;
    update public.payment_orders
       set status='REJECTED', reviewed_by=me, reviewed_at=now(), review_note=p_note
     where id = o.id;
    insert into public.user_notifications (user_id, kind, title, body, meta)
    values (o.user_id, 'PAYMENT', 'PURCHASE REJECTED',
            'Order ' || o.order_ref || ' could not be verified: ' || p_note,
            jsonb_build_object('order', o.order_ref));
    insert into public.admin_audit_log (actor, action, target, meta)
    values (me, 'REJECT_VC_ORDER', o.id::text, jsonb_build_object('order_ref', o.order_ref, 'reason', p_note));
    return 'REJECTED';
  end if;
end $$;

-- ── D. WITHDRAWALS (held-balance law) ────────────────────────────────────────
create table if not exists public.withdrawal_requests (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references public.users(id),
  vc_amount   bigint not null check (vc_amount > 0),
  fee_inr     numeric(10,2) not null default 0,
  payout_inr  numeric(10,2) not null check (payout_inr >= 0),
  method      text not null default 'UPI' check (method in ('UPI')),
  payout      jsonb not null,             -- {name, upi} — masked in list views
  status      text not null default 'PENDING_REVIEW' check (status in
    ('PENDING_REVIEW','APPROVED','PROCESSING','PAID','REJECTED','FAILED','CANCELLED')),
  payout_ref  text,
  review_note text,
  reviewed_by uuid references public.users(id),
  reviewed_at timestamptz,
  created_at  timestamptz not null default now()
);
create unique index if not exists withdrawal_payout_ref_unique
  on public.withdrawal_requests (payout_ref) where payout_ref is not null;
create index if not exists withdrawal_user_idx on public.withdrawal_requests (user_id, created_at desc);
create index if not exists withdrawal_queue_idx on public.withdrawal_requests (status, created_at);

alter table public.withdrawal_requests enable row level security;
drop policy if exists withdrawal_read on public.withdrawal_requests;
create policy withdrawal_read on public.withdrawal_requests for select
  using (auth.uid() = user_id or public.has_admin_perm(auth.uid(),'FINANCE'));

create or replace function public.request_withdrawal(p_vc bigint, p_method text, p_payout jsonb)
returns uuid language plpgsql security definer set search_path = public as $$
declare
  me uuid := auth.uid(); cfg jsonb; wid uuid;
  rate numeric; pay numeric; uname text; uupi text;
begin
  if me is null then raise exception 'unauthorized'; end if;
  select value into cfg from public.engine_config where key = 'monetization';
  if not coalesce((cfg->>'withdrawal_enabled')::boolean, true) then raise exception 'withdrawals_paused'; end if;
  if p_method <> 'UPI' then raise exception 'unsupported_method'; end if;
  if p_vc < coalesce((cfg->>'withdrawal_min_vc')::bigint, 1000) then raise exception 'below_minimum'; end if;
  if p_vc > coalesce((cfg->>'withdrawal_max_vc')::bigint, 100000) then raise exception 'above_maximum'; end if;
  if p_vc % 200 <> 0 then raise exception 'amount_must_be_multiple_of_200'; end if;
  uname := btrim(coalesce(p_payout->>'name',''));
  uupi  := btrim(coalesce(p_payout->>'upi',''));
  if char_length(uname) < 3 then raise exception 'bad_payee_name'; end if;
  if uupi !~ '^[A-Za-z0-9._-]{2,}@[A-Za-z]{2,}$' then raise exception 'bad_upi_id'; end if;
  if exists (select 1 from public.withdrawal_requests
             where user_id = me and status in ('PENDING_REVIEW','APPROVED','PROCESSING')) then
    raise exception 'request_in_flight';
  end if;
  rate := 1 / coalesce((cfg->>'vc_per_inr')::numeric, 2);
  pay  := round(p_vc * rate, 2);
  -- atomic hold: balance leaves circulation immediately, lands in vc_held
  update public.users set vc_balance = vc_balance - p_vc, vc_held = vc_held + p_vc
   where id = me and vc_balance >= p_vc;
  if not found then raise exception 'insufficient_vc'; end if;
  insert into public.withdrawal_requests (user_id, vc_amount, fee_inr, payout_inr, method, payout)
  values (me, p_vc, 0, pay, p_method, jsonb_build_object('name', uname, 'upi', uupi))
  returning id into wid;
  insert into public.admin_audit_log (actor, action, target, meta)
  values (me, 'REQUEST_WITHDRAWAL', wid::text, jsonb_build_object('vc', p_vc, 'inr', pay));
  return wid;
end $$;

create or replace function public.admin_review_withdrawal(
  p_id uuid, p_action text, p_ref text default null, p_note text default null
) returns text language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); w public.withdrawal_requests%rowtype;
begin
  perform public.require_admin_perm('FINANCE');
  select * into w from public.withdrawal_requests where id = p_id for update;
  if not found then raise exception 'no_request'; end if;
  case p_action
    when 'approve' then
      if w.status <> 'PENDING_REVIEW' then raise exception 'bad_transition'; end if;
      update public.withdrawal_requests set status='APPROVED', reviewed_by=me, reviewed_at=now(), review_note=p_note where id=w.id;
    when 'process' then
      if w.status <> 'APPROVED' then raise exception 'bad_transition'; end if;
      update public.withdrawal_requests set status='PROCESSING' where id=w.id;
    when 'paid' then
      if w.status not in ('APPROVED','PROCESSING') then raise exception 'bad_transition'; end if;
      if p_ref is null or btrim(p_ref) = '' then raise exception 'payout_ref_required'; end if;
      update public.withdrawal_requests set status='PAID', payout_ref=btrim(p_ref), reviewed_by=me, reviewed_at=now(), review_note=p_note where id=w.id;
      -- hold is CONSUMED (money left the system)
      update public.users set vc_held = vc_held - w.vc_amount where id = w.user_id;
      insert into public.user_notifications (user_id, kind, title, body, meta)
      values (w.user_id, 'WITHDRAWAL', 'PAYOUT SENT',
              w.vc_amount || ' VC settled as ₹' || w.payout_inr || ' — ref ' || btrim(p_ref) || '. Check your UPI app.',
              jsonb_build_object('withdrawal', w.id, 'ref', btrim(p_ref)));
    when 'reject', 'fail' then
      if w.status in ('PAID','REJECTED','FAILED','CANCELLED') then raise exception 'bad_transition'; end if;
      if p_action = 'reject' and (p_note is null or btrim(p_note) = '') then raise exception 'rejection_reason_required'; end if;
      update public.withdrawal_requests
         set status = case p_action when 'reject' then 'REJECTED' else 'FAILED' end,
             reviewed_by=me, reviewed_at=now(), review_note=p_note
       where id = w.id;
      -- hold RELEASED BACK into circulation via the ledger primitive
      update public.users set vc_held = vc_held - w.vc_amount where id = w.user_id;
      perform public.credit_vc(w.user_id, w.vc_amount, 'WITHDRAWAL_' || upper(p_action) || '_RELEASE', w.id::text);
      insert into public.user_notifications (user_id, kind, title, body, meta)
      values (w.user_id, 'WITHDRAWAL', 'WITHDRAWAL ' || case p_action when 'reject' then 'REJECTED' else 'FAILED' end,
              coalesce(p_note, 'The held VC returned to your balance.'),
              jsonb_build_object('withdrawal', w.id));
    else raise exception 'bad_action';
  end case;
  insert into public.admin_audit_log (actor, action, target, meta)
  values (me, 'WITHDRAWAL_' || upper(p_action), w.id::text,
          jsonb_build_object('vc', w.vc_amount, 'ref', p_ref, 'note', p_note));
  return (select status from public.withdrawal_requests where id = w.id);
end $$;

-- ── E. USER NOTIFICATIONS (operational rail) ─────────────────────────────────
create table if not exists public.user_notifications (
  id         bigint generated always as identity primary key,
  user_id    uuid not null references public.users(id) on delete cascade,
  kind       text not null,
  title      text not null,
  body       text,
  meta       jsonb not null default '{}'::jsonb,
  read_at    timestamptz,
  created_at timestamptz not null default now()
);
create index if not exists user_notifications_idx on public.user_notifications (user_id, created_at desc);
alter table public.user_notifications enable row level security;
drop policy if exists user_notifications_read on public.user_notifications;
create policy user_notifications_read on public.user_notifications for select using (auth.uid() = user_id);
drop policy if exists user_notifications_stamp on public.user_notifications;
create policy user_notifications_stamp on public.user_notifications for update
  using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- ── F. SUPPORT INBOX ─────────────────────────────────────────────────────────
create table if not exists public.support_conversations (
  id              uuid primary key default gen_random_uuid(),
  user_id         uuid not null references public.users(id),
  subject         text,
  status          text not null default 'OPEN' check (status in ('OPEN','ASSIGNED','WAITING_FOR_USER','WAITING_FOR_SUPPORT','RESOLVED','REOPENED')),
  priority        text not null default 'NORMAL',
  assigned_to     uuid references public.users(id),
  resolved_reason text,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now()
);
create index if not exists support_conv_user_idx on public.support_conversations (user_id, created_at desc);
create index if not exists support_conv_queue_idx on public.support_conversations (status, updated_at desc);

create table if not exists public.support_messages (
  id              bigint generated always as identity primary key,
  conversation_id uuid not null references public.support_conversations(id) on delete cascade,
  sender_id       uuid not null references public.users(id),
  staff           boolean not null default false,
  body            text not null check (char_length(body) between 1 and 2000),
  read_at         timestamptz,
  created_at      timestamptz not null default now()
);
create index if not exists support_msg_conv_idx on public.support_messages (conversation_id, created_at);

alter table public.support_conversations enable row level security;
alter table public.support_messages enable row level security;

drop policy if exists support_conv_read on public.support_conversations;
create policy support_conv_read on public.support_conversations for select
  using (auth.uid() = user_id or public.has_admin_perm(auth.uid(),'SUPPORT'));

drop policy if exists support_msg_read on public.support_messages;
create policy support_msg_read on public.support_messages for select
  using (exists (
    select 1 from public.support_conversations c
    where c.id = conversation_id and (c.user_id = auth.uid() or public.has_admin_perm(auth.uid(),'SUPPORT'))
  ));

create or replace function public.support_open(p_subject text, p_body text)
returns uuid language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); cid uuid;
begin
  if me is null then raise exception 'unauthorized'; end if;
  if p_body is null or btrim(p_body) = '' then raise exception 'empty_message'; end if;
  -- one live conversation per hunter — reopen rather than duplicate
  select id into cid from public.support_conversations
   where user_id = me and status not in ('RESOLVED')
   order by created_at desc limit 1;
  if cid is null then
    insert into public.support_conversations (user_id, subject) values (me, left(coalesce(p_subject,''), 80))
    returning id into cid;
  end if;
  insert into public.support_messages (conversation_id, sender_id, staff, body)
  values (cid, me, false, btrim(p_body));
  update public.support_conversations set status='WAITING_FOR_SUPPORT', updated_at=now() where id = cid;
  return cid;
end $$;

create or replace function public.support_user_reply(p_conv uuid, p_body text)
returns void language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); c public.support_conversations%rowtype;
begin
  select * into c from public.support_conversations where id = p_conv for update;
  if not found or c.user_id <> me then raise exception 'no_conversation'; end if;
  if c.status = 'RESOLVED' then raise exception 'conversation_closed'; end if;
  if p_body is null or btrim(p_body) = '' then raise exception 'empty_message'; end if;
  insert into public.support_messages (conversation_id, sender_id, staff, body)
  values (p_conv, me, false, btrim(p_body));
  update public.support_conversations set status='WAITING_FOR_SUPPORT', updated_at=now() where id = p_conv;
end $$;

create or replace function public.support_admin_reply(p_conv uuid, p_body text)
returns void language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); c public.support_conversations%rowtype;
begin
  perform public.require_admin_perm('SUPPORT');
  select * into c from public.support_conversations where id = p_conv for update;
  if not found then raise exception 'no_conversation'; end if;
  if p_body is null or btrim(p_body) = '' then raise exception 'empty_message'; end if;
  insert into public.support_messages (conversation_id, sender_id, staff, body)
  values (p_conv, me, true, btrim(p_body));
  update public.support_conversations
     set status='WAITING_FOR_USER', assigned_to=coalesce(c.assigned_to, me), updated_at=now()
   where id = p_conv;
  insert into public.user_notifications (user_id, kind, title, body)
  values (c.user_id, 'SUPPORT', 'SUPPORT REPLIED', 'The System answered your ticket — open Support to read it.');
end $$;

create or replace function public.support_admin_status(p_conv uuid, p_status text, p_reason text default null)
returns void language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); c public.support_conversations%rowtype;
begin
  perform public.require_admin_perm('SUPPORT');
  select * into c from public.support_conversations where id = p_conv for update;
  if not found then raise exception 'no_conversation'; end if;
  if p_status not in ('OPEN','ASSIGNED','WAITING_FOR_USER','WAITING_FOR_SUPPORT','RESOLVED','REOPENED') then
    raise exception 'bad_status';
  end if;
  if p_status = 'RESOLVED' and (p_reason is null or btrim(p_reason) = '') then
    raise exception 'resolution_reason_required';
  end if;
  update public.support_conversations
     set status = p_status,
         resolved_reason = case when p_status = 'RESOLVED' then btrim(p_reason) else resolved_reason end,
         assigned_to = case when p_status = 'ASSIGNED' then me else assigned_to end,
         updated_at = now()
   where id = p_conv;
  insert into public.admin_audit_log (actor, action, target, meta)
  values (me, 'SUPPORT_' || p_status, p_conv::text, jsonb_build_object('reason', p_reason));
end $$;

create or replace function public.support_mark_read(p_conv uuid)
returns void language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid();
begin
  update public.support_messages m set read_at = now()
   where m.conversation_id = p_conv and m.sender_id <> me and m.read_at is null
     and exists (select 1 from public.support_conversations c
                 where c.id = p_conv and (c.user_id = me or public.has_admin_perm(me,'SUPPORT')));
end $$;

-- ── G. OPS METRICS — every figure has a source + window ──────────────────────
create or replace function public.admin_ops_metrics()
returns jsonb language plpgsql security definer stable set search_path = public as $$
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
    'predictions_open', (select count(*) from public.prediction_pools where status = 'OPEN'),
    'predictions_unsettled', (select count(*) from public.prediction_pools where status in ('OPEN','LOCKED')),
    'support_open', (select count(*) from public.support_conversations where status not in ('RESOLVED')),
    'support_waiting', (select count(*) from public.support_conversations where status = 'WAITING_FOR_SUPPORT')
  );
end $$;

-- ── H. PAYMENT PROOFS BUCKET (private; owner write/read, FINANCE read) ───────
insert into storage.buckets (id, name, public)
values ('payment-proofs', 'payment-proofs', false)
on conflict (id) do nothing;

drop policy if exists payproof_insert on storage.objects;
create policy payproof_insert on storage.objects for insert
  with check (bucket_id = 'payment-proofs' and auth.role() = 'authenticated'
              and (storage.foldername(name))[1] = auth.uid()::text);

drop policy if exists payproof_read on storage.objects;
create policy payproof_read on storage.objects for select
  using (bucket_id = 'payment-proofs'
         and ((storage.foldername(name))[1] = auth.uid()::text
              or public.has_admin_perm(auth.uid(),'FINANCE')));

drop policy if exists payproof_delete on storage.objects;
create policy payproof_delete on storage.objects for delete
  using (bucket_id = 'payment-proofs' and (storage.foldername(name))[1] = auth.uid()::text);

create or replace function public.admin_get_config(p_key text)
returns jsonb language plpgsql security definer stable set search_path = public as $$
begin
  perform public.require_admin_perm('ANALYTICS');
  return (select value from public.engine_config where key = p_key);
end $$;
