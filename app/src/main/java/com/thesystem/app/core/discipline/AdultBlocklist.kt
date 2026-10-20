package com.thesystem.app.core.discipline

/**
 * §6 PORN & ADULT CONTENT FILTER — pure, offline, deterministic.
 *
 * Three layers of detection, all local (zero network, zero latency on the
 * accessibility thread):
 *   1. Adult TLD suffixes (.xxx / .porn / .adult / .sex …)
 *   2. Known adult domains (curated; subdomains included via suffix match)
 *   3. Explicit keyword tokens + phrases scanned across the visible URL /
 *      address-bar text (covers search-engine queries, where the domain is
 *      innocent but the hunt is not).
 *
 * Everything is compared lowercase. Tokens are matched on non-alphanumeric
 * boundaries so "class" never trips "ass" — but this is a discipline engine,
 * so when in doubt it errs on the side of the block.
 */
object AdultBlocklist {

    // ── 1. Adult TLDs ───────────────────────────────────────────────────────
    private val ADULT_TLDS = listOf(
        ".xxx", ".porn", ".adult", ".sex", ".sexy",
    )

    // ── 2. Known adult domains (suffix match covers every subdomain) ────────
    private val BLOCKED_DOMAINS = setOf(
        "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "redtube.com",
        "youporn.com", "tube8.com", "spankbang.com", "eporner.com", "tnaflix.com",
        "porn.com", "porntrex.com", "hqporner.com", "beeg.com", "drtuber.com",
        "thumbzilla.com", "pornmd.com", "tubegalore.com", "porndig.com", "xtube.com",
        "sunporno.com", "vporn.com", "txxx.com", "upornia.com", "hclips.com",
        "voyeurhit.com", "pornone.com", "porn300.com", "fapster.xxx", "pornPics.com".lowercase(),
        "imagefap.com", "motherless.com", "4porn.com", "ixxx.com", "fuq.com",
        "brazzers.com", "bangbros.com", "realitykings.com", "naughtyamerica.com",
        "mofos.com", "fakehub.com", "teamskeet.com", "evilangel.com", "vixen.com",
        "tushy.com", "blacked.com", "deeper.com", "kink.com", "brazzersnetwork.com",
        "onlyfans.com", "fansly.com", "manyvids.com", "justfor.fans",
        "chaturbate.com", "stripchat.com", "bongacams.com", "livejasmin.com",
        "cam4.com", "camsoda.com", "myfreecams.com", "flirt4free.com", "imlive.com",
        "streamate.com", "jerkmate.com", "camplace.com", "xlovecam.com",
        "nhentai.net", "hanime.tv", "hentaihaven.xxx", "rule34.xxx", "rule34video.com",
        "e-hentai.org", "exhentai.org", "gelbooru.com", "danbooru.donmai.us",
        "multporn.net", "luscious.net", "pururin.to", "tsumino.com", "hitomi.la",
        "e621.net", "hypnohub.net", "konachan.com", "yande.re",
        "literotica.com", "sexstories.com", "fapello.com", "dirtyroulette.com",
        "coomer.party", "kemono.party", "simpcity.su", "faponic.com",
        "erome.com", "camwhores.tv", "rekord.cc", "influencersgonewild.com",
    )

    // ── 3a. Single-token keywords (matched on token boundaries) ─────────────
    private val KEYWORD_TOKENS = setOf(
        "porn", "porno", "pornography", "xnxx", "xvideos", "xhamster", "xxx",
        "hentai", "rule34", "r34", "ecchi",
        "onlyfans", "chaturbate", "stripchat", "bongacams", "livejasmin",
        "nsfw", "fap", "fapping", "fapper",
        "nude", "nudes", "nudity", "topless",
        "milf", "gilf", "cumshot", "creampie", "bukkake", "gangbang",
        "blowjob", "handjob", "footjob", "deepthroat",
        "dildo", "vibrator", "fleshlight", "buttplug",
        "camgirl", "camboy", "webcamsex", "sexchat", "sexting",
        "boobs", "tits", "boob", "titjob", "pussy", "vagina", "clitoris",
        "penis", "dick", "cock", "cum", "jizz", "orgasm", "masturbate", "masturbation",
        "bdsm", "bondage", "fetish",
    )

    // ── 3b. Multi-word phrases (substring match on the flattened text) ──────
    private val KEYWORD_PHRASES = listOf(
        "porn video", "sex video", "sex videos", "sexy video", "sex movie",
        "hot video", "adult video", "adult content", "adult site",
        "blue film", "blue picture", "adult film", "x video", "x videos",
        "free porn", "watch porn", "hd porn", "live sex", "phone sex",
        "nude pics", "nude photos", "naked girl", "naked girls", "naked women",
        "desi mms", "desi sex", "bhabhi", "aunty sex", "mallu",
        "18+ videos", "18 plus", "xnxx video", "sex tape", "leaked nudes",
        "cam show", "private show", "strip tease", "striptease",
        "fuck video", "fucking video", "hardcore video",
    )

    /** Normalize a raw address-bar / title blob: lowercase + URL-decode light. */
    private fun normalize(raw: String): String {
        var t = raw.lowercase().trim()
        // light percent-decode so "%70orn" can't hide
        t = t.replace("%70", "p").replace("%73", "s").replace("%65", "e")
            .replace("%78", "x").replace("%61", "a").replace("%69", "i")
            .replace("%6f", "o").replace("%75", "u").replace("%6e", "n")
        return t
    }

    /** Pull the host out of a url-ish string ("https://a.b.c/x?q=1" → "a.b.c"). */
    fun extractHost(text: String): String {
        var t = normalize(text)
        val schemeIdx = t.indexOf("://")
        if (schemeIdx >= 0) t = t.substring(schemeIdx + 3)
        // strip credentials
        val at = t.indexOf('@')
        if (at >= 0 && at < t.indexOf('/').let { if (it < 0) Int.MAX_VALUE else it }) t = t.substring(at + 1)
        return t.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
    }

    fun isAdultUrl(raw: String): Boolean {
        val host = extractHost(raw)
        if (host.isNotBlank()) {
            if (ADULT_TLDS.any { host.endsWith(it) }) return true
            if (BLOCKED_DOMAINS.any { host == it || host.endsWith(".$it") }) return true
        }
        val flat = " " + normalize(raw).replace(Regex("[^a-z0-9]+"), " ") + " "
        if (KEYWORD_TOKENS.any { flat.contains(" $it ") }) return true
        val collapsed = flat.replace(Regex("\\s+"), " ")
        return KEYWORD_PHRASES.any { collapsed.contains(it) }
    }

    /** For search-engine intercepts: is this address bar text an adult hunt? */
    fun isAdultQuery(raw: String): Boolean = isAdultUrl(raw)
}
