package com.mineygg.ergon.core

import android.content.Context
import com.mineygg.ergon.data.Credentials
import com.mineygg.ergon.data.ExchangeMode
import com.mineygg.ergon.data.ItemState
import com.mineygg.ergon.data.RecordItem
import com.mineygg.ergon.data.RedeemEntry
import com.mineygg.ergon.data.RunRecord
import com.mineygg.ergon.data.RunReport
import com.mineygg.ergon.data.Settings
import com.mineygg.ergon.data.Store
import com.mineygg.ergon.data.WorkshopInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Two independent tasks that share one lock (so they never overlap):
 *  - [runCheckin]: daily check-in, never re-requesting games already done today (HoYoLAB day = UTC+8)
 *  - [runRedeem]:  gift codes. Normally a cheap "watch" (only acts if a code page changed);
 *                  every 6 hours, or when forced, a full pass. A keep-alive redeem request goes out at least daily.
 * Each run publishes live progress to [RunBus] and a friendly [RunRecord] for the Activity tab.
 */
class Runner(private val ctx: Context, private val store: Store) {

    companion object {
        private val lock = Mutex()
        private const val BETWEEN_CODE_MS = 10_000L
        private const val BETWEEN_ACCOUNT_MS = 5_000L
        private const val BETWEEN_GAME_MS = 8_000L
        private const val RATE_LIMIT_WAIT_MS = 30_000L

        /** Minimum gap between two Machine Workshop claim requests (finish_task, receive_point, exchange). */
        private const val MIN_CLAIM_GAP_MS = 5_000L
        private const val FULL_PASS_MS = 6L * 60 * 60 * 1000

        /** cookie_token expires after ~2 days idle, so make sure a redeem request goes out at least this often. */
        private const val KEEPALIVE_MS = 20L * 60 * 60 * 1000
    }

    /** Returns null if another run is active or nothing could run. */
    suspend fun runCheckin(trigger: String): RunReport? =
        guarded(Task.CHECKIN, trigger) { checkin(trigger) }

    suspend fun runRedeem(trigger: String, force: Boolean): RunReport? =
        guarded(Task.REDEEM, trigger) { redeem(trigger, force) }

    /** [finalCall] = the mandatory run 15 min before a reset: claims everything that is complete. */
    suspend fun runWorkshop(trigger: String, force: Boolean, finalCall: Boolean = false): RunReport? =
        guarded(Task.WORKSHOP, trigger) { workshop(trigger, force, finalCall) }

    private suspend fun guarded(task: Task, trigger: String, block: suspend () -> RunReport?): RunReport? {
        if (!lock.tryLock()) return null
        try {
            RunBus.begin(task, trigger)
            RunBus.attach(currentCoroutineContext()[Job])
            return block()
        } catch (e: CancellationException) {
            RunBus.log("■ Stopped")
            throw e
        } catch (e: Exception) {
            RunBus.log("✖ Unexpected error: ${e.message}")
            return null
        } finally {
            store.saveLog(RunBus.log.value)
            RunBus.end()
            lock.unlock()
        }
    }

    // ── Daily check-in ──────────────────────────────────────────────────────
    private suspend fun checkin(trigger: String): RunReport? {
        val s = store.settings.value
        val cr = store.creds.value
        if (!s.checkin) {
            RunBus.log("Check-in is turned off in settings.")
            return null
        }
        if (!cr.hasHoyolab) {
            RunBus.log("✖ Not signed in. Open the app and connect your account.")
            if (trigger == "scheduled") Notifier.alert(ctx, "Sign-in needed", "Open Ergon and connect your account.")
            return null
        }

        val results = LinkedHashMap<String, String>()
        val items = ArrayList<RecordItem>()
        val claimedNow = ArrayList<String>()
        var hoyolabExpired = false
        var loginExpired = false
        var calls = 0
        var hoyolabCalls = 0

        for (g in Game.entries) {
            if (g.id !in s.enabledGames) continue

            val prior = store.checkinToday(g.id)
            // Never ask the server again for a game that is already done today.
            if (prior != null) {
                results[g.id] = prior.name
                RunBus.liveStatus(g.id, prior.name)
                RunBus.log("• ${g.title}: already done today, no request sent")
                val text = if (prior == CheckinStatus.SUCCESS) "Claimed earlier today" else "Already claimed today"
                items.add(RecordItem(g.id, g.title, text, ItemState.INFO))
                continue
            }

            RunBus.liveGame(g.id)
            RunBus.step("Check-in: ${g.title}")
            calls++
            if (!g.checkin.useCookieToken) hoyolabCalls++
            val r = HoyoApi.checkin(g, cr)
            results[g.id] = r.status.name

            when (r.status) {
                CheckinStatus.SUCCESS -> {
                    store.setCheckinToday(g.id, CheckinStatus.SUCCESS)
                    claimedNow.add(g.title)
                    RunBus.log("✔ ${g.title}: check-in claimed")
                    items.add(RecordItem(g.id, g.title, "Check-in claimed", ItemState.OK))
                }
                CheckinStatus.ALREADY -> {
                    store.setCheckinToday(g.id, CheckinStatus.ALREADY)
                    results[g.id] = CheckinStatus.ALREADY.name
                    RunBus.log("• ${g.title}: already checked in")
                    items.add(RecordItem(g.id, g.title, "Already claimed today", ItemState.INFO))
                }
                CheckinStatus.TOKEN_EXPIRED -> {
                    RunBus.log("✖ ${g.title}: session expired")
                    if (g.checkin.useCookieToken) loginExpired = true else hoyolabExpired = true
                    items.add(RecordItem(g.id, g.title, "Login expired. Sign in again.", ItemState.ERROR))
                }
                CheckinStatus.CAPTCHA -> {
                    RunBus.log("⚠ ${g.title}: captcha required, skipped")
                    items.add(RecordItem(g.id, g.title, "Captcha required, skipped", ItemState.WARN))
                }
                CheckinStatus.FAILED -> {
                    RunBus.log("✖ ${g.title}: ${r.message}")
                    items.add(RecordItem(g.id, g.title, "Failed: ${r.message}", ItemState.ERROR))
                }
            }
            RunBus.liveStatus(g.id, results[g.id] ?: r.status.name)
            delay(800)
        }
        RunBus.liveGame(null)

        if (hoyolabCalls > 0) store.setHealth(hoyolabExpired = hoyolabExpired)
        if (loginExpired) store.setHealth(loginExpired = true)

        val now = System.currentTimeMillis()
        val report = RunReport(now, trigger, results, 0, 0, 0, emptyList())
        val prev = store.checkinReport.value
        if (calls > 0 || prev == null || !Time.isToday(prev.timeMillis)) store.saveCheckinReport(report)

        if (calls > 0) {
            val nClaimed = items.count { it.state == ItemState.OK }
            val nDone = items.count { it.state == ItemState.INFO }
            val nBad = items.count { it.state == ItemState.WARN || it.state == ItemState.ERROR }
            val summary = listOfNotNull(
                if (nClaimed > 0) "$nClaimed claimed" else null,
                if (nDone > 0) "$nDone already done" else null,
                if (nBad > 0) "$nBad need attention" else null
            ).joinToString(" · ").ifEmpty { "Nothing to claim" }
            store.addRecord(RunRecord(now, "CHECKIN", trigger, summary, nBad == 0, items))
        }
        RunBus.log(if (calls == 0) "Nothing to do. Everything was already claimed today." else "Done.")

        if (hoyolabExpired || loginExpired) {
            Notifier.alert(ctx, "Session expired", "Open Ergon and log in again to keep claiming.")
        } else if (claimedNow.isNotEmpty() && s.notify) {
            Notifier.checkinClaimed(ctx, claimedNow)
        }
        return report
    }

    // ── Gift codes ──────────────────────────────────────────────────────────
    private suspend fun redeem(trigger: String, force: Boolean): RunReport? {
        val s = store.settings.value
        val cr = store.creds.value
        if (!s.autoRedeem) {
            RunBus.log("Auto-redeem is turned off in settings.")
            return null
        }
        if (!cr.hasHoyolab) {
            RunBus.log("✖ Not signed in. Open the app and connect your account.")
            return null
        }
        if (!cr.hasLogin) {
            RunBus.log("⚠ Redeem skipped: finish setup to enable code redemption.")
            return null
        }
        if (cr.hoyolabExpired) {
            RunBus.log("⚠ Redeem skipped: HoYoLAB session expired.")
            return null
        }

        val now = System.currentTimeMillis()
        val full = force || now - store.lastFullRedeem >= FULL_PASS_MS
        RunBus.log(if (full) "Full pass (checks every code page)" else "Watching for new codes")

        val entries = ArrayList<RedeemEntry>()
        val notices = ArrayList<RecordItem>()
        var claimed = 0
        var skipped = 0
        var failed = 0
        var loginExpired = false
        var redeemCalls = 0
        var aborted = false

        val games = Game.entries.filter { it.redeem != null && it.id in s.enabledGames }
        outer@ for ((gi, game) in games.withIndex()) {
            val rc = game.redeem!!
            RunBus.liveGame(game.id)
            RunBus.liveDetail("${game.title} · checking code page")
            RunBus.step("${game.title}: checking code page")

            val fetched = Game8Fetcher.fetch(ctx, store, game.id, rc.scrapeUrl, conditional = !full) { RunBus.log(it) }
            if (fetched.notModified) {
                RunBus.log("• ${game.title}: page unchanged")
                continue
            }
            val html = fetched.html
            if (html == null) {
                RunBus.log("⚠ ${game.title}: couldn't load the code page")
                notices.add(RecordItem(game.id, game.title, "Couldn't load the code page", ItemState.WARN))
                aborted = true
                continue
            }

            val codes = withContext(Dispatchers.Default) { CodeParser.parse(html) }
            if (codes.isEmpty()) {
                RunBus.log("⚠ ${game.title}: no codes found (page layout may have changed)")
                notices.add(RecordItem(game.id, game.title, "No codes found on the page", ItemState.WARN))
            } else {
                RunBus.log("${game.title}: ${codes.size} active code(s)")
            }

            val sig = codes.map { it.code }.sorted().joinToString(",").hashCode().toString()
            if (!full && sig == store.codesSig(game.id)) {
                RunBus.log("• ${game.title}: no new codes")
                continue
            }

            val accounts = HoyoApi.discover(rc.gameBiz, cr)
            if (accounts.isEmpty()) {
                RunBus.log("• ${game.title}: no linked account found")
                notices.add(RecordItem(game.id, game.title, "No linked game account found", ItemState.INFO))
                continue
            }

            var gameOk = true
            for ((ai, acc) in accounts.withIndex()) {
                RunBus.step("${game.title}: ${acc.nickname.ifEmpty { acc.uid }}")
                val key = "${game.id}:${acc.uid}"

                for (c in codes) {
                    if (store.isClaimed(key, c.code)) {
                        skipped++
                        RunBus.liveCounts(claimed, skipped, failed)
                        continue
                    }
                    RunBus.liveDetail("${game.title} · ${c.code}")
                    RunBus.log("  trying ${c.code}")

                    redeemCalls++
                    var res = HoyoApi.redeem(game, c.code, acc, cr)
                    if (res.rateLimited) {
                        RunBus.liveDetail("Rate limited, waiting 30 s…")
                        RunBus.log("  ⚠ rate limited, waiting 30s")
                        delay(RATE_LIMIT_WAIT_MS)
                        res = HoyoApi.redeem(game, c.code, acc, cr)
                    }

                    if (res.tokenExpired) {
                        RunBus.log("✖ ${c.code}: ${res.message} (session expired, stopping)")
                        failed++
                        RunBus.liveCounts(claimed, skipped, failed)
                        loginExpired = true
                        aborted = true
                        break@outer
                    }
                    if (res.networkError) {
                        RunBus.log("✖ ${c.code}: ${res.message}. Skipping account.")
                        failed++
                        RunBus.liveCounts(claimed, skipped, failed)
                        gameOk = false
                        break
                    }

                    if (!res.rateLimited) store.setLastRedeemCall(System.currentTimeMillis())

                    // Handled either way, so bad codes aren't retried on every run.
                    store.addClaimed(key, c.code)
                    val rewards = c.rewards.joinToString(", ")

                    when {
                        res.success -> {
                            claimed++
                            RunBus.log("  ✔ ${c.code}: redeemed")
                            entries.add(RedeemEntry(game.id, c.code, rewards, "CLAIMED", res.message))
                        }
                        res.alreadyClaimed -> {
                            skipped++
                            RunBus.log("  • ${c.code}: ${res.message}")
                            entries.add(RedeemEntry(game.id, c.code, rewards, "SKIPPED", res.message))
                        }
                        else -> {
                            failed++
                            RunBus.log("  ✖ ${c.code}: ${res.message}")
                            entries.add(RedeemEntry(game.id, c.code, rewards, "FAILED", res.message))
                        }
                    }
                    RunBus.liveCounts(claimed, skipped, failed)
                    delay(BETWEEN_CODE_MS)
                }

                if (ai < accounts.size - 1) delay(BETWEEN_ACCOUNT_MS)
            }

            if (gameOk) store.setCodesSig(game.id, sig)
            if (gi < games.size - 1) delay(BETWEEN_GAME_MS)
        }
        RunBus.liveGame(null)

        // Keep the session alive: at least one redeem request per day, from any game. A real code
        // counts; otherwise ping with a permanent code.
        if (!loginExpired && System.currentTimeMillis() - store.lastRedeemCall >= KEEPALIVE_MS) {
            when (keepAlive(games, cr)) {
                true -> loginExpired = true
                false -> RunBus.log("Keep-alive ping sent")
                null -> RunBus.log("⚠ Keep-alive ping couldn't be sent, will retry next run")
            }
        }

        store.touchCodeCheck()
        if (full && !aborted) store.setLastFullRedeem(now)
        if (redeemCalls > 0 || loginExpired) store.setHealth(loginExpired = loginExpired)

        RunBus.log(
            if (redeemCalls == 0) {
                "Done. No new codes."
            } else {
                "Done. Redeemed $claimed · skipped $skipped · failed $failed"
            }
        )

        val end = System.currentTimeMillis()
        val report = RunReport(end, trigger, emptyMap(), claimed, skipped, failed, entries)
        if (redeemCalls > 0 || full) store.saveRedeemReport(report)

        // Friendly record for the Activity tab (hourly "nothing changed" watches are not recorded).
        if (redeemCalls > 0 || full || notices.isNotEmpty() || loginExpired) {
            val items = ArrayList<RecordItem>()
            entries.forEach { e ->
                val g = Game.entries.firstOrNull { it.id == e.game }
                val detail = listOf(g?.title.orEmpty(), e.rewards).filter { it.isNotEmpty() }.joinToString(" · ")
                when (e.outcome) {
                    "CLAIMED" -> items.add(RecordItem(e.game, e.code, detail, ItemState.OK, mono = true))
                    "SKIPPED" -> items.add(RecordItem(e.game, e.code, "Already used or expired", ItemState.INFO, mono = true))
                    else -> items.add(RecordItem(e.game, e.code, "Failed: ${e.message}", ItemState.ERROR, mono = true))
                }
            }
            items.addAll(notices)
            if (loginExpired) items.add(RecordItem(null, "Session expired", "Sign in again to keep redeeming", ItemState.ERROR))

            val nOk = entries.count { it.outcome == "CLAIMED" }
            val nUsed = entries.count { it.outcome == "SKIPPED" }
            val nFail = entries.count { it.outcome == "FAILED" }
            val summary = when {
                loginExpired -> "Session expired"
                nOk + nUsed + nFail == 0 -> if (notices.any { it.state == ItemState.WARN }) "Some pages need attention" else "No new codes"
                else -> listOfNotNull(
                    if (nOk > 0) "$nOk redeemed" else null,
                    if (nUsed > 0) "$nUsed already used" else null,
                    if (nFail > 0) "$nFail failed" else null
                ).joinToString(" · ")
            }
            val ok = !loginExpired && nFail == 0 && notices.none { it.state == ItemState.WARN }
            store.addRecord(RunRecord(end, "REDEEM", trigger, summary, ok, items))
        }

        if (loginExpired) {
            Notifier.alert(ctx, "Session expired", "Open Ergon and log in again to keep redeeming.")
        } else {
            val fresh = entries.filter { it.outcome == "CLAIMED" }
            if (fresh.isNotEmpty() && s.notify) Notifier.codesRedeemed(ctx, fresh)
        }
        return report
    }

    /**
     * Sends one permanent-code redeem request for the first game that has a linked account.
     * Returns true if the session turned out to be expired, false if the ping went through,
     * null if it couldn't be sent (no account, network error, rate limit).
     */
    private suspend fun keepAlive(games: List<Game>, cr: Credentials): Boolean? {
        for (game in games) {
            val rc = game.redeem ?: continue
            val acc = HoyoApi.discover(rc.gameBiz, cr).firstOrNull() ?: continue
            RunBus.step("${game.title}: keep-alive ping")
            val ping = HoyoApi.redeem(game, rc.keepAliveCode, acc, cr)
            if (ping.networkError || ping.rateLimited) return null
            if (ping.tokenExpired) {
                RunBus.log("✖ Keep-alive: session expired")
                return true
            }
            store.setLastRedeemCall(System.currentTimeMillis())
            return false
        }
        return null
    }

    // ── Machine Workshop (Genshin web event on HoYoLAB) ─────────────────────
    // Per task, every run:
    //  - status 3 = already claimed on the server: never touched again.
    //  - completion is judged by progress: progress >= total but status not 3 means complete and unclaimed,
    //    so claim it (every run, always). Other statuses (e.g. 2) are not interpreted, only progress is.
    //  - otherwise try finish_task once per period. If the server accepts it, claim; if it refuses, the
    //    player has to do it: remember that for the period and just watch its progress from then on.
    // Runs: 30 min after each reset, 15 min before each reset (mandatory claim), and every 6 hours.
    // After the tasks, if auto-exchange is on, the exchange shop is read once and rewards are claimed
    // (see autoExchange). There is no timer of its own: timed rewards are only looked at on these runs.
    // Every claim request is at least MIN_CLAIM_GAP_MS after the previous one.
    private suspend fun workshop(trigger: String, force: Boolean, finalCall: Boolean): RunReport? {
        val s = store.settings.value
        val cr = store.creds.value
        if (!s.workshop) {
            RunBus.log("Machine Workshop is turned off in settings.")
            return null
        }
        if ("genshin" !in s.enabledGames) {
            RunBus.log("Genshin is turned off, skipping the Machine Workshop.")
            return null
        }
        if (!cr.hasHoyolab) {
            RunBus.log("✖ Not signed in. Open the app and connect your account.")
            return null
        }
        if (cr.hoyolabExpired) {
            RunBus.log("⚠ Machine Workshop skipped: HoYoLAB session expired.")
            return null
        }

        if (finalCall) RunBus.log("Final call: a reset is 15 min away, claiming everything that is complete")
        RunBus.step("Machine Workshop: loading the event")
        val (indexRes, act) = WorkshopApi.index(cr)
        if (act == null) {
            when {
                indexRes.network -> RunBus.log("✖ Machine Workshop: network error (${indexRes.message})")
                indexRes.retcode == -100 -> {
                    store.setHealth(hoyolabExpired = true)
                    RunBus.log("✖ Machine Workshop: session expired")
                }
                indexRes.retcode == 0 -> RunBus.log("• Machine Workshop: no active event right now")
                else -> RunBus.log("✖ Machine Workshop: [${indexRes.retcode}] ${indexRes.message}")
            }
            return null
        }
        val nowSec = System.currentTimeMillis() / 1000
        if (act.end in 1 until nowSec) {
            RunBus.log("• Machine Workshop: the event has ended")
            return null
        }
        if (!act.bound) {
            RunBus.log("⚠ Machine Workshop: no Genshin account is linked to your HoYoLAB")
            return null
        }
        RunBus.log("Machine Workshop v${act.versionId}: ${act.points} points so far")
        store.saveWorkshopInfo(WorkshopInfo(act.versionId, act.points, act.end, act.lottery, act.exchange, System.currentTimeMillis()))

        val (taskRes, tasks) = WorkshopApi.tasks(cr, act)
        if (taskRes.retcode != 0) {
            RunBus.log("✖ Machine Workshop: couldn't load tasks [${taskRes.retcode}] ${taskRes.message}")
            return null
        }

        val items = ArrayList<RecordItem>()
        val pacer = ClaimPacer()
        var attempted = 0
        var needYou = 0
        var sessionExpired = false

        for (t in tasks) {
            val prefix = "${act.versionId}:${t.id}:${t.timeType}:"
            val key = prefix + Time.workshopPeriodKey(t.timeType, t.type)

            // Status 3 = already claimed on the server. Never touch the task again.
            if (t.status == WorkshopTask.STATUS_CLAIMED) {
                store.setWorkshopDone(prefix, key)
                RunBus.log("• ${t.name}: already claimed")
                items.add(RecordItem("genshin", t.name, "Claimed", ItemState.INFO))
                continue
            }
            if (!force && store.workshopDone(key)) {
                RunBus.log("• ${t.name}: already collected")
                items.add(RecordItem("genshin", t.name, "Already collected", ItemState.INFO))
                continue
            }

            val complete = t.progress >= t.total
            if (!complete && !force && store.workshopNeedsYou(key)) {
                // Player has to do it. We only watch its progress (${t.progress}/${t.total}).
                RunBus.log("• ${t.name}: needs you (${t.progress}/${t.total})")
                items.add(RecordItem("genshin", t.name, "Needs you · ${t.progress}/${t.total}", ItemState.INFO))
                needYou++
                continue
            }
            attempted++
            RunBus.step("Machine Workshop: ${t.name}")

            if (!complete) {
                if (t.readSeconds > 0) {
                    RunBus.liveDetail("${t.name} · waiting ${t.readSeconds}s")
                    delay((t.readSeconds + 2) * 1000L)
                }
                pacer.pace()
                val f = WorkshopApi.finish(cr, act, t.id)
                RunBus.log("  finish_task #${t.id}: [${f.retcode}] ${f.message}")
                if (f.retcode == -100) {
                    sessionExpired = true
                    break
                }
                if (f.network) {
                    items.add(RecordItem("genshin", t.name, "Network error, will try next run", ItemState.WARN))
                    continue
                }
                if (f.retcode != 0) {
                    // The server refused: the player has to do this one. Track its progress from now on.
                    store.setWorkshopNeedsYou(prefix, key)
                    needYou++
                    val why = f.message.ifEmpty { "error ${f.retcode}" }
                    items.add(RecordItem("genshin", t.name, "Needs you · ${t.progress}/${t.total} · $why", ItemState.INFO))
                    continue
                }
            }

            pacer.pace()
            val r = WorkshopApi.receive(cr, act, t.id)
            RunBus.log("  receive_point #${t.id}: [${r.retcode}] ${r.message}")
            when {
                r.retcode == 0 -> {
                    store.setWorkshopDone(prefix, key)
                    RunBus.log("  ✔ ${t.name}: +${t.points} points")
                    items.add(RecordItem("genshin", t.name, "+${t.points} points", ItemState.OK))
                }
                r.retcode == -100 -> {
                    sessionExpired = true
                    break
                }
                r.network -> items.add(RecordItem("genshin", t.name, "Network error, will try next run", ItemState.WARN))
                !complete -> {
                    // finish_task was accepted but the task still isn't complete: the player has to do it.
                    store.setWorkshopNeedsYou(prefix, key)
                    needYou++
                    val why = r.message.ifEmpty { "error ${r.retcode}" }
                    items.add(RecordItem("genshin", t.name, "Needs you · ${t.progress}/${t.total} · $why", ItemState.INFO))
                }
                else -> {
                    val why = r.message.ifEmpty { "error ${r.retcode}" }
                    items.add(RecordItem("genshin", t.name, "Points not claimed: $why", ItemState.WARN))
                }
            }
        }

        if (sessionExpired) {
            store.setHealth(hoyolabExpired = true)
            RunBus.log("✖ Machine Workshop: session expired")
            Notifier.alert(ctx, "Session expired", "Open Ergon and log in again to keep collecting.")
        }

        // Read the new total from the server (only if something was actually attempted).
        var total = act.points
        if (attempted > 0) {
            val (_, after) = WorkshopApi.index(cr)
            if (after != null) {
                total = after.points
                store.saveWorkshopInfo(WorkshopInfo(after.versionId, after.points, after.end, after.lottery, after.exchange, System.currentTimeMillis()))
            }
        }
        val gained = total - act.points

        // Spend points last, so what was just earned counts.
        var ex = ExchangeRun()
        if (s.autoExchange && !sessionExpired) {
            if (act.exchange) {
                ex = autoExchange(cr, act, total, s, pacer, items)
                if (ex.expired) {
                    store.setHealth(hoyolabExpired = true)
                    RunBus.log("✖ Machine Workshop: session expired")
                    Notifier.alert(ctx, "Session expired", "Open Ergon and log in again to keep collecting.")
                }
                if (ex.claimed > 0) {
                    val (_, after) = WorkshopApi.index(cr)
                    if (after != null) {
                        total = after.points
                        store.saveWorkshopInfo(WorkshopInfo(after.versionId, after.points, after.end, after.lottery, after.exchange, System.currentTimeMillis()))
                    } else {
                        total -= ex.spent
                    }
                }
            } else {
                RunBus.log("• Auto exchange: the exchange isn't open right now")
            }
        }

        val didSomething = attempted > 0 || ex.claimed > 0
        val spentText = if (ex.spent > 0) " · ${ex.spent} spent" else ""
        RunBus.log(
            when {
                !didSomething -> "Done. Nothing new to try."
                else -> "Done. +$gained points$spentText, $total in total" + if (needYou > 0) " · $needYou need you" else ""
            }
        )

        val now = System.currentTimeMillis()
        if (didSomething || force) {
            val exchangedText = if (ex.claimed > 0) " · ${ex.claimed} exchanged" else ""
            val summary = when {
                items.isEmpty() -> "No tasks found"
                needYou > 0 -> "+$gained points$spentText$exchangedText · $total total · $needYou need you"
                else -> "+$gained points$spentText$exchangedText · $total total"
            }
            val ok = !sessionExpired && !ex.expired && items.none { it.state == ItemState.ERROR || it.state == ItemState.WARN }
            store.addRecord(RunRecord(now, "WORKSHOP", trigger, summary, ok, items))
        }
        if (gained > 0 && s.notify) Notifier.workshopPoints(ctx, gained, total)
        if (ex.claimed > 0 && s.notify) Notifier.exchangeClaimed(ctx, ex.names, ex.codes)
        return RunReport(now, trigger, emptyMap(), 0, 0, 0, emptyList())
    }

    /** Spaces claim requests out: never two within [MIN_CLAIM_GAP_MS]. */
    private class ClaimPacer {
        private var last = 0L

        suspend fun pace() {
            if (last != 0L) {
                val gap = MIN_CLAIM_GAP_MS - (System.nanoTime() - last) / 1_000_000L
                if (gap > 0) delay(gap)
            }
            last = System.nanoTime()
        }
    }

    private data class ExchangeRun(
        val claimed: Int = 0,
        val spent: Int = 0,
        val expired: Boolean = false,
        val names: List<String> = emptyList(),
        val codes: List<String> = emptyList()
    )

    /**
     * Exchange shop pass, one list request per run:
     *  1. rewards that cost 0 are always claimed (they don't touch the points);
     *  2. then the player's priority list, top to bottom:
     *     - already claimed (status 3) or not claimable now (status 2/4, or on a refresh timer): skipped, never blocks;
     *     - affordable: claimed;
     *     - not affordable: WAIT stops here and keeps the points, AFFORDABLE moves on to the next reward.
     * Points are tracked locally between claims, so one run never overspends.
     */
    private suspend fun autoExchange(
        cr: Credentials,
        act: WorkshopAct,
        startPoints: Int,
        s: Settings,
        pacer: ClaimPacer,
        items: MutableList<RecordItem>
    ): ExchangeRun {
        RunBus.step("Machine Workshop: exchange shop")
        val (listRes, list) = WorkshopApi.exchangeList(cr, act)
        if (listRes.retcode == -100) return ExchangeRun(expired = true)
        if (listRes.retcode != 0) {
            val why = if (listRes.network) "network error" else "[${listRes.retcode}] ${listRes.message}"
            RunBus.log("✖ Auto exchange: couldn't load the shop ($why)")
            items.add(RecordItem("genshin", "Exchange", "Couldn't load the shop, will try next run", ItemState.WARN))
            return ExchangeRun()
        }

        var points = startPoints
        var claimed = 0
        var spent = 0
        var expired = false
        val names = ArrayList<String>()
        val codes = ArrayList<String>()

        // Returns true if the request failed because of the network (so waiting on points makes no sense).
        suspend fun claim(a: ExchangeAward): Boolean {
            RunBus.step("Machine Workshop: exchanging ${a.name}")
            pacer.pace()
            val o = WorkshopApi.exchange(cr, act, a.id)
            val r = o.result
            RunBus.log("  exchange #${a.id}: [${r.retcode}] ${r.message}")
            when {
                r.retcode == 0 -> {
                    claimed++
                    points -= a.cost
                    spent += a.cost
                    names.add(a.name)
                    items.add(
                        RecordItem("genshin", "Exchanged: ${a.name}", if (a.cost == 0) "Free" else "-${a.cost} points", ItemState.OK)
                    )
                    if (o.code.isNotEmpty()) {
                        codes.add("${a.name}: ${o.code}")
                        items.add(RecordItem("genshin", o.code, "Redeem code for ${a.name}", ItemState.OK, mono = true))
                    }
                }
                r.retcode == -100 -> expired = true
                r.network -> items.add(RecordItem("genshin", a.name, "Network error, will try next run", ItemState.WARN))
                else -> {
                    val why = r.message.ifEmpty { "error ${r.retcode}" }
                    items.add(RecordItem("genshin", a.name, "Not exchanged: $why", ItemState.WARN))
                }
            }
            return r.network
        }

        // 1. Free rewards.
        for (a in list) {
            if (a.status == ExchangeAward.STATUS_CLAIMED || a.cost != 0) continue
            if (!a.claimable) {
                RunBus.log("• ${a.name}: free but not claimable now${if (a.timed) " (on a refresh timer)" else ""}")
                continue
            }
            claim(a)
            if (expired) return ExchangeRun(claimed, spent, true, names, codes)
        }

        // 2. Priority list.
        val byId = list.associateBy { it.id }
        for (id in s.exchangePriority) {
            val a = byId[id] ?: continue
            if (a.status == ExchangeAward.STATUS_CLAIMED || a.cost == 0) continue
            if (!a.claimable) {
                RunBus.log("• ${a.name}: not claimable now" + if (a.timed) " (on a refresh timer, checked again next run)" else "")
                continue
            }
            if (points < a.cost) {
                val missing = a.cost - points
                if (s.exchangeMode == ExchangeMode.WAIT) {
                    RunBus.log("• Waiting for ${a.name}: $missing more points needed ($points/${a.cost})")
                    items.add(RecordItem("genshin", a.name, "Waiting for $missing more points · $points/${a.cost}", ItemState.INFO))
                    break
                }
                RunBus.log("• ${a.name}: costs ${a.cost}, have $points, moving on")
                continue
            }
            val network = claim(a)
            if (expired) break
            if (network && s.exchangeMode == ExchangeMode.WAIT) break
        }
        return ExchangeRun(claimed, spent, expired, names, codes)
    }
}
