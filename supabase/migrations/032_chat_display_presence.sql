-- ════════════════════════════════════════════════════════════════════════════
-- THE SYSTEM — 032: CHAT DISPLAY NAMES + PRESENCE SURFACE (§4.1)
-- The sender view carries display_name so bubbles/headers render the hunter's
-- chosen name instead of the @handle. Presence uses users.last_seen_at (031).
-- ════════════════════════════════════════════════════════════════════════════

-- (drop+create: CREATE OR REPLACE cannot reorder — appended column would land
--  after sender_username and break positional decoders)
drop view if exists public.messages_with_sender;
create view public.messages_with_sender with (security_invoker = true) as
select m.*, u.username as sender_username, u.display_name as sender_display_name
from public.messages m join public.users u on u.id = m.sender_id;
