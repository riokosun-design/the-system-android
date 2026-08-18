package com.thesystem.app

import com.thesystem.app.core.Geohash
import com.thesystem.app.core.SystemMath
import com.thesystem.app.core.SystemMath.CombatStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the rule engine so app math can never drift from spec. */
class SystemMathTest {

    @Test fun `xp curve is 100 x N^1-8 cumulative`() {
        assertEquals(0L, SystemMath.xpRequiredForLevel(1))
        assertEquals(100L, SystemMath.xpRequiredForLevel(2))
        assertEquals(Math.round(100 * Math.pow(99.0, 1.8)), SystemMath.xpRequiredForLevel(100))
    }

    @Test fun `level inversion matches requirement curve`() {
        for (lvl in 1..100) {
            val req = SystemMath.xpRequiredForLevel(lvl)
            assertEquals(lvl, SystemMath.levelForXp(req))
            assertEquals(lvl, SystemMath.levelForXp(req + 1))
        }
        assertEquals(100, SystemMath.levelForXp(Long.MAX_VALUE / 4))
    }

    @Test fun `ranks follow level with penalty override`() {
        assertEquals(SystemMath.HunterRank.AVERAGE, SystemMath.rankFor(1, 0))
        assertEquals(SystemMath.HunterRank.ELITE, SystemMath.rankFor(25, 0))
        assertEquals(SystemMath.HunterRank.S_RANK, SystemMath.rankFor(50, 0))
        assertEquals(SystemMath.HunterRank.MASTERPIECE, SystemMath.rankFor(80, 0))
        assertEquals(SystemMath.HunterRank.GARBAGE, SystemMath.rankFor(60, 3))
        assertEquals(SystemMath.HunterRank.LOSER, SystemMath.rankFor(60, 5))
    }

    @Test fun `decay escalates 3 percent per missed day`() {
        assertEquals(30L, SystemMath.xpDecayForMissedDay(1000, 0)) // day 1: 3%
        assertEquals(60L, SystemMath.xpDecayForMissedDay(1000, 1)) // day 2: 6%
        assertEquals(0L, SystemMath.xpDecayForMissedDay(0, 5))
    }

    @Test fun `lazy form 5 loses to hardcore form 1`() {
        val lazyHardWork = SystemMath.hardWorkMultiplier(streakDays = 0, weeklySessions = 0, missedDays = 6)
        val hardcore = SystemMath.hardWorkMultiplier(streakDays = 20, weeklySessions = 10, missedDays = 0)
        val lazyForm5 = SystemMath.formPower(totalXp = 500, formIndex = 5, style = CombatStyle.BALANCED, hardWork = lazyHardWork)
        val hardcoreForm1 = SystemMath.formPower(totalXp = 12_000, formIndex = 1, style = CombatStyle.BALANCED, hardWork = hardcore)
        assertTrue("hardcore Form1 ($hardcoreForm1) must beat lazy Form5 ($lazyForm5)", hardcoreForm1 > lazyForm5)
    }

    @Test fun `hard work multiplier clamps to 0-5..3-0`() {
        assertEquals(0.5, SystemMath.hardWorkMultiplier(0, 0, 50), 0.0001)
        assertTrue(SystemMath.hardWorkMultiplier(20, 10, 0) <= 3.0)
    }

    @Test fun `prediction payout is bet over winning pool times total times 0-85`() {
        // Bet 100 into a 400-side pool, 900 total → (100/500)×(1000×0.85)=170 after your own bet merges
        val payout = SystemMath.expectedPayout(betVc = 100, winningSidePoolVc = 500, totalPoolVc = 1000)
        assertEquals(170L, payout)
    }

    @Test fun `geohash encodes precision 6 cells with valid neighbors`() {
        val z = Geohash.encode(22.5726, 88.3639) // Kolkata
        assertEquals(6, z.length)
        val (lat, lon) = Geohash.center(z)
        assertTrue(Math.abs(lat - 22.5726) < 0.02)
        assertTrue(Math.abs(lon - 88.3639) < 0.02)
        assertEquals(9, Geohash.gridAround(z).size)
    }
}
