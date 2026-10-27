package com.mineygg.ergon.data

import com.mineygg.ergon.core.Game
import org.json.JSONArray
import org.json.JSONObject

enum class ThemeMode { SYSTEM, DARK, LIGHT }

data class Settings(
    val theme: ThemeMode = ThemeMode.DARK,
    val autoRun: Boolean = true,
    val enabledGames: Set<String> = Game.entries.map { it.id }.toSet(),
    val checkin: Boolean = true,
    val autoRedeem: Boolean = true,
    val workshop: Boolean = true,
    val notify: Boolean = true,
    val checkUpdates: Boolean = true
)

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
