-- ============================================================
-- THE SYSTEM — Migration 018
-- MENTAL ASCENSION (spec §2/§6/§7) — Chess as first-class discipline.
--
-- LAWS:
--  • Rating / mental XP / stats are SERVER-COMPUTED (RPC only).
--    Clients never write economy fields directly (§20/§21).
--  • Stats evolve from performance via bounded EMA — never random.
--  • Synergy (§8) is once/day, requires BOTH body + mind proof.
--  • No wagering anywhere in this pillar (§1).
-- ============================================================

CREATE TABLE IF NOT EXISTS public.chess_profile (
    user_id            uuid PRIMARY KEY REFERENCES auth.users ON DELETE CASCADE,
    rating             int   NOT NULL DEFAULT 400 CHECK (rating BETWEEN 100 AND 3200),
    mental_xp          bigint NOT NULL DEFAULT 0,
    games              int   NOT NULL DEFAULT 0,
    wins               int   NOT NULL DEFAULT 0,
    losses             int   NOT NULL DEFAULT 0,
    draws              int   NOT NULL DEFAULT 0,
    puzzles_solved     int   NOT NULL DEFAULT 0,
    puzzles_attempted  int   NOT NULL DEFAULT 0,
    stats              jsonb NOT NULL DEFAULT '{}'::jsonb, -- tactics/focus/memory/calculation/adaptability/decision/composure (0..100)
    last_session_at    timestamptz,
    updated_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.chess_sessions (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      uuid NOT NULL REFERENCES auth.users ON DELETE CASCADE,
    kind         text NOT NULL,        -- GAME | PUZZLE | DAILY
    mode         text NOT NULL,        -- MENTAL_WAR | AI_TRAINING | BLITZ | RAPID | CLASSICAL | ENDGAME | TIME_PRESSURE | PUZZLE | DAILY_CHALLENGE
    result       text NOT NULL,        -- WIN | LOSS | DRAW | SOLVED | FAILED
    accuracy     numeric,              -- 0..100 (games), null ok
    blunders     int,                  -- 0..50
    think_ms     int,                  -- avg ms per decision
    rating_after int,
    xp_gained    int  NOT NULL DEFAULT 0,
    analysis     jsonb NOT NULL DEFAULT '{}'::jsonb, -- best/worst moment, targets
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS chess_sessions_user_date ON chess_sessions (user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS public.mental_quest_claims (
    user_id    uuid NOT NULL REFERENCES auth.users ON DELETE CASCADE,
    quest_date date NOT NULL DEFAULT current_date,
    title      text NOT NULL,
    xp         int  NOT NULL DEFAULT 0,
    UNIQUE (user_id, quest_date, title)
);

CREATE TABLE IF NOT EXISTS public.synergy_claims (
    user_id    uuid NOT NULL REFERENCES auth.users ON DELETE CASCADE,
    quest_date date NOT NULL DEFAULT current_date,
    PRIMARY KEY (user_id, quest_date)
);

ALTER TABLE public.chess_profile       ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.chess_sessions      ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.mental_quest_claims ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.synergy_claims      ENABLE ROW LEVEL SECURITY;

-- read-only for owners; ALL writes flow through the RPCs below (definer)
DROP POLICY IF EXISTS chess_profile_read_own ON public.chess_profile;
CREATE POLICY chess_profile_read_own ON public.chess_profile
    FOR SELECT USING (auth.uid() = user_id);

DROP POLICY IF EXISTS chess_sessions_read_own ON public.chess_sessions;
CREATE POLICY chess_sessions_read_own ON public.chess_sessions
    FOR SELECT USING (auth.uid() = user_id);

DROP POLICY IF EXISTS mental_claims_read_own ON public.mental_quest_claims;
CREATE POLICY mental_claims_read_own ON public.mental_quest_claims
    FOR SELECT USING (auth.uid() = user_id);

DROP POLICY IF EXISTS synergy_read_own ON public.synergy_claims;
CREATE POLICY synergy_read_own ON public.synergy_claims
    FOR SELECT USING (auth.uid() = user_id);

-- ── log_chess_session ────────────────────────────────────────────────────────
-- Validates + clamps everything, applies bounded EMA to mental stats,
-- computes rating delta + mental XP. Deterministic. No AI anywhere near money.
CREATE OR REPLACE FUNCTION public.log_chess_session(
    p_kind      text,
    p_mode      text,
    p_result    text,
    p_accuracy  numeric DEFAULT NULL,
    p_blunders  int     DEFAULT 0,
    p_think_ms  int     DEFAULT 0,
    p_stats     jsonb   DEFAULT '{}'::jsonb,
    p_analysis  jsonb   DEFAULT '{}'::jsonb
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    v_user uuid := auth.uid();
    v_prof public.chess_profile%ROWTYPE;
    v_delta int := 0;
    v_xp int := 0;
    v_new_rating int;
    v_key  text;
    v_val  numeric;
    v_stats jsonb;
    v_allowed_stats text[] := ARRAY['tactics','focus','memory','calculation','adaptability','decision','composure'];
BEGIN
    IF v_user IS NULL THEN RAISE EXCEPTION 'auth required'; END IF;
    IF p_kind NOT IN ('GAME','PUZZLE','DAILY') THEN RAISE EXCEPTION 'bad kind'; END IF;
    IF p_result NOT IN ('WIN','LOSS','DRAW','SOLVED','FAILED') THEN RAISE EXCEPTION 'bad result'; END IF;

    p_accuracy := CASE WHEN p_accuracy IS NULL THEN NULL ELSE least(100, greatest(0, p_accuracy)) END;
    p_blunders := least(50, greatest(0, coalesce(p_blunders, 0)));
    p_think_ms := least(600000, greatest(0, coalesce(p_think_ms, 0)));

    INSERT INTO public.chess_profile (user_id) VALUES (v_user)
        ON CONFLICT (user_id) DO NOTHING;
    SELECT * INTO v_prof FROM public.chess_profile WHERE user_id = v_user FOR UPDATE;

    -- rating delta: games only, flat deterministic steps
    IF p_kind = 'GAME' THEN
        v_delta := CASE p_result WHEN 'WIN' THEN 12 WHEN 'DRAW' THEN 2 ELSE -10 END;
        v_xp := 8 + floor(coalesce(p_accuracy, 40) / 12)::int + CASE p_result WHEN 'WIN' THEN 6 WHEN 'DRAW' THEN 2 ELSE 0 END;
    ELSIF p_kind = 'DAILY' THEN
        v_xp := CASE p_result WHEN 'SOLVED' THEN 6 ELSE 1 END;
    ELSE -- PUZZLE
        v_xp := CASE p_result WHEN 'SOLVED' THEN 3 ELSE 1 END;
    END IF;
    v_new_rating := least(3200, greatest(100, v_prof.rating + v_delta));

    -- bounded EMA on whitelisted stat keys: 85% history, 15% latest reading
    v_stats := v_prof.stats;
    FOR v_key, v_val IN SELECT key, value::numeric FROM jsonb_each_text(coalesce(p_stats,'{}'::jsonb)) LOOP
        IF v_key = ANY (v_allowed_stats) THEN
            v_val := least(100, greatest(0, v_val));
            v_stats := jsonb_set(
                v_stats, ARRAY[v_key],
                to_jsonb(round(coalesce((v_stats->>v_key)::numeric, 40) * 0.85 + v_val * 0.15))
            );
        END IF;
    END LOOP;

    UPDATE public.chess_profile SET
        rating = v_new_rating,
        mental_xp = mental_xp + v_xp,
        games   = games   + CASE WHEN p_kind = 'GAME' THEN 1 ELSE 0 END,
        wins    = wins    + CASE WHEN p_kind = 'GAME' AND p_result = 'WIN' THEN 1 ELSE 0 END,
        losses  = losses  + CASE WHEN p_kind = 'GAME' AND p_result = 'LOSS' THEN 1 ELSE 0 END,
        draws   = draws   + CASE WHEN p_kind = 'GAME' AND p_result = 'DRAW' THEN 1 ELSE 0 END,
        puzzles_solved   = puzzles_solved    + CASE WHEN p_result = 'SOLVED' THEN 1 ELSE 0 END,
        puzzles_attempted= puzzles_attempted + CASE WHEN p_kind IN ('PUZZLE','DAILY') THEN 1 ELSE 0 END,
        stats = v_stats,
        last_session_at = now(),
        updated_at = now()
    WHERE user_id = v_user;

    INSERT INTO public.chess_sessions
        (user_id, kind, mode, result, accuracy, blunders, think_ms, rating_after, xp_gained, analysis)
    VALUES
        (v_user, p_kind, p_mode, p_result, p_accuracy, p_blunders, p_think_ms, v_new_rating, v_xp, coalesce(p_analysis,'{}'::jsonb));

    RETURN jsonb_build_object('rating', v_new_rating, 'xp_gained', v_xp, 'rating_delta', v_delta);
END $$;

-- ── complete_mental_quest ────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION public.complete_mental_quest(p_title text, p_xp int)
RETURNS int LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE v_user uuid := auth.uid(); v_amount int := least(100, greatest(0, p_xp));
BEGIN
    IF v_user IS NULL THEN RAISE EXCEPTION 'auth required'; END IF;
    IF length(coalesce(p_title,'')) < 3 OR length(p_title) > 60 THEN RAISE EXCEPTION 'bad title'; END IF;
    INSERT INTO public.mental_quest_claims (user_id, title, xp) VALUES (v_user, left(p_title,60), v_amount)
        ON CONFLICT (user_id, quest_date, title) DO NOTHING;
    IF NOT FOUND THEN RETURN 0; END IF;
    INSERT INTO public.chess_profile (user_id) VALUES (v_user) ON CONFLICT DO NOTHING;
    UPDATE public.chess_profile SET mental_xp = mental_xp + v_amount, updated_at = now() WHERE user_id = v_user;
    RETURN v_amount;
END $$;

-- ── claim_synergy (§8 BODY × MIND SYNC) ──────────────────────────────────────
-- +25 XP +5 VC, once/day, requires a completed physical quest AND a mental claim today.
CREATE OR REPLACE FUNCTION public.claim_synergy()
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    v_user uuid := auth.uid();
    v_body boolean;
    v_mind boolean;
BEGIN
    IF v_user IS NULL THEN RAISE EXCEPTION 'auth required'; END IF;

    SELECT EXISTS (SELECT 1 FROM public.daily_quests
                   WHERE user_id = v_user AND quest_date = current_date AND completed) INTO v_body;
    SELECT EXISTS (SELECT 1 FROM public.mental_quest_claims
                   WHERE user_id = v_user AND quest_date = current_date) INTO v_mind;

    IF NOT v_body THEN RETURN 'BODY PENDING — finish a physical quest first'; END IF;
    IF NOT v_mind THEN RETURN 'MIND PENDING — finish today''s mental quest first'; END IF;

    INSERT INTO public.synergy_claims (user_id) VALUES (v_user) ON CONFLICT DO NOTHING;
    IF NOT FOUND THEN RETURN 'SYNC ALREADY CLAIMED TODAY'; END IF;

    PERFORM public.apply_xp(v_user, 25, null);
    UPDATE public.users SET vc_balance = vc_balance + 5 WHERE id = v_user;
    INSERT INTO public.vc_transactions (user_id, amount, reason, reference)
        VALUES (v_user, 5, 'SYNERGY_BONUS', 'BODY × MIND SYNC');
    RETURN 'SYNC COMPLETE — +25 XP · +5 VC';
END $$;
