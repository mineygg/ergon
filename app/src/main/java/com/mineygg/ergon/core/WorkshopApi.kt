package com.mineygg.ergon.core

import com.mineygg.ergon.data.Credentials
import org.json.JSONObject

/**
 * One HoYoLAB "travel" web event. Genshin's Machine Workshop uses the older API ([legacy]); Star Rail and
 * Zenless share the newer Traveling Mimo API. They are all handled by the same code, only the requests differ.
 */
enum class WorkshopGame(
    val game: Game,
    val hoyoGameId: Int,
    val eventName: String,
    val shortName: String,
    val legacy: Boolean,
    /** task_type values of in-game missions (reset 04:00 server time). Everything else is a community mission (00:00 UTC+8). */
    val gameTaskTypes: Set<Int>
) {
    GENSHIN(Game.GENSHIN, 2, "Machine Workshop", "Genshin", true, setOf(6)),
    HSR(Game.HSR, 6, "Traveling Mimo", "Star Rail", false, setOf(8)),
    ZZZ(Game.ZZZ, 8, "Traveling Mimo", "Zenless", false, setOf(12, 16));

    val id: String get() = game.id

    /** How the event is named in logs and notifications. */
    val label: String get() = if (legacy) eventName else "$eventName ($shortName)"

    /** RunRecord.task value. Genshin keeps the original name so older history entries still match. */
    val recordTask: String get() = if (legacy) "WORKSHOP" else "MIMO_$name"

    /** The prize draw endpoints were captured for Traveling Mimo only. */
    val canDraw: Boolean get() = !legacy

    fun isGameTask(taskType: Int): Boolean = taskType in gameTaskTypes
}

data class WorkshopAct(
    val game: WorkshopGame,
    val gameId: Int,
    val versionId: Int,
    val points: Int,
    val start: Long, // epoch seconds
    val end: Long,
    val lottery: Boolean,
    val exchange: Boolean,
    val bound: Boolean
)

data class WorkshopTask(
    val id: Int,
    val name: String,
    val timeType: Int, // 0 once, 1 daily, 2 weekly
    val points: Int,
    val progress: Int,
    val total: Int,
    val status: Int,
    val type: Int, // 101 read, 102 watch, 6 in-game, 202 top-up, 1 open a page
    val readSeconds: Int
) {
    companion object {
        /** task_list status: 3 = claimed (points already collected). 2 is not understood yet. */
        const val STATUS_CLAIMED = 3
    }
}

/** One reward in the event's exchange shop (exchange_list). */
data class ExchangeAward(
    val id: Int,
    val status: Int,
    val name: String,
    val cost: Int,
    val stock: Int,
    val nextRefresh: Long, // epoch seconds, 0 = not on a timer
    val expireDays: Int
) {
    /** Can be claimed right now: available and not sitting on a refresh timer. */
    val claimable: Boolean get() = status == STATUS_AVAILABLE && nextRefresh == 0L

    /** Timed reward that is on cooldown. An alarm is set for its refresh so it can be claimed right away. */
    val timed: Boolean get() = nextRefresh != 0L

    companion object {
        /** exchange_list status: 1 = can be claimed, 3 = already claimed, 4 = out of stock. 2 is not understood (not claimable). */
        const val STATUS_AVAILABLE = 1
        const val STATUS_CLAIMED = 3
        const val STATUS_SOLD_OUT = 4
    }
}

/** The prize draw: [point] you have, [cost] per draw, [count] draws done today out of the daily [limit]. */
data class LotteryInfo(val point: Int, val cost: Int, val count: Int, val limit: Int) {
    /** How many draws can be made right now. */
    val affordable: Int get() = if (cost <= 0) 0 else minOf(point / cost, (limit - count).coerceAtLeast(0))
}

/** Result of one draw. [code] is only set for prizes delivered as an in-game redeem code. */
data class DrawOutcome(val result: ApiResult, val name: String, val code: String)

/** Result of a successful exchange. [code] is only set for rewards delivered as an in-game redeem code. */
data class ExchangeOutcome(val result: ApiResult, val code: String)

/**
 * "Hilichurls' Machine Workshop" (Genshin) and "Traveling Mimo" (Star Rail, Zenless), the HoYoLAB web events.
 * Endpoints as captured from the event pages.
 */
object WorkshopApi {
    private const val ACT = "https://sg-act-public-api.hoyolab.com/event/e2023mimotravel/qiuqiu"
    private const val PUBLIC = "https://sg-public-api.hoyolab.com/event/e2023mimotravel/qiuqiu"
    private const val MIMO = "https://sg-public-api.hoyolab.com/event/e2023mimotravel"

    /** Draws per game per day. The server reports its own limit (limit_count); this is only used if it doesn't. */
    private const val DEFAULT_DRAW_LIMIT = 5

    /** The server tells us the current event version (version_id), so nothing is hard-coded. */
    suspend fun index(cr: Credentials, wg: WorkshopGame): Pair<ApiResult, WorkshopAct?> {
        val legacy = wg.legacy
        val r = HoyoApi.call(if (legacy) "$ACT/index?lang=en-us" else "$MIMO/index?lang=en-us", cr.hoyolabCookie())
        val data = r.data
        if (r.retcode != 0 || data == null) return r to null
        val arr = data.optJSONArray(if (legacy) "act_list" else "list") ?: return r to null

        var best: WorkshopAct? = null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            // Genshin nests the event under act_info, the Traveling Mimo list is flat.
            val info = (if (legacy) o.optJSONObject("act_info") else o) ?: continue
            if (info.optInt("game_id") != wg.hoyoGameId) continue
            val act = WorkshopAct(
                game = wg,
                gameId = wg.hoyoGameId,
                versionId = info.optInt("version_id"),
                points = if (legacy) o.optInt("point", info.optInt("point")) else info.optInt("point"),
                start = info.optString("start_time").toLongOrNull() ?: 0L,
                end = info.optString("end_time").toLongOrNull() ?: 0L,
                lottery = info.optBoolean("lottery_exist"),
                exchange = info.optBoolean("exchange_exist"),
                // The Traveling Mimo list has no bind flag: a game is only listed when the account has a role in it.
                bound = if (legacy) o.optBoolean("bind_uid") else true
            )
            val current = best
            if (current == null || act.start > current.start) best = act
        }
        return r to best
    }

    suspend fun tasks(cr: Credentials, act: WorkshopAct): Pair<ApiResult, List<WorkshopTask>> {
        val url = if (act.game.legacy) {
            "$ACT/task_list?game_id=${act.gameId}&version_id=${act.versionId}&lang=en-us"
        } else {
            "$MIMO/task-list?lang=en-us&game_id=${act.gameId}&version_id=${act.versionId}"
        }
        val r = HoyoApi.call(url, cr.hoyolabCookie())
        val arr = r.data?.optJSONArray("task_list") ?: return r to emptyList()
        val list = List(arr.length()) { i ->
            val t = arr.getJSONObject(i)
            WorkshopTask(
                id = t.getInt("task_id"),
                name = t.optString("task_name"),
                timeType = t.optInt("time_type"),
                points = t.optInt("point"),
                progress = t.optInt("progress"),
                total = t.optInt("total_progress", 1),
                status = t.optInt("status"),
                type = t.optInt("task_type"),
                readSeconds = t.optInt("read_seconds")
            )
        }
        return r to list
    }

    private fun body(act: WorkshopAct, taskId: Int) =
        JSONObject().put("game_id", act.gameId).put("task_id", taskId).put("version_id", act.versionId)

    /**
     * Asks the server to count a "go and look at this" task as done.
     * Genshin's request was captured. Traveling Mimo's was not: it follows the same naming pattern as its other
     * endpoints (finish-task), so if the server doesn't know it the task simply stays "needs you".
     */
    suspend fun finish(cr: Credentials, act: WorkshopAct, taskId: Int): ApiResult =
        if (act.game.legacy) {
            HoyoApi.call("$PUBLIC/finish_task", cr.hoyolabCookie(), body(act, taskId))
        } else {
            HoyoApi.call("$MIMO/finish-task?lang=en-us", cr.hoyolabCookie(), body(act, taskId).put("lang", "en-us"))
        }

    suspend fun receive(cr: Credentials, act: WorkshopAct, taskId: Int): ApiResult =
        if (act.game.legacy) {
            HoyoApi.call("$ACT/receive_point?lang=en-us", cr.hoyolabCookie(), body(act, taskId))
        } else {
            HoyoApi.call(
                "$MIMO/receive-point?lang=en-us&game_id=${act.gameId}&task_id=$taskId&version_id=${act.versionId}",
                cr.hoyolabCookie()
            )
        }

    /** Every reward of the exchange shop, in the order the server lists them (claimed ones included). */
    suspend fun exchangeList(cr: Credentials, act: WorkshopAct): Pair<ApiResult, List<ExchangeAward>> {
        val url = if (act.game.legacy) {
            "$ACT/exchange_list?game_id=${act.gameId}&version_id=${act.versionId}&lang=en-us"
        } else {
            "$MIMO/exchange-list?lang=en-us&game_id=${act.gameId}&version_id=${act.versionId}"
        }
        val r = HoyoApi.call(url, cr.hoyolabCookie())
        val arr = r.data?.optJSONArray("exchange_award_list") ?: return r to emptyList()
        val nowSec = System.currentTimeMillis() / 1000
        val list = List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            // Traveling Mimo sends the seconds left until the refresh, Genshin an absolute time. Store it absolute.
            val raw = o.optLong("next_refresh_time")
            ExchangeAward(
                id = o.getInt("award_id"),
                status = o.optInt("status"),
                name = o.optString("name"),
                cost = o.optInt("cost"),
                stock = o.optInt("stock"),
                nextRefresh = if (raw in 1 until 1_000_000_000L) nowSec + raw else raw,
                expireDays = o.optInt("expire_day")
            )
        }
        return r to list
    }

    suspend fun exchange(cr: Credentials, act: WorkshopAct, awardId: Int): ExchangeOutcome {
        val body = JSONObject().put("award_id", awardId).put("game_id", act.gameId).put("version_id", act.versionId)
        val url = if (act.game.legacy) {
            "$ACT/exchange?lang=en-us"
        } else {
            body.put("lang", "en-us")
            "$MIMO/exchange?lang=en-us"
        }
        val r = HoyoApi.call(url, cr.hoyolabCookie(), body)
        return ExchangeOutcome(r, r.data?.optString("exchange_code").orEmpty())
    }

    suspend fun lotteryInfo(cr: Credentials, act: WorkshopAct): Pair<ApiResult, LotteryInfo?> {
        val r = HoyoApi.call(
            "$MIMO/lottery-info?lang=en-us&game_id=${act.gameId}&version_id=${act.versionId}",
            cr.hoyolabCookie()
        )
        val d = r.data ?: return r to null
        if (r.retcode != 0) return r to null
        val limit = d.optInt("limit_count").takeIf { it > 0 } ?: DEFAULT_DRAW_LIMIT
        return r to LotteryInfo(d.optInt("point"), d.optInt("cost"), d.optInt("count"), limit)
    }

    suspend fun draw(cr: Credentials, act: WorkshopAct): DrawOutcome {
        val body = JSONObject().put("game_id", act.gameId).put("lang", "en-us").put("version_id", act.versionId)
        val r = HoyoApi.call("$MIMO/lottery?lang=en-us", cr.hoyolabCookie(), body)
        return DrawOutcome(r, r.data?.optString("name").orEmpty(), r.data?.optString("exchange_code").orEmpty())
    }
}
