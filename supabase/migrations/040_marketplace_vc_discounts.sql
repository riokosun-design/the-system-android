-- 040 — VC UTILITY: MARKETPLACE DISCOUNTS (directive §2)
-- VC spends on exactly two rails: tournament entry stakes and product
-- discounts. Admins set the ratio per product (vc_discount_cost ↔ flat ₹ or
-- %); redemption is one atomic server RPC — ledger first, balance second,
-- replay-proof by unique reference.

alter table public.ecommerce_products
  add column if not exists vc_discount_cost   bigint        not null default 0 check (vc_discount_cost >= 0),
  add column if not exists discount_percentage int          not null default 0 check (discount_percentage between 0 and 90),
  add column if not exists discount_flat_inr   numeric(10,2) not null default 0 check (discount_flat_inr >= 0);

create unique index if not exists vc_tx_marketplace_ref_uq
  on public.vc_transactions (reference)
  where reason = 'MARKETPLACE_DISCOUNT' and reference is not null;

create or replace function public.redeem_vc_discount(p_product uuid, p_tx text default null)
returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  me uuid := auth.uid();
  pr public.ecommerce_products%rowtype;
  v_discount numeric(10,2);
begin
  if me is null then raise exception 'auth_required'; end if;

  select * into pr from public.ecommerce_products where id = p_product for update;
  if not found or not pr.active then raise exception 'no_product'; end if;
  if pr.vc_discount_cost <= 0 then raise exception 'no_vc_discount'; end if;

  -- flat discount wins when both are set (flat is the admin's exact number)
  v_discount := case
    when pr.discount_flat_inr > 0 then least(pr.discount_flat_inr, pr.price_inr)
    when pr.discount_percentage > 0 then round(pr.price_inr * pr.discount_percentage / 100.0, 2)
    else 0 end;
  if v_discount <= 0 then raise exception 'no_vc_discount'; end if;

  -- atomic debit (insufficient_vc raises; identical p_tx replays are no-ops)
  perform public.credit_vc(me, -pr.vc_discount_cost, 'MARKETPLACE_DISCOUNT',
    coalesce(nullif(p_tx, ''), 'redeem:' || pr.id::text || ':' || me::text || ':' || extract(epoch from clock_timestamp())::bigint::text));

  return jsonb_build_object(
    'product', pr.name,
    'vc_spent', pr.vc_discount_cost,
    'discount_inr', v_discount,
    'discount_pct', pr.discount_percentage,
    'final_price_inr', greatest(pr.price_inr - v_discount, 0));
end $$;
