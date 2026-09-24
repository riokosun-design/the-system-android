package com.thesystem.app.ai

import android.content.Context
import com.thesystem.app.data.model.ProductDto
import com.thesystem.app.data.model.QuestDto
import com.thesystem.app.data.model.UserDto
import com.thesystem.app.data.model.VerifiedBestsDto
import com.thesystem.app.data.repo.CommerceRepository
import com.thesystem.app.data.repo.SystemRepository
import com.thesystem.app.data.repo.TrainingRepository
import com.thesystem.app.service.RecoveryTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CONTEXT ENGINE (spec §11) — targeted retrieval, never the whole database.
 * Every AI request declares its needs; each category is fetched once, capped,
 * and rendered as a compact k=v prompt block (§7: structured facts, no frames,
 * no raw rows, nothing the model has no business seeing).
 */
@Singleton
class ContextEngine @Inject constructor(
    private val system: SystemRepository,
    private val training: TrainingRepository,
    private val commerce: CommerceRepository,
    private val chess: com.thesystem.app.data.repo.ChessRepository,
    @ApplicationContext private val app: Context,
) {

    enum class Need { PROFILE, QUESTS, PERFORMANCE, TRAINING, MARKET, NOTES, ROUTINE, CHESS }

    data class Snapshot(
        val profile: UserDto? = null,
        val quests: List<QuestDto> = emptyList(),
        val bests: VerifiedBestsDto? = null,
        val primaryCourseTitle: String? = null,
        val activeSpecials: Int = 0,
        val products: List<ProductDto> = emptyList(),   // price-asc, capped
        val notes: List<String> = emptyList(),          // user-saved memory, capped 5
        val routineCountToday: Int = 0,
        val recoveryRemainingSec: Int = 0,
        val commitments: List<CommitmentBlock> = emptyList(), // §6 fixed life blocks
        val chess: com.thesystem.app.data.model.ChessProfileDto? = null, // MENTAL ASCENSION facts
    ) {
        val minor: Boolean get() = (profile?.age ?: 99) < 18
    }

    suspend fun snapshot(needs: Set<Need>, productCap: Int = 12): Snapshot = withContext(Dispatchers.IO) {
        val profileD = if (Need.PROFILE in needs) async { system.profile() } else null
        val questsD = if (Need.QUESTS in needs) async { system.dailyQuests() } else null
        val bestsD = if (Need.PERFORMANCE in needs) async { system.verifiedBests() } else null
        val coursesD = if (Need.TRAINING in needs) async { training.myCourses() } else null
        val catalogD = if (Need.TRAINING in needs) async { training.courses() } else null
        val productsD = if (Need.MARKET in needs) async { commerce.products() } else null
        val notesD = if (Need.NOTES in needs) async { system.aiNotes() } else null
        val routineD = if (Need.ROUTINE in needs) async { system.routineToday() } else null
        val commitD = if (Need.ROUTINE in needs) async { system.myCommitments() } else null
        val chessD = if (Need.CHESS in needs) async { chess.profile() } else null

        val mine = coursesD?.await().orEmpty()
        val catalog = catalogD?.await().orEmpty()
        Snapshot(
            profile = profileD?.await(),
            quests = questsD?.await().orEmpty(),
            bests = bestsD?.await(),
            primaryCourseTitle = catalog.firstOrNull { c ->
                c.type == "MUSCLE" && mine.any { it.courseId == c.id && it.status == "ACTIVE" }
            }?.title,
            activeSpecials = mine.count { it.status == "ACTIVE" } -
                if (catalog.any { c -> c.type == "MUSCLE" && mine.any { it.courseId == c.id && it.status == "ACTIVE" } }) 1 else 0,
            products = productsD?.await()?.sortedBy { it.priceInr }?.take(productCap).orEmpty(),
            notes = notesD?.await()?.map { it.note }?.take(5).orEmpty(),
            routineCountToday = routineD?.await()?.items?.size ?: 0,
            recoveryRemainingSec = RecoveryTracker.remainingSec(app),
            commitments = commitD?.await()?.let { dto ->
                runCatching {
                    AiJson.decodeFromJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(CommitmentBlock.serializer()),
                        dto.blocks,
                    )
                }.getOrNull()?.take(12)
            }.orEmpty(),
            chess = chessD?.await(),
        )
    }

    /** Compact prompt block — the ONLY user data a text model ever sees. */
    fun promptBlock(s: Snapshot, maxChars: Int = 900): String = buildString {
        s.profile?.let { p ->
            appendLine("hunter: level=${p.level} rank=${p.rank.title} xp=${p.xp} streak=${p.streakDays} missed=${p.missedDays}")
            appendLine("goal=${p.goal ?: "UNSET"} age=${p.age ?: "?"} act=${p.activityLevel ?: "?"} exp=${p.athleticExperience ?: "?"}")
        }
        s.bests?.let { b -> appendLine("verified_bests: push=${b.pushReps} squat=${b.squatReps} run_m=${b.runMeters} sessions=${b.sessions}") }
        val open = s.quests.filter { !it.isDone && !it.isDead }
        appendLine("quests_today: done=${s.quests.count { it.isDone }}/${s.quests.size} open=[${open.joinToString("|") { "${it.exerciseKind ?: "?"}:${it.progress}/${it.targetValue}${it.targetUnit.take(1)}" }}]")
        appendLine("training: primary=${s.primaryCourseTitle ?: "NONE"} specials=${s.activeSpecials} recovery_wait_s=${s.recoveryRemainingSec}")
        if (s.commitments.isNotEmpty()) appendLine("commitments: ${s.commitments.take(6).joinToString("|") { "${it.title.take(12)}@${it.start}" }}")
        s.chess?.let { c ->
            appendLine("mind: mental_rank=${c.mentalRank} lv=${c.mentalLevel} rating=${c.rating} w${c.wins}/d${c.draws}/l${c.losses} puzzles=${c.puzzlesSolved}/${c.puzzlesAttempted}")
            val weakest = listOf("tactics","focus","memory","calculation","adaptability","decision","composure")
                .map { it to c.stat(it) }.filter { it.second > 0 }.minByOrNull { it.second }
            weakest?.let { appendLine("mind_weakest: ${it.first}=${it.second}") }
        }
        if (s.notes.isNotEmpty()) appendLine("saved_notes: ${s.notes.joinToString(" · ")}")
        if (s.products.isNotEmpty()) appendLine("market: " + s.products.take(8).joinToString("|") { "${it.id.take(6)}:${it.name.take(18)}:₹${it.priceInr.toInt()}" })
    }.take(maxChars)
}
