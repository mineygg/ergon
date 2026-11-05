package com.mineygg.ergon.data

import com.mineygg.ergon.core.Account
import com.mineygg.ergon.core.Game
import com.mineygg.ergon.core.WorkshopGame
import org.json.JSONArray
import org.json.JSONObject

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** What auto-exchange does when the top-priority reward costs more points than we have. */
enum class ExchangeMode {
    /** Keep the points and wait until the top-priority reward can be afforded. */
    WAIT,

    /** Claim the first reward in the priority list that is affordable right now. */
    AFFORDABLE,

    /** Claim nothing, only spend the points on prize draws. */
    DRAW
}

/** Auto-exchange choices of one workshop game. */
data class ExchangePrefs(
    val auto: Boolean = false,
    val mode: ExchangeMode = ExchangeMode.WAIT,
    // Award ids, most wanted first.
    val priority: List<Int> = emptyList(),
    // Also draw when no reward in the priority list is left to claim (not used in DRAW mode, which always draws).
    val drawWhenIdle: Boolean = false
)

data class Settings(
    val theme: ThemeMode = ThemeMode.DARK,
    val autoRun: Boolean = true,
    // What Ergon does per game (game ids). Set from each game's Configure screen.
    val checkinGames: Set<String> = Game.entries.map { it.id }.toSet(),
    val redeemGames: Set<String> = Game.entries.filter { it.redeem != null }.map { it.id }.toSet(),
    val workshopGames: Set<String> = WorkshopGame.entries.map { it.id }.toSet(),
    // Games whose important HoYoLAB posts are shown in the News tab (off by default).
    val newsGames: Set<String> = emptySet(),
    val exchange: Map<String, ExchangePrefs> = emptyMap(), // game id -> prefs (missing = defaults)
    // Regions Ergon works with, per game id (missing = every account the profile lists).
    val regions: Map<String, Set<String>> = emptyMap(),
    val notify: Boolean = true,
    val checkUpdates: Boolean = true
) {
    /**
     * The accounts of [g] that are automated: the ones in the picked regions. Falls back to every known account when
     * nothing was picked or the picked regions are gone (an account removed), so an account is never silently left out
     * for good.
     */
    fun accountsToUse(g: Game, known: List<Account>): List<Account> {
        val picked = regions[g.id] ?: return known
        return known.filter { it.region in picked }.ifEmpty { known }
    }

    /** Regions of [g] that are currently automated, out of the regions the profile lists. */
    fun pickedRegions(g: Game, known: List<Account>): Set<String> = accountsToUse(g, known).map { it.region }.toSet()

    /** Copy with [region] of [g] switched on or off. At least one region stays on, so switching the last one off does nothing. */
    fun withRegion(g: Game, known: List<Account>, region: String, on: Boolean): Settings {
        if (known.none { it.region == region }) return this
        val current = pickedRegions(g, known)
        val next = if (on) current + region else current - region
        if (next.isEmpty()) return this
        return copy(regions = regions + (g.id to next))
    }

    fun exchangePrefs(g: WorkshopGame): ExchangePrefs = exchange[g.id] ?: ExchangePrefs()

    /** Copy with [g]'s exchange prefs changed. */
    fun withExchange(g: WorkshopGame, change: (ExchangePrefs) -> ExchangePrefs): Settings =
        copy(exchange = exchange + (g.id to change(exchangePrefs(g))))
}

data class Credentials(
    val ltoken: String = "",
    val ltuid: String = "",
    val cookieToken: String = "",
    val hoyolabExpired: Boolean = false,
    val loginExpired: Boolean = false
) {
    val hasHoyolab: Boolean get() = ltoken.isNotEmpty() && ltuid.isNotEmpty()
    val hasLogin: Boolean get() = ltuid.isNotEmpty() && cookieToken.isNotEmpty()

    fun hoyolabCookie() = "ltoken_v2=$ltoken; ltuid_v2=$ltuid;"
    fun loginCookie() = "account_id_v2=$ltuid; cookie_token_v2=$cookieToken;"
}

data class RedeemEntry(
    val game: String,
    val code: String,
    val rewards: String,
    val outcome: String, // CLAIMED | SKIPPED | FAILED
    val message: String
)

data class RunReport(
    val timeMillis: Long,
    val trigger: String,
    val checkins: Map<String, String>, // game id -> CheckinStatus name
    val claimed: Int,
    val skipped: Int,
    val failed: Int,
    val entries: List<RedeemEntry>
) {
    fun toJson(): String = JSONObject().apply {
        put("time", timeMillis)
        put("trigger", trigger)
        put("claimed", claimed)
        put("skipped", skipped)
        put("failed", failed)
        put("checkins", JSONObject(checkins))
        put(
            "entries",
            JSONArray().apply {
                entries.forEach { e ->
                    put(
                        JSONObject()
                            .put("game", e.game).put("code", e.code).put("rewards", e.rewards)
                            .put("outcome", e.outcome).put("message", e.message)
                    )
                }
            }
        )
    }.toString()

    companion object {
        fun fromJson(s: String?): RunReport? {
            if (s.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(s)
                val c = o.getJSONObject("checkins")
                val map = LinkedHashMap<String, String>()
                c.keys().forEach { k -> map[k] = c.getString(k) }
                val arr = o.getJSONArray("entries")
                val list = List(arr.length()) { i ->
                    val e = arr.getJSONObject(i)
                    RedeemEntry(
                        e.getString("game"),
                        e.getString("code"),
                        e.optString("rewards"),
                        e.getString("outcome"),
                        e.optString("message")
                    )
                }
                RunReport(
                    o.getLong("time"),
                    o.optString("trigger", "manual"),
                    map,
                    o.getInt("claimed"),
                    o.getInt("skipped"),
                    o.getInt("failed"),
                    list
                )
            }.getOrNull()
        }
    }
}

enum class ItemState { OK, INFO, WARN, ERROR }

data class RecordItem(
    val gameId: String?,
    val title: String,
    val detail: String,
    val state: ItemState,
    val mono: Boolean = false
)

/** One finished run, shown as a card in the Activity tab. */
data class RunRecord(
    val timeMillis: Long,
    val task: String, // CHECKIN | REDEEM
    val trigger: String, // manual | scheduled
    val summary: String,
    val ok: Boolean,
    val items: List<RecordItem>
) {
    fun toJson(): JSONObject = JSONObject()
        .put("time", timeMillis).put("task", task).put("trigger", trigger)
        .put("summary", summary).put("ok", ok)
        .put(
            "items",
            JSONArray().apply {
                items.forEach {
                    put(
                        JSONObject().put("game", it.gameId ?: "").put("title", it.title)
                            .put("detail", it.detail).put("state", it.state.name).put("mono", it.mono)
                    )
                }
            }
        )

    companion object {
        fun fromJson(o: JSONObject): RunRecord {
            val arr = o.getJSONArray("items")
            val list = List(arr.length()) { i ->
                val e = arr.getJSONObject(i)
                RecordItem(
                    e.optString("game").ifEmpty { null },
                    e.getString("title"),
                    e.optString("detail"),
                    runCatching { ItemState.valueOf(e.getString("state")) }.getOrDefault(ItemState.INFO),
                    e.optBoolean("mono", false)
                )
            }
            return RunRecord(
                o.getLong("time"),
                o.getString("task"),
                o.optString("trigger", "manual"),
                o.optString("summary"),
                o.optBoolean("ok", true),
                list
            )
        }

        fun listToJson(list: List<RunRecord>): String =
            JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(s: String?): List<RunRecord> {
            if (s.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(s)
                List(arr.length()) { fromJson(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
    }
}

/** Latest known state of the Machine Workshop web event, shown on the Home screen. */
data class WorkshopInfo(
    val versionId: Int,
    val points: Int,
    val endTime: Long, // epoch seconds
    val lottery: Boolean,
    val exchange: Boolean,
    val updatedAt: Long
) {
    fun toJson(): String = JSONObject()
        .put("v", versionId).put("p", points).put("end", endTime)
        .put("lottery", lottery).put("exchange", exchange).put("at", updatedAt).toString()

    companion object {
        fun fromJson(s: String?): WorkshopInfo? {
            if (s.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(s)
                WorkshopInfo(o.getInt("v"), o.getInt("p"), o.getLong("end"), o.getBoolean("lottery"), o.getBoolean("exchange"), o.getLong("at"))
            }.getOrNull()
        }
    }
}

/** An important HoYoLAB event (a giveaway, a web event, a prize), shown in the News tab. */
data class NewsItem(
    val id: String, // feed id and event id: "2:47163124"
    val game: String, // game id
    val title: String,
    val text: String,
    val time: Long, // when it was posted, epoch seconds, 0 = unknown
    val end: Long, // when the event ends, epoch seconds, 0 = unknown
    // empty = no link
    val url: String
) {
    /** True while the event hasn't ended. With no end time known it counts as ended. */
    fun isLive(nowSeconds: Long): Boolean = end > nowSeconds

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("game", game).put("title", title).put("text", text)
        .put("time", time).put("end", end).put("url", url)

    companion object {
        /** An event stays this long after it ended. One that is still running stays as long as it runs. */
        const val KEEP_SECONDS = 30L * 24 * 60 * 60

        /** All games together never keep more than this. */
        const val KEEP_MAX = 100

        /**
         * The list as it is kept: the running events and the ones that ended within the last 30 days, without
         * doubles (the first copy of an id wins). Running ones come first, newest first, then the ended ones.
         * An event whose end isn't known counts from when it was posted.
         */
        fun kept(list: List<NewsItem>, nowSeconds: Long): List<NewsItem> =
            list.distinctBy { it.id }
                .filter { nowSeconds - (if (it.end > 0) it.end else it.time) <= KEEP_SECONDS }
                .sortedWith(compareByDescending<NewsItem> { it.isLive(nowSeconds) }.thenByDescending { it.time })
                .take(KEEP_MAX)

        fun fromJson(o: JSONObject) = NewsItem(
            o.getString("id"),
            o.getString("game"),
            o.optString("title"),
            o.optString("text"),
            o.optLong("time", 0L),
            o.optLong("end", 0L),
            o.optString("url")
        )

        fun listToJson(list: List<NewsItem>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(s: String?): List<NewsItem> {
            if (s.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(s)
                List(arr.length()) { fromJson(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
    }
}
