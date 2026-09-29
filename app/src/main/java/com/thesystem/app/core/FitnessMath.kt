package com.thesystem.app.core

import kotlin.math.roundToInt

/**
 * FITNESS MATH (AI master prompt §1/§2) — deterministic BODY math.
 *
 * The AI intelligence layer is forbidden from deriving these numbers itself:
 * app logic computes them here; the AI only READS them from the context
 * snapshot. Nothing here writes state, touches XP/Rank, or pretends to be a
 * measurement. Every output is an ESTIMATE and labelled as such — the users
 * table carries no sex column, so BMR uses the sex-neutral midpoint of the
 * Mifflin–St Jeor equations (male adjectiveTerm +5 / female −161 → midpoint
 * −78), the standard approximation when sex is unknown.
 */
object FitnessMath {

    // ── Activity multipliers — mirror the vessel intake enum (onboarding). ──
    // SEDENTARY / STEADY / RELENTLESS are the only values the app ever writes.
    fun activityFactor(activityLevel: String?): Double = when (activityLevel?.uppercase()) {
        "SEDENTARY" -> 1.35          // resting vessel + light daily movement
        "STEADY" -> 1.50             // trains sometimes
        "RELENTLESS" -> 1.70         // near-daily grind
        else -> 1.40                 // unknown → assuming nothing extreme
    }

    /** Body-mass index; null when the profile lacks height/weight. */
    fun bmi(weightKg: Double?, heightCm: Double?): Double? {
        if (weightKg == null || heightCm == null || heightCm < 100.0 || weightKg < 25.0) return null
        val m = heightCm / 100.0
        return (weightKg / (m * m) * 10.0).roundToInt() / 10.0
    }

    /** WHO class labels — plain bands, no diagnosis language (§8). */
    fun bmiClass(bmi: Double): String = when {
        bmi < 18.5 -> "UNDER"
        bmi < 25.0 -> "HEALTHY"
        bmi < 30.0 -> "OVER"
        else -> "OBESE"
    }

    /** Healthy-weight range in kg for a height (BMI 18.5–24.9); null if height missing. */
    fun healthyWeightRange(heightCm: Double?): Pair<Double, Double>? {
        if (heightCm == null || heightCm < 100.0) return null
        val m = heightCm / 100.0
        return (18.5 * m * m).let { lo ->
            (24.9 * m * m).let { hi ->
                (lo * 10.0).roundToInt() / 10.0 to (hi * 10.0).roundToInt() / 10.0
            }
        }
    }

    /**
     * Resting metabolism, kcal/day — sex-neutral Mifflin–St Jeor midpoint.
     * Estimate marker lives in [ESTIMATE_NOTE]; UI/AI must carry it.
     */
    fun bmrEstimate(weightKg: Double?, heightCm: Double?, age: Int?): Int? {
        if (weightKg == null || heightCm == null || age == null || age < 10) return null
        return (10.0 * weightKg + 6.25 * heightCm - 5.0 * age - 78.0).roundToInt()
            .coerceAtLeast(900)
    }

    /** Total daily energy expenditure, kcal/day. */
    fun tdee(bmr: Int?, activityLevel: String?): Int? =
        bmr?.let { (it * activityFactor(activityLevel)).roundToInt() }

    /**
     * Goal-adjusted daily target, kcal/day. SAFETY CLAMPS (§4): deficits cap
     * at −400 kcal and floored (adult 1600 · minor 1800); minors NEVER get a
     * deficit below their floor regardless of goal text.
     */
    fun calorieTarget(tdee: Int?, goal: String?, minor: Boolean, floor: Int): Int? {
        tdee ?: return null
        val g = goal?.uppercase().orEmpty()
        val minFloor = if (minor) maxOf(floor, 1800) else floor
        val target = when {
            g.contains("BULK") || g.contains("MASS") || g.contains("GAIN") || g.contains("MUSCLE") -> tdee + 300
            g.contains("CUT") || g.contains("FAT") || g.contains("LOSE") || g.contains("SHRED") ||
                g.contains("DROP") -> (tdee - 400).coerceAtLeast(minFloor)
            else -> tdee
        }
        return target.coerceIn(minFloor, 4200)
    }

    /** Daily protein target in grams (1.5–1.9 g/kg by goal; standard ranges only). */
    fun proteinGrams(weightKg: Double?, goal: String?): Int? {
        weightKg ?: return null
        val g = goal?.uppercase().orEmpty()
        val perKg = when {
            g.contains("BULK") || g.contains("MASS") || g.contains("GAIN") || g.contains("MUSCLE") -> 1.8
            g.contains("CUT") || g.contains("FAT") || g.contains("LOSE") || g.contains("SHRED") -> 1.9
            else -> 1.5
        }
        return (weightKg * perKg).roundToInt().coerceIn(40, 260)
    }

    /** Daily water target in ml — 35 ml per kg, clamped to sane bottles. */
    fun waterMl(weightKg: Double?): Int? =
        weightKg?.let { (it * 35.0).roundToInt().coerceIn(1500, 4500) }

    const val ESTIMATE_NOTE =
        "BMR/TDEE are sex-neutral estimates (profile carries no sex field) — use them as planning guides, not lab values."
}
