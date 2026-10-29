package com.mineygg.ergon.core

import com.mineygg.ergon.data.Credentials
import org.json.JSONObject

data class WorkshopAct(
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

    /** Timed reward that is on cooldown. Only looked at again on the next normal workshop run. */
    val timed: Boolean get() = nextRefresh != 0L

    companion object {
        /** exchange_list status: 1 = can be claimed, 3 = already claimed, 4 = out of stock. 2 is not understood (not claimable). */
        const val STATUS_AVAILABLE = 1
        const val STATUS_CLAIMED = 3
        const val STATUS_SOLD_OUT = 4
    }
}

/** Result of a successful exchange. [code] is only set for rewards delivered as an in-game redeem code. */
data class ExchangeOutcome(val result: ApiResult, val code: String)

/** "Hilichurls' Machine Workshop" (HoYoLAB web event). Endpoints as captured from the event page. */
object WorkshopApi {
    private const val ACT = "https://sg-act-public-api.hoyolab.com/event/e2023mimotravel/qiuqiu"
    private const val PUBLIC = "https://sg-public-api.hoyolab.com/event/e2023mimotravel/qiuqiu"
    private const val GENSHIN = 2

    /** The server tells us the current event version (version_id), so nothing is hard-coded. */
    suspend fun index(cr: Credentials): Pair<ApiResult, WorkshopAct?> {
        val r = HoyoApi.call("$ACT/index?lang=en-us", cr.hoyolabCookie())
        val data = r.data
        if (r.retcode != 0 || data == null) return r to null
        val arr = data.optJSONArray("act_list") ?: return r to null

        var best: WorkshopAct? = null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val info = o.optJSONObject("act_info") ?: continue
            if (info.optInt("game_id") != GENSHIN) continue
            val act = WorkshopAct(
                gameId = GENSHIN,
                versionId = info.optInt("version_id"),
                points = o.optInt("point", info.optInt("point")),
                start = info.optString("start_time").toLongOrNull() ?: 0L,
                end = info.optString("end_time").toLongOrNull() ?: 0L,
                lottery = info.optBoolean("lottery_exist"),
                exchange = info.optBoolean("exchange_exist"),
                bound = o.optBoolean("bind_uid")
            )
            val current = best
            if (current == null || act.start > current.start) best = act
        }
        return r to best
    }

    suspend fun tasks(cr: Credentials, act: WorkshopAct): Pair<ApiResult, List<WorkshopTask>> {
        val r = HoyoApi.call(
            "$ACT/task_list?game_id=${act.gameId}&version_id=${act.versionId}&lang=en-us",
            cr.hoyolabCookie()
        )
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

    suspend fun finish(cr: Credentials, act: WorkshopAct, taskId: Int): ApiResult =
        HoyoApi.call("$PUBLIC/finish_task", cr.hoyolabCookie(), body(act, taskId))

    suspend fun receive(cr: Credentials, act: WorkshopAct, taskId: Int): ApiResult =
        HoyoApi.call("$ACT/receive_point?lang=en-us", cr.hoyolabCookie(), body(act, taskId))

    /** Every reward of the exchange shop, in the order the server lists them (claimed ones included). */
    suspend fun exchangeList(cr: Credentials, act: WorkshopAct): Pair<ApiResult, List<ExchangeAward>> {
        val r = HoyoApi.call(
            "$ACT/exchange_list?game_id=${act.gameId}&version_id=${act.versionId}&lang=en-us",
            cr.hoyolabCookie()
        )
        val arr = r.data?.optJSONArray("exchange_award_list") ?: return r to emptyList()
        val list = List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            ExchangeAward(
                id = o.getInt("award_id"),
                status = o.optInt("status"),
                name = o.optString("name"),
                cost = o.optInt("cost"),
                stock = o.optInt("stock"),
                nextRefresh = o.optLong("next_refresh_time"),
                expireDays = o.optInt("expire_day")
            )
        }
        return r to list
    }

    suspend fun exchange(cr: Credentials, act: WorkshopAct, awardId: Int): ExchangeOutcome {
        val body = JSONObject().put("award_id", awardId).put("game_id", act.gameId).put("version_id", act.versionId)
        val r = HoyoApi.call("$ACT/exchange?lang=en-us", cr.hoyolabCookie(), body)
        return ExchangeOutcome(r, r.data?.optString("exchange_code").orEmpty())
    }
}
