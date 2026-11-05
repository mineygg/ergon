package com.mineygg.ergon.core

import android.content.Context
import com.mineygg.ergon.data.NewsItem
import com.mineygg.ergon.data.Store
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Which HoYoLAB events are important. The game of an event is known from the feed it comes from, so no words are
 * needed to tell the games apart. An event is important when its title or text names something worth having, and
 * none of the excluded words.
 *
 * Matching ignores case and only counts whole words, and a trailing "s" is allowed ("primogem" matches "Primogems").
 * "top up", "top-up" and "topup" count as the same word.
 */
object NewsWords {
    /** The premium currencies and passes of each game. Codes are not counted: Ergon redeems those on its own. */
    val rewards: Map<Game, List<String>> = mapOf(
        Game.GENSHIN to listOf("primogem", "primo", "genesis crystal", "intertwined fate", "acquaint fate", "welkin"),
        Game.HSR to listOf("stellar jade", "oneiric shard", "special pass", "star rail pass", "express supply pass"),
        Game.ZZZ to listOf("polychrome", "monochrome", "master tape", "inter-knot membership"),
        Game.HI3 to emptyList(),
        // S-Chips are Tears of Themis' premium currency. Stellin is the basic one, so it doesn't count.
        Game.TOT to listOf("s-chip")
    )

    /** Worth having in any game: web events, raffles, figures and hardware prizes. */
    val general: List<String> = listOf(
        "web event",
        "raffle",
        "figure",
        "laptop",
        "macbook",
        "ipad",
        "phone",
        "iphone",
        "smartphone",
        "playstation",
        "ps5",
        "xbox",
        "nintendo switch",
        "console"
    )

    /** An event with one of these anywhere in its title or text is never important, even with a reward word. */
    val excluded: List<String> = listOf(
        "top-up",
        "creator contest",
        "machine workshop",
        "travel mimo",
        "traveling mimo",
        "traveling miko",
        "wonderland", // Miliastra Wonderland
        "time album spotlight"
    )

    /** The same, but only in the title: these words also show up in the text of events that are fine. */
    val excludedInTitle: List<String> = listOf("share", "screenshot", "submission", "birthday", "fan art")

    /** The feed's own kinds of post that are never important: fan-art contests and Travel Mimo. */
    private val EXCLUDED_TYPES = setOf(1, 5)

    private fun regex(words: List<String>): Regex? =
        if (words.isEmpty()) {
            null
        } else {
            Regex(words.joinToString("|", prefix = "(?<![a-z0-9])(?:", postfix = ")s?(?![a-z0-9])") { Regex.escape(it) })
        }

    private val rewardPatterns: Map<Game, Regex?> = rewards.mapValues { (_, words) -> regex(words) }
    private val generalPattern = regex(general)
    private val excludedPattern = regex(excluded)
    private val excludedInTitlePattern = regex(excludedInTitle)

    private fun normalize(s: String): String = s.lowercase()
        .replace(Regex("[\u2010-\u2015\u00a0]"), "-")
        .replace("top up", "top-up")
        .replace("topup", "top-up")

    /** True when an event of [game] with this [type], [title] and [text] is worth showing. */
    fun isImportant(game: Game, type: Int, title: String, text: String): Boolean {
        if (type in EXCLUDED_TYPES) return false
        val all = normalize("$title $text")
        if (excludedPattern?.containsMatchIn(all) == true) return false
        if (excludedInTitlePattern?.containsMatchIn(normalize(title)) == true) return false
        return generalPattern?.containsMatchIn(all) == true || rewardPatterns[game]?.containsMatchIn(all) == true
    }
}

/** One entry of a game's event feed. */
data class FeedEvent(
    val id: String,
    val title: String,
    val text: String,
    val time: Long, // when it was posted, epoch seconds
    val end: Long, // when the event ends, epoch seconds, 0 = unknown
    val type: Int,
    val url: String
)

/** HoYoLAB's public event feed of a game. It needs no sign-in. */
object NewsApi {
    private const val URL = "https://bbs-api-os.hoyolab.com/community/community_contribution/wapi/event/list"
    private const val SITE = "https://www.hoyolab.com"

    /** What the site asks for per page. Ergon never reads more than this. */
    const val PAGE_SIZE = 15

    /** The feed (gids) of each game. */
    val FEEDS: Map<Game, Int> = mapOf(Game.GENSHIN to 2, Game.HSR to 6, Game.ZZZ to 8, Game.HI3 to 1, Game.TOT to 4)

    /** The newest page of [game]'s feed: the running events first, then the ended ones. */
    suspend fun latest(game: Game): Pair<ApiResult, List<FeedEvent>> {
        val gid = FEEDS.getValue(game)
        val r = HoyoApi.call("$URL?gids=$gid&page_size=$PAGE_SIZE&size=$PAGE_SIZE", cookie = "")
        return r to (r.data?.let { parse(it) } ?: emptyList())
    }

    fun parse(data: JSONObject): List<FeedEvent> {
        val arr = data.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<FeedEvent>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = str(o, "id")
            if (id.isBlank()) continue
            val title = str(o, "name").trim()
            val text = str(o, "desc").trim().take(MAX_TEXT)
            if (title.isEmpty() && text.isEmpty()) continue
            out.add(
                FeedEvent(
                    id = id,
                    title = title,
                    text = text,
                    time = long(o, "create_at"),
                    end = long(o, "end"),
                    type = o.optInt("type", 0),
                    url = articleUrl(str(o, "web_path"), str(o, "app_path"))
                )
            )
        }
        return out
    }

    private const val MAX_TEXT = 600

    /** JSON null counts as empty (org.json would turn it into the text "null"). Numbers and text both work. */
    private fun str(o: JSONObject, key: String): String = if (o.isNull(key)) "" else o.opt(key)?.toString().orEmpty()

    private fun long(o: JSONObject, key: String): Long = str(o, key).toLongOrNull() ?: 0L

    /** A link that opens in the HoYoLAB app when it is installed and in the browser otherwise. */
    private fun articleUrl(web: String, app: String): String = when {
        web.startsWith("http") -> web
        web.startsWith("/") -> SITE + web
        app.startsWith("hoyolab://articles/") -> "$SITE/article/" + app.removePrefix("hoyolab://articles/").substringBefore('?')
        else -> ""
    }
}

data class NewsRun(val ok: Boolean, val message: String, val added: Int)

/** The check for important HoYoLAB events. Runs every 6 hours, and when asked from the News tab. */
object News {
    /** Ids remembered per game to tell what is new. They are the newest 15, the whole page that is read. */
    private const val KEEP_SEEN = 15

    /** More new events than this in one check are only added to the tab, without a notification each. */
    private const val MAX_NOTIFY = 5

    /** The id an event is stored under: the feed's id of the game and the event's own id. */
    fun keyOf(game: Game, eventId: String): String = "${NewsApi.FEEDS.getValue(game)}:$eventId"

    /** The ids to remember: [ids] first (newest), then the older ones, at most 15 per game. */
    fun trimSeen(ids: List<String>): List<String> =
        ids.distinct().groupBy { it.substringBefore(':') }.values.flatMap { it.take(KEEP_SEEN) }

    /** The event as the tab keeps it, or null when it isn't important. */
    fun toItem(game: Game, e: FeedEvent): NewsItem? =
        if (NewsWords.isImportant(game, e.type, e.title, e.text)) {
            NewsItem(keyOf(game, e.id), game.id, e.title, e.text, e.time, e.end, e.url)
        } else {
            null
        }

    suspend fun check(ctx: Context, store: Store, notify: Boolean): NewsRun {
        val now = System.currentTimeMillis() / 1000
        store.pruneNews(now)
        val games = Game.entries.filter { it.id in store.settings.value.newsGames }
        if (games.isEmpty()) return NewsRun(true, "News is off for every game.", 0)

        val seen = store.newsSeen()
        val listedIds = ArrayList<String>()
        val important = ArrayList<NewsItem>()
        val unseen = ArrayList<NewsItem>()
        var failed = 0
        var why = ""
        for ((i, game) in games.withIndex()) {
            if (i > 0) delay(300)
            val (res, events) = NewsApi.latest(game)
            if (res.retcode != 0) {
                failed++
                why = if (res.network) "No connection." else "[${res.retcode}] ${res.message}"
                continue
            }
            // A game seen for the first time (turned on just now): what is listed fills the tab, but isn't new to the user.
            val first = seen.none { it.startsWith("${NewsApi.FEEDS.getValue(game)}:") }
            for (e in events) {
                val key = keyOf(game, e.id)
                listedIds.add(key)
                val item = toItem(game, e) ?: continue
                important.add(item)
                if (!first && key !in seen) unseen.add(item)
            }
        }
        if (failed == games.size) return NewsRun(false, "Couldn't load the event feeds. $why", 0)

        val known = store.news.value.map { it.id }.toSet()
        val added = important.count { it.id !in known }
        store.setNewsSeen(trimSeen(listedIds + seen))
        store.addNews(important, now)
        if (notify) unseen.take(MAX_NOTIFY).forEach { Notifier.news(ctx, it) }

        val message = when {
            added == 0 -> "No new important events for your games."
            added == 1 -> "Found 1 new important event for your games."
            else -> "Found $added new important events for your games."
        } + if (failed > 0) " ($failed of ${games.size} feeds failed.)" else ""
        return NewsRun(true, message, added)
    }
}
