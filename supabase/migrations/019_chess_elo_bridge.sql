-- ============================================================
-- THE SYSTEM — Migration 019
-- CHESS ELO BRIDGE (spec §10/§11/§12)
--
--  • practice_elo — AI-games track, STANDARD ELO math (client computes the
--    proposition, server clamps/rejects; competitive `rating` stays for war).
--  • Anti-farm: games under 8 plies earn a participation floor only and move
--    NO rating anywhere.
--  • Chess XP now bridges into GLOBAL SYSTEM XP (`apply_xp`), capped per day.
--    Fitness XP + Chess XP + Quest XP converge on the single hunter rank —
--    no second progression system is created.
--
-- Backward compatible: old clients call log_chess_session with the original
-- eight arguments; the two new ones default safely.
-- ============================================================

ALTER TABLE public.chess_profile
    ADD COLUMN IF NOT EXISTS practice_elo int NOT NULL DEFAULT 400
    CHECK (practice_elo BETWEEN 100 AND 3200);

-- ── log_chess_session (v2) ───────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION public.log_chess_session(
    p_kind      text,
    p_mode      text,
    p_result    text,
    p_accuracy  numeric DEFAULT NULL,
    p_blunders  int     DEFAULT 0,
    p_think_ms  int     DEFAULT 0,
    p_stats     jsonb   DEFAULT '{}'::jsonb,
    p_analysis  jsonb   DEFAULT '{}'::jsonb,
    p_plies        int  DEFAULT 999,          -- real move count (anti-farm gate)
    p_practice_delta int DEFAULT NULL         -- client's ELO proposition (AI games)
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
    v_practice_delta int := 0;
    v_new_practice int;
    v_today_xp int;
    v_global int;
BEGIN
    IF v_user IS NULL THEN RAISE EXCEPTION 'auth required'; END IF;
    IF p_kind NOT IN ('GAME','PUZZLE','DAILY') THEN RAISE EXCEPTION 'bad kind'; END IF;
    IF p_result NOT IN ('WIN','LOSS','DRAW','SOLVED','FAILED') THEN RAISE EXCEPTION 'bad result'; END IF;

    p_accuracy := CASE WHEN p_accuracy IS NULL THEN NULL ELSE least(100, greatest(0, p_accuracy)) END;
    p_blunders := least(50, greatest(0, coalesce(p_blunders, 0)));
    p_think_ms := least(600000, greatest(0, coalesce(p_think_ms, 0)));
    p_plies    := greatest(0, coalesce(p_plies, 999));

    INSERT INTO public.chess_profile (user_id) VALUES (v_user)
        ON CONFLICT (user_id) DO NOTHING;
    SELECT * INTO v_prof FROM public.chess_profile WHERE user_id = v_user FOR UPDATE;

    IF p_kind = 'GAME' THEN
        v_xp := 8 + floor(coalesce(p_accuracy, 40) / 12)::int + CASE p_result WHEN 'WIN' THEN 6 WHEN 'DRAW' THEN 2 ELSE 0 END;

        -- ANTI-FARM: sub-8-ply "games" are not games. Participation floor only.
        IF p_plies < 8 THEN
            v_xp := least(v_xp, 4);
            p_practice_delta := NULL;                 -- no ELO anywhere
            v_delta := 0;                             -- no competitive delta either
        ELSE
            IF p_mode = 'MENTAL_WAR' THEN
                -- competitive track: server-owned flat steps (as shipped in 018)
                v_delta := CASE p_result WHEN 'WIN' THEN 12 WHEN 'DRAW' THEN 2 ELSE -10 END;
            ELSE
                -- practice track: client's standard-ELO proposition, clamped hard
                IF p_practice_delta IS NOT NULL THEN
                    v_practice_delta := least(48, greatest(-48, p_practice_delta));
                END IF;
                v_delta := 0;
            END IF;
        END IF;
    ELSIF p_kind = 'DAILY' THEN
        v_xp := CASE p_result WHEN 'SOLVED' THEN 6 ELSE 1 END;
    ELSE -- PUZZLE
        v_xp := CASE p_result WHEN 'SOLVED' THEN 3 ELSE 1 END;
    END IF;

    v_new_rating := least(3200, greatest(100, v_prof.rating + v_delta));
    v_new_practice := least(3200, greatest(100, v_prof.practice_elo + v_practice_delta));

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
        practice_elo = v_new_practice,
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
        (v_user, p_kind, p_mode, p_result, p_accuracy, p_blunders, p_think_ms,
         CASE WHEN p_kind = 'GAME' AND p_mode != 'MENTAL_WAR' THEN v_new_practice ELSE v_new_rating END,
         v_xp, coalesce(p_analysis,'{}'::jsonb));

    -- ── GLOBAL XP BRIDGE (§11/§12): chess mind XP feeds the single SYSTEM rank.
    -- Deterministic daily ceiling: 150 XP/day from chess; the bridge starves
    -- farming loops without punishing real training.
    SELECT coalesce(sum(xp_gained), 0) INTO v_today_xp
    FROM public.chess_sessions
    WHERE user_id = v_user AND created_at::date = current_date AND id IS NOT NULL;
    v_global := greatest(0, least(v_xp, 150 - greatest(0, v_today_xp - v_xp)));
    IF v_global > 0 THEN
        PERFORM public.apply_xp(v_user, v_global, null);
    END IF;

    RETURN jsonb_build_object(
        'rating', v_new_rating,
        'xp_gained', v_xp,
        'rating_delta', v_delta,
        'practice_elo', v_new_practice,
        'practice_delta', v_practice_delta,
        'global_xp', v_global
    );
END $$;
