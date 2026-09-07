package com.thesystem.app.data.repo

import com.thesystem.app.core.Geohash
import com.thesystem.app.data.model.*
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** Clans (Shadow Guilds), territory capture, realtime chat (clan + global DM), referrals, search. */
@Singleton
class SocialRepository @Inject constructor(private val supabase: SupabaseClient) {

    private val uid: String? get() = supabase.auth.currentSessionOrNull()?.user?.id

    // ── User directory (global DM search by @handle) ─────────────────────────
    suspend fun searchUsers(query: String): List<UserDto> = runCatching {
        supabase.from("users").select {
            filter { ilike("username", "${query.trim().removePrefix("@")}%") }
            limit(20)
        }.decodeList<UserDto>()
    }.getOrDefault(emptyList())

    suspend fun userById(id: String): UserDto? = runCatching {
        supabase.from("users").select { filter { eq("id", id) } }.decodeSingle<UserDto>()
    }.getOrNull()

    // ── Clans / Shadow Guilds ────────────────────────────────────────────────
    suspend fun clans(): List<ClanDto> = runCatching {
        supabase.from("clan_overview").select { order("treasury_vc", Order.DESCENDING) }.decodeList<ClanDto>()
    }.getOrDefault(emptyList())

    suspend fun myMembership(): ClanMemberDto? = uid?.let { id ->
        runCatching {
            supabase.from("clan_members_with_names").select { filter { eq("user_id", id) } }
                .decodeList<ClanMemberDto>().firstOrNull()
        }.getOrNull()
    }

    suspend fun clanMembers(clanId: String): List<ClanMemberDto> = runCatching {
        supabase.from("clan_members_with_names").select { filter { eq("clan_id", clanId) } }.decodeList<ClanMemberDto>()
    }.getOrDefault(emptyList())

    suspend fun clan(id: String): ClanDto? = runCatching {
        supabase.from("clan_overview").select { filter { eq("id", id) } }.decodeList<ClanDto>().firstOrNull()
    }.getOrNull()

    /** Creation gate enforced server-side: Level 30+ and 500 VC (debited atomically). */
    suspend fun createClan(name: String, tag: String): Result<Unit> = runCatching {
        supabase.postgrest.rpc("create_clan", buildJsonObject { put("p_name", name); put("p_tag", tag) }); Unit
    }

    suspend fun joinClan(clanId: String): Result<Unit> = runCatching {
        supabase.postgrest.rpc("join_clan", buildJsonObject { put("p_clan_id", clanId) }); Unit
    }

    suspend fun leaveClan(): Result<Unit> = runCatching {
        val id = uid ?: error("Not signed in")
        supabase.from("clan_members").delete { filter { eq("user_id", id) } }; Unit
    }

    // ── Territory (1KM geofenced zones) ──────────────────────────────────────
    suspend fun leaderboardsFor(zones: List<String>): List<ZoneLeaderboardDto> = runCatching {
        supabase.from("zone_leaderboard").select { filter { isIn("zone", zones) } }.decodeList<ZoneLeaderboardDto>()
    }.getOrDefault(emptyList())

    suspend fun clanTerritories(zones: List<String>): List<ClanTerritoryDto> = runCatching {
        supabase.from("clan_territories_with_tag").select { filter { isIn("zone", zones) } }
            .decodeList<ClanTerritoryDto>()
    }.getOrDefault(emptyList())

    /** Capture = a verified workout logged to this geohash cell. Server credits XP (+ guild tax if shielded). */
    suspend fun captureZone(zone: String, reps: Int, durationSec: Int): Result<Unit> = runCatching {
        supabase.postgrest.rpc("capture_zone", buildJsonObject {
            put("p_zone", zone); put("p_reps", reps); put("p_duration_sec", durationSec)
        }); Unit
    }

    /** Claim a dominated zone for your clan → activates the Guild Shield + 5% tax. */
    suspend fun claimTerritoryForClan(zone: String): Result<Unit> = runCatching {
        supabase.postgrest.rpc("claim_territory", buildJsonObject { put("p_zone", zone) }); Unit
    }

    fun zoneOf(lat: Double, lon: Double): String = Geohash.encode(lat, lon)

    // ── Realtime Chat (Clan + DM) ────────────────────────────────────────────
    private suspend fun loadClanHistory(clanId: String): List<MessageDto> = runCatching {
        supabase.from("messages_with_sender").select {
            filter { eq("clan_id", clanId) }; order("created_at", Order.DESCENDING); limit(100)
        }.decodeList<MessageDto>().asReversed()
    }.getOrDefault(emptyList())

    private suspend fun loadDmHistory(otherId: String): List<MessageDto> {
        val me = uid ?: return emptyList()
        return runCatching {
            supabase.from("messages_with_sender").select {
                filter {
                    eq("kind", "DM")
                    or {
                        and { eq("sender_id", me); eq("recipient_id", otherId) }
                        and { eq("sender_id", otherId); eq("recipient_id", me) }
                    }
                }
                order("created_at", Order.DESCENDING); limit(100)
            }.decodeList<MessageDto>().asReversed()
        }.getOrDefault(emptyList())
    }

    /**
     * Hot flow of clan messages. Uses Supabase Realtime postgres changes; RLS already restricts
     * rows to clan members, so the stream is private by construction.
     */
    fun clanMessagesFlow(clanId: String): Flow<List<MessageDto>> = callbackFlow {
        val channel = supabase.channel("clan-$clanId")
        val job = launch {
            channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "messages"
            }.collect { action ->
                val rowClan = when (action) {
                    is PostgresAction.Insert -> action.record["clan_id"]?.toString()
                    is PostgresAction.Update -> action.record["clan_id"]?.toString()
                    else -> null
                }
                if (rowClan?.contains(clanId) == true) trySend(loadClanHistory(clanId))
            }
        }
        channel.subscribe()
        trySend(loadClanHistory(clanId))
        awaitClose { job.cancel(); launch { supabase.realtime.runCatching { channel.unsubscribe() } } }
    }

    fun dmFlow(otherId: String): Flow<List<MessageDto>> = callbackFlow {
        val me = uid ?: run { close(); return@callbackFlow }
        val channel = supabase.channel("dm-${listOf(me, otherId).sorted().joinToString("-")}")
        val job = launch {
            channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "messages"
            }.collect {
                trySend(loadDmHistory(otherId)) // RLS filters to this conversation's participants only
            }
        }
        channel.subscribe()
        trySend(loadDmHistory(otherId))
        awaitClose { job.cancel(); launch { supabase.realtime.runCatching { channel.unsubscribe() } } }
    }

    suspend fun sendClanMessage(clanId: String, body: String): Result<Unit> = runCatching {
        supabase.from("messages").insert(
            MessageDto(senderId = uid ?: error("Not signed in"), clanId = clanId, kind = "CLAN", body = body)
        ); Unit
    }

    suspend fun sendDm(otherId: String, body: String): Result<Unit> = runCatching {
        supabase.from("messages").insert(
            MessageDto(senderId = uid ?: error("Not signed in"), recipientId = otherId, kind = "DM", body = body)
        ); Unit
    }

    /** Inbox: distinct DM partners ordered by last message. */
    suspend fun dmInbox(): List<Pair<UserDto, MessageDto>> {
        val me = uid ?: return emptyList()
        val recent = runCatching {
            supabase.from("messages_with_sender").select {
                filter {
                    eq("kind", "DM")
                    or { eq("sender_id", me); eq("recipient_id", me) }
                }
                order("created_at", Order.DESCENDING); limit(200)
            }.decodeList<MessageDto>()
        }.getOrDefault(emptyList())
        val partners = recent.groupBy { if (it.senderId == me) it.recipientId!! else it.senderId }
        return partners.mapNotNull { (partnerId, msgs) ->
            userById(partnerId)?.let { it to msgs.first() }
        }
    }

    // ── Referral Engine ──────────────────────────────────────────────────────
    suspend fun referralHallOfFame(): List<ReferralHallDto> = runCatching {
        supabase.from("referral_hall").select { limit(10) }.decodeList<ReferralHallDto>()
    }.getOrDefault(emptyList())

    // ── HUNTER FEED — dispatches, threads, mana, re-dispatch ═════════════════

    /** Timeline window: latest top-level dispatches + their threads. Scoped client-side. */
    suspend fun hunterFeed(): List<HunterPostDto> = runCatching {
        supabase.from("hunter_posts_feed").select {
            order("created_at", Order.DESCENDING); limit(150)
        }.decodeList<HunterPostDto>()
    }.getOrDefault(emptyList())

    /** My interaction state (for filled Arise/Transmit indicators). */
    suspend fun myFeedInteractions(): Map<String, Set<String>> {
        val id = uid ?: return emptyMap()
        return runCatching {
            supabase.from("hunter_interactions").select { filter { eq("user_id", id) } }
                .decodeList<HunterInteractionRow>()
                .groupBy({ it.postId }, { it.type }).mapValues { it.value.toSet() }
        }.getOrDefault(emptyMap())
    }

    /** Zero-latency timeline pushes. Emits Unit on any posts/interactions change. */
    fun hunterFeedFlow(): Flow<Unit> = callbackFlow {
        val posts = supabase.channel("hunter-feed-posts")
        val inter = supabase.channel("hunter-feed-inter")
        val j1 = launch {
            posts.postgresChangeFlow<PostgresAction>(schema = "public") { table = "hunter_posts" }
                .collect { trySend(Unit) }
        }
        val j2 = launch {
            inter.postgresChangeFlow<PostgresAction>(schema = "public") { table = "hunter_interactions" }
                .collect { trySend(Unit) }
        }
        posts.subscribe(); inter.subscribe()
        trySend(Unit) // initial paint
        awaitClose {
            j1.cancel(); j2.cancel()
            launch { supabase.realtime.runCatching { posts.unsubscribe(); inter.unsubscribe() } }
        }
    }

    suspend fun createPost(content: String, mediaUrl: String? = null, parentId: String? = null, quotedPostId: String? = null): Result<Unit> = runCatching {
        val id = uid ?: error("Not signed in")
        supabase.from("hunter_posts").insert(
            NewHunterPost(
                authorId = id,
                content = content.trim(),
                mediaUrl = mediaUrl?.trim()?.takeIf { it.isNotBlank() },
                parentId = parentId,
                quotedPostId = quotedPostId,
            )
        ); Unit
    }

    /** Arise: toggle a mana flare on a dispatch. */
    suspend fun toggleMana(postId: String, boost: Boolean): Result<Unit> = runCatching {
        val id = uid ?: error("Not signed in")
        if (boost) {
            supabase.from("hunter_interactions").upsert(HunterInteractionRow(postId, id, "mana_boost"))
        } else {
            supabase.from("hunter_interactions").delete {
                filter { eq("post_id", postId); eq("user_id", id); eq("type", "mana_boost") }
            }
        }; Unit
    }

    /** Transmit: quote / re-dispatch to own timeline + interaction marker. */
    suspend fun retransmit(post: HunterPostDto, comment: String?): Result<Unit> = runCatching {
        val id = uid ?: error("Not signed in")
        supabase.from("hunter_interactions").upsert(HunterInteractionRow(post.id, id, "transmit"))
        supabase.from("hunter_posts").insert(
            NewHunterPost(
                authorId = id,
                content = comment?.trim()?.takeIf { it.isNotBlank() } ?: post.content.take(500),
                quotedPostId = post.quotedPostId ?: post.id, // re-quote of a quote points at the origin
            )
        ); Unit
    }

    /** Battle Invite marker (the duel itself is created via ArenaRepository.challenge). */
    suspend fun markChallenge(postId: String): Result<Unit> = runCatching {
        val id = uid ?: error("Not signed in")
        supabase.from("hunter_interactions").upsert(HunterInteractionRow(postId, id, "challenge")); Unit
    }

    /** Own dispatches are erasable (RLS: author only). Threads cascade. */
    suspend fun deletePost(postId: String): Result<Unit> = runCatching {
        supabase.from("hunter_posts").delete { filter { eq("id", postId) } }; Unit
    }

    suspend fun myReferralCount(): Long {
        val me = uid ?: return 0
        return runCatching {
            supabase.from("referrals").select { filter { eq("referrer", me) } }
                .decodeList<Map<String, kotlinx.serialization.json.JsonElement>>().size.toLong()
        }.getOrDefault(0)
    }
}
