package com.thesystem.app.core.sport

/**
 * SPORT PATHS — the athlete branch of the System (STEP 6).
 *
 * Contract law:
 *  - [Sport.id] is the EXACT value stored in `users.sport` on the server.
 *    Never rename an id — rename labels freely.
 *  - The explicit opt-out is NOT a sport: it is the NULL profile column.
 *    "No sport" means no sport content anywhere; clients only ever render a
 *    path the server actually issued.
 *  - Content (titles/targets per day) lives ONLY in server templates
 *    (`daily_quest_templates.sport/.pillar/.variant`). This file carries
 *    display metadata + the deterministic rotation math — the same formula
 *    the SQL uses, so the client can PREDICT the cycle but the server
 *    always DECIDES it.
 *  - Adding a sport = one enum entry here + one seed block in
 *    supabase/migrations. Nothing else in the tree touches per-sport code.
 */
enum class Sport(
    val id: String,
    val label: String,
    val tagline: String,
    val templatePrefix: String,
    val poolSize: Int,
) {
    FOOTBALL(
        id = "FOOTBALL", label = "FOOTBALL",
        tagline = "Engine, first touch, and the pass nobody saw.",
        templatePrefix = "FB", poolSize = 4,
    ),
    CRICKET(
        id = "CRICKET", label = "CRICKET",
        tagline = "Rotational power, throw accuracy, patience under lights.",
        templatePrefix = "CR", poolSize = 4,
    ),
    BASKETBALL(
        id = "BASKETBALL", label = "BASKETBALL",
        tagline = "Vertical, handle, and decisions at game speed.",
        templatePrefix = "BB", poolSize = 4,
    ),
    BADMINTON(
        id = "BADMINTON", label = "BADMINTON",
        tagline = "Explosive lungs, razor footwork, reading deception.",
        templatePrefix = "BD", poolSize = 4,
    ),
    ;

    companion object {
        /** Server value → sport. Unknown / future ids map to null = hidden. */
        fun fromId(id: String?): Sport? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The four development pillars. CONDITIONING + TECHNICAL are daily floor
 * quests (migration 022); MENTAL + RECOVERY slot into the existing weekly
 * rhythm (system quests / rest-day education) — they are announced in the
 * path plan, never faked as quests the server didn't issue.
 */
enum class SportPillar(val label: String, val questSource: String) {
    CONDITIONING("CONDITIONING", "SPORT_CONDITIONING"),
    TECHNICAL("TECHNICAL", "SPORT_TECHNICAL"),
    MENTAL("MENTAL", "SPORT_MENTAL"),
    RECOVERY("RECOVERY", "SPORT_RECOVERY"),
}

/** A server quest's source tag → pillar (unknown tags map to null = base protocol). */
fun sportPillarFor(questSource: String?): SportPillar? =
    SportPillar.entries.firstOrNull { it.questSource == questSource }

/**
 * Template rotation — MUST equal the SQL formula
 * `(epoch_day + sport ordinal) % pool_size` (see migration 022). Deterministic
 * worldwide, per-sport offset so different paths don't sync-cycle.
 */
fun sportRotationOffset(epochDay: Long, sport: Sport): Int =
    Math.floorMod(epochDay + sport.ordinal, sport.poolSize.toLong()).toInt()
