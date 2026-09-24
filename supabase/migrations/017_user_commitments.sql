-- ============================================================
-- THE SYSTEM — Migration 017
-- USER COMMITMENTS — daily routine anchors for the personal AI.
-- Blocks are owned by the user (owner-auth only via RLS).
-- Blocks: [{ "t":"College","s":"08:00","e":"16:30","d":"DAILY" }]
--   t = title, s = start "HH:MM", e = end "HH:MM", d = days tag
-- ============================================================

CREATE TABLE IF NOT EXISTS public.user_commitments (
    user_id    uuid PRIMARY KEY REFERENCES auth.users ON DELETE CASCADE,
    blocks     jsonb NOT NULL DEFAULT '[]'::jsonb,
    updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE public.user_commitments ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS commitments_rw_own ON public.user_commitments;
CREATE POLICY commitments_rw_own ON public.user_commitments
    FOR ALL USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);
