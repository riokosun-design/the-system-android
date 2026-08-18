-- ═══════════════════════════════════════════════════════════════════════════
-- THE SYSTEM — 005: SEED DATA
-- Legal docs v1, training arcs, opening store catalog, runtime config.
-- All are editable from the Admin Panel afterwards.
-- ═══════════════════════════════════════════════════════════════════════════

-- ── Legal documents (dynamic — the app NEVER hardcodes these) ───────────────
insert into public.legal_documents (doc_type, title, content_markdown, version) values
('PRIVACY_POLICY', 'THE SYSTEM — Privacy Policy',
'# Privacy Policy
## What we collect
- Account: Google display name, email (via Google sign-in), your chosen @handle.
- Biometrics you enter: age, height, weight, goal.
- Gameplay: XP, levels, quests, workouts, clan activity, transactions.
- Location: coarse GPS used ONLY while capturing territory zones.
- Media: workout verification photos, auto-purged from our servers 10 minutes after upload.
## What we never do
- We never sell personal data.
- Push-up battle video never leaves your phone — counting is on-device (ML Kit).
## Your rights
- Export or erase your account data any time via Profile → Sign out (contact support for full erasure).
- Data is hosted on Supabase (PostgreSQL) with row-level security on every table.
## Changes
- This document is versioned and published dynamically; the app shows the latest version at onboarding and on update.', 1),
('TERMS_OF_SERVICE', 'THE SYSTEM — Terms of Service',
'# Terms of Service
## The Contract
- One account per hunter. Account sharing voids rank.
- Cheating (GPS spoofing, fake battles, bet abuse) triggers decay, rank loss, or ban.
## Virtual Currency
- VC has no cash value, is non-refundable, and cannot be withdrawn.
- Store purchases, prediction outcomes and referral rewards are final.
## Predictions (Arena)
- Pools take a 15% platform cut before distribution.
- Payout = (your bet ÷ winning-side pool) × (total pool × 0.85).
## Purchases
- Manual UPI payments are verified by admins; approval applies the purchased effect.
- Merch is rank-gated by design. No exceptions, no refunds on lost ranks.
## Liability
- Training arcs are fiction-inspired and extreme. Consult a physician. You train at your own risk.
## Enforcement
- Admins may adjust balances, settle pools, and suspend accounts for abuse.', 1)
on conflict (doc_type) do nothing;

-- ── Runtime config (admin-editable) ──────────────────────────────────────────
insert into public.system_config (key, value) values
  ('upi_id', 'thesystem@upi'),
  ('black_room_price_inr', '499'),
  ('offerwall_url', 'https://YOUR_CPA_OFFERWALL_URL'),
  ('referral_reward_vc', '250'),
  ('platform_cut_bps', '1500')
on conflict (key) do nothing;

-- ── Training arcs: base conditioning first, then the legends ─────────────────
insert into public.training_arcs (id, hero, title, duration_months, unlock_req_arc, min_level, description, disclaimer, sort) values
('SAITAMA', 'Saitama', 'One Punch Conditioning', 4, null, 1,
 '100 push-ups, 100 sit-ups, 100 squats, 10km run. Every day. The base every other arc demands you survive.',
 'EXTREME ROUTINE — medical clearance strongly recommended. Scale volume to your body; THE SYSTEM tracks, you decide.', 1),
('GOKU', 'Goku', 'Saiyan Gravity Program', 4, 'SAITAMA', 10,
 'Weighted calisthenics cycles, explosive hypertrophy blocks, and brutal cardio ladders.',
 'Weighted training risks joint injury. Master SAITAMA base conditioning first. Spotter advised for lifts.', 2),
('JIN_WOO', 'Sung Jin Woo', 'Shadow Monarch Ascension', 4, 'GOKU', 20,
 'Daily dungeon quests: AM/PM split routines, stealth mobility work, and the infamous instant-death penalty quest.',
 'Twice-daily training demands disciplined sleep and nutrition. Skip days at your own peril — the penalty is real.', 3),
('GAROU', 'Garou', 'Hero Hunter Evolution', 4, 'JIN_WOO', 35,
 'Combat-flow drills, high-intensity fight simulations, and volume that should not be legal.',
 'Strike drills require pads, space, and ideally a coach. Evolution hurts — distinguish pain from damage.', 4)
on conflict (id) do nothing;

-- ── Opening store catalog (admins can CRUD this live) ────────────────────────
insert into public.ecommerce_products (name, description, category, price_inr, outbound_url, affiliate_commission_pct, min_rank_required, active) values
('GARBAGE→AVERAGE Tee', 'Entry drop. You escaped the pit — wear the receipt.', 'MERCH', 799, 'https://store.thesystem.app/average-tee', 0, 'AVERAGE', true),
('ELITE Windbreaker', 'Storm-blue shell for hunters who run at dawn.', 'MERCH', 2499, 'https://store.thesystem.app/elite-windbreaker', 0, 'ELITE', true),
('S-RANK Hoodie', 'The hoodie. Shadow-black, purple-thread crest. Rank-gated forever.', 'MERCH', 3999, 'https://store.thesystem.app/s-rank-hoodie', 0, 'S-RANK', true),
('MASTERPIECE Coat', 'Numbered drop. Gold embroidery. If you know, you know.', 'MERCH', 9999, 'https://store.thesystem.app/masterpiece-coat', 0, 'MASTERPIECE', true),
('Whey Isolate 1kg', 'Affiliate partner — cold-filtered, 27g protein/scoop.', 'SUPPLEMENT', 2899, 'https://affiliate.example.com/whey?aff=THESYSTEM', 12, 'AVERAGE', true),
('Pre-Workout: LIMIT BREAK', 'Affiliate partner — 300mg caffeine. Respect the label.', 'SUPPLEMENT', 1699, 'https://affiliate.example.com/preworkout?aff=THESYSTEM', 15, 'AVERAGE', true),
('Creatine Monohydrate 300g', 'Affiliate partner — micronized, unflavored.', 'SUPPLEMENT', 999, 'https://affiliate.example.com/creatine?aff=THESYSTEM', 10, 'AVERAGE', true)
on conflict do nothing;

-- ── Theme asset slots (upload the actual art via Admin → ASSETS) ─────────────
insert into public.dynamic_assets (key, type, title, storage_path, fade_opacity, enabled, sort_order) values
  ('DASHBOARD_HERO', 'CHARACTER_WALLPAPER', 'Jin Woo — Shadow Monarch', 'CHARACTER_WALLPAPER/jinwoo.jpg', 0.22, false, 1),
  ('ARENA_BG', 'CHARACTER_WALLPAPER', 'Garou — Hero Hunter', 'CHARACTER_WALLPAPER/garou.jpg', 0.16, false, 2),
  ('PROTOCOL_BG', 'CHARACTER_WALLPAPER', 'Guts — Black Swordsman', 'CHARACTER_WALLPAPER/guts.jpg', 0.14, false, 3),
  ('PROFILE_BG', 'CHARACTER_WALLPAPER', 'Goku — Ultra Instinct', 'CHARACTER_WALLPAPER/goku.jpg', 0.18, false, 4),
  ('AMBIENT_VOID', 'AMBIENT_BACKGROUND', 'Void Grid', 'AMBIENT_BACKGROUND/void.jpg', 0.25, false, 9)
on conflict do nothing;
-- enabled=false on purpose: flip them live from Admin → ASSETS once the art is in the bucket.
