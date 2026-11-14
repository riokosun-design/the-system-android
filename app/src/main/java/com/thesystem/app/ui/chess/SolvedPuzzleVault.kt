package com.thesystem.app.ui.chess

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.thesystem.app.chess.Puzzle
import com.thesystem.app.chess.PuzzlePack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * vC55 §A4 — LIFETIME NEVER-REPEAT VAULT (local rail).
 *
 * The solved-puzzle blacklist lives in TWO places, exactly per spec:
 *   HERE        — DataStore preferences (instant, offline-first reads)
 *   SUPABASE    — user_solved_puzzles (mig 041; durable across devices)
 *
 * The director consumes a SNAPSHOT of this set at selection time; solves are
 * stamped local-first so a two-tap sprint can never re-serve the corpse.
 * Process-level cache keeps cold reads off the critical path (2GB covenant);
 * disk remains the authority whenever the cache is cold.
 */
private val Context.solvedPuzzleStore by preferencesDataStore(name = "solved_puzzle_vault")

object SolvedPuzzleVault {

    private val IDS = stringSetPreferencesKey("solved_ids")

    @Volatile
    private var cache: Set<String>? = null

    /** Local blacklist snapshot (disk on cold start, cache after). */
    suspend fun local(context: Context): Set<String> {
        cache?.let { return it }
        val app = context.applicationContext
        val got = withContext(Dispatchers.IO) {
            runCatching { app.solvedPuzzleStore.data.first()[IDS] }.getOrNull()
        } ?: emptySet()
        cache = got
        return got
    }

    /** Instant local stamp — the UI updates its StateFlow right after this. */
    suspend fun stamp(context: Context, id: String) {
        val app = context.applicationContext
        val next = (local(app) + id)
        cache = next
        withContext(Dispatchers.IO) {
            runCatching { app.solvedPuzzleStore.edit { it[IDS] = next } }
        }
    }

    /** Server merge: union of local + remote, persisted back — the missing
     *  half of the cross-device covenant (reinstalls heal from Supabase). */
    suspend fun mergeRemote(context: Context, remote: Set<String>): Set<String> {
        if (remote.isEmpty()) return local(context)
        val union = local(context) + remote
        if (union.size != (cache?.size ?: 0)) {
            cache = union
            val app = context.applicationContext
            withContext(Dispatchers.IO) {
                runCatching { app.solvedPuzzleStore.edit { it[IDS] = union } }
            }
        }
        return union
    }
}

/**
 * vC55 §A4 — MEGA-PACK CACHE. The 500+ asset bundle decodes ONCE per process
 * (strict pure-Kotlin reader in [PuzzlePack.decode]); every screen reads the
 * merged pack from here. Corrupt/absent asset → builtin 49 seed, never a
 * crash, never an empty arena.
 */
object PuzzlePackCache {

    @Volatile
    private var pack: List<Puzzle>? = null

    suspend fun load(context: Context): List<Puzzle> {
        pack?.let { return it }
        val app = context.applicationContext
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val raw = app.assets.open("chess_puzzles.json").bufferedReader().use { it.readText() }
                PuzzlePack.merged(PuzzlePack.decode(raw))
            }.getOrNull()
        } ?: PuzzlePack.ALL
        pack = loaded
        return loaded
    }
}
