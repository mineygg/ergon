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
}
