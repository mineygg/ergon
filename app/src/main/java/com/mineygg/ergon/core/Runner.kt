package com.mineygg.ergon.core

import android.content.Context
import com.mineygg.ergon.data.Credentials
import com.mineygg.ergon.data.ExchangeMode
import com.mineygg.ergon.data.ExchangePrefs
import com.mineygg.ergon.data.ItemState
import com.mineygg.ergon.data.RecordItem
import com.mineygg.ergon.data.RedeemEntry
import com.mineygg.ergon.data.RunRecord
import com.mineygg.ergon.data.RunReport
import com.mineygg.ergon.data.Settings
import com.mineygg.ergon.data.Store
import com.mineygg.ergon.data.WorkshopInfo
import com.mineygg.ergon.work.RefreshAlarm
import com.mineygg.ergon.work.Scheduler
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

        // Timed rewards: the alarm goes off this long before the refresh, and a refresh closer than REFRESH_SOON_SEC is waited for.
        private const val REFRESH_LEAD_MS = 6_000L
        private const val REFRESH_SOON_SEC = 15L
        private const val REFRESH_TRIES = 8
        private const val REFRESH_RETRY_MS = 5L * 60 * 1000
        private const val REFRESH_STUCK_MS = 10L * 60 * 1000

        private const val FULL_PASS_MS = 6L * 60 * 60 * 1000

        // Connection handling. A scheduled run waits this long for the internet to come back, then is resumed later.
        private const val WAIT_START_MS = 90_000L
        private const val WAIT_MID_RUN_MS = 120_000L
        private const val WAIT_MANUAL_MID_RUN_MS = 20_000L
        private const val MAX_NET_TRIES = 3
        private const val NET_RETRY_MS = 3_000L

        /** cookie_token expires after ~2 days idle, so make sure a redeem request goes out at least this often. */
        private const val KEEPALIVE_MS = 20L * 60 * 60 * 1000
    }

    /** How the last run on this instance ended. */
    enum class Outcome {
        /** Ran to the end (or had nothing to do). */
        DONE,

        /** Another run held the lock, nothing was done. */
        BUSY,

        /** Cut short by the connection. It is saved as pending and resumed when the internet is back. */
        DEFERRED,

        /** Unexpected error. */
        ERROR
    }

    var outcome: Outcome = Outcome.DONE
        private set

    /** Set when something could not be finished because of the connection, so the task is resumed later. */
    private var deferred = false

    /** Scheduled runs wait for a lost connection; manual runs give up quickly, the person is looking at them. */
    private var patient = true

    init {
        Connectivity.start(ctx)
    }

    /** Returns null if another run is active or nothing could run. */
    suspend fun runCheckin(trigger: String): RunReport? =
        guarded(Task.CHECKIN, trigger) { checkin(trigger) }

    suspend fun runRedeem(trigger: String, force: Boolean): RunReport? =
        guarded(Task.REDEEM, trigger) { redeem(trigger, force) }

    /**
     * [finalCall] = the mandatory run 15 min before a reset: claims everything that is complete.
     * [only] limits the run to one game (the buttons on the Home cards); null runs every enabled game, one after the other.
     */
    suspend fun runWorkshop(trigger: String, force: Boolean, finalCall: Boolean = false, only: WorkshopGame? = null): RunReport? =
        guarded(Task.WORKSHOP, trigger) { workshop(trigger, force, finalCall, only) }

    /**
     * Started by the alarm of a timed reward: claims it the moment it refreshes. Returns null only if another
     * run holds the lock, so the caller can try again a few seconds later.
     */
    suspend fun runTimedExchange(wg: WorkshopGame): RunReport? =
        guarded(Task.WORKSHOP, "refresh") { timedExchange(wg) }

    private suspend fun guarded(task: Task, trigger: String, block: suspend () -> RunReport?): RunReport? {
        if (!lock.tryLock()) {
            outcome = Outcome.BUSY
            return null
        }
        outcome = Outcome.DONE
        deferred = false
        patient = trigger != "manual"
        try {
            RunBus.begin(task, trigger)
            RunBus.attach(currentCoroutineContext()[Job])
            awaitConnection(initial = true)
            return block()
        } catch (e: CancellationException) {
            outcome = Outcome.ERROR // stopped on purpose: don't touch the pending state
            RunBus.log("■ Stopped")
            throw e
        } catch (e: OfflineException) {
            deferred = true
            RunBus.log(
                if (trigger == "manual") "✖ No internet connection" else "⚠ No internet connection. This continues on its own once you're back online."
            )
            return null
        } catch (e: Exception) {
            outcome = Outcome.ERROR
            RunBus.log("✖ Unexpected error: ${e.message}")
            return null
        } finally {
            if (deferred) {
                outcome = Outcome.DEFERRED
                // Remember it across restarts and have it resumed the moment the connection is there.
                if (trigger != "manual") {
                    store.addPending(task.name)
                    Scheduler.catchUp(ctx)
                }
            } else if (outcome == Outcome.DONE) {
                store.clearPending(task.name)
            }
            store.saveLog(RunBus.log.value)
            RunBus.end()
            lock.unlock()
        }
    }

    /** Returns when the device is online. Throws [OfflineException] if it didn't come back within the wait. */
    private suspend fun awaitConnection(initial: Boolean) {
        if (Connectivity.isOnline(ctx)) return
        val wait = when {
            patient && initial -> WAIT_START_MS
            patient -> WAIT_MID_RUN_MS
            initial -> 0L
            else -> WAIT_MANUAL_MID_RUN_MS
        }
        RunBus.liveDetail("Waiting for the internet…")
        RunBus.log(if (wait > 0) "⚠ No internet connection, waiting for it…" else "✖ No internet connection")
        if (!Connectivity.awaitOnline(ctx, wait)) throw OfflineException()
        RunBus.log("• Back online")
    }

    /**
     * Runs [call] only while there is a connection, and sends it again when it failed because of the connection
     * ([failed] says so). If the device went offline during the call the request most likely never arrived, so it is
     * repeated once the internet is back, whatever it is. If the device is online but the call still fails, only
     * calls that are safe to repeat ([idempotent]) are tried again, with a short pause. Whatever still fails is
     * returned, and the task is marked to be resumed later.
     */
    private suspend fun <T> netCall(idempotent: Boolean = true, call: suspend () -> T, failed: (T) -> Boolean): T {
        var tries = 0
        while (true) {
            awaitConnection(initial = false)
            val r = call()
            if (!failed(r)) return r
            tries++
            if (!Connectivity.isOnline(ctx)) {
                if (tries >= MAX_NET_TRIES + 2) {
                    deferred = true
                    return r
                }
                continue
            }
            if (!idempotent || tries >= MAX_NET_TRIES) {
                deferred = true
                return r
            }
            RunBus.liveDetail("Connection problem, trying again…")
            delay(NET_RETRY_MS * tries)
        }
    }

    private suspend fun netResult(idempotent: Boolean = true, call: suspend () -> ApiResult): ApiResult =
        netCall(idempotent, call) { it.network }

    private suspend fun <T> netPair(idempotent: Boolean = true, call: suspend () -> Pair<ApiResult, T>): Pair<ApiResult, T> =
        netCall(idempotent, call) { it.first.network }

    // ── Daily check-in ──────────────────────────────────────────────────────
    private suspend fun checkin(trigger: String): RunReport? {
        val s = store.settings.value
        val cr = store.creds.value
        if (s.checkinGames.isEmpty()) {
            RunBus.log("Check-in is turned off for every game.")
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
            if (g.id !in s.checkinGames) continue

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
            val r = try {
                netCall(
                    call = { HoyoApi.checkin(g, cr) },
                    failed = { it.network }
                )
            } catch (e: OfflineException) {
                // Nothing is lost: games done so far stay done, this one and the rest are resumed when online.
                deferred = true
                RunBus.log("⚠ ${g.title}: lost the connection, check-in continues when it's back")
                items.add(RecordItem(g.id, g.title, "No connection, will continue when it's back", ItemState.WARN))
                break
            }
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
                    if (deferred) {
                        // The connection kept failing: not an answer from the server, so try again later.
                        items.add(RecordItem(g.id, g.title, "Connection problem, will try again", ItemState.WARN))
                    } else {
                        items.add(RecordItem(g.id, g.title, "Failed: ${r.message}", ItemState.ERROR))
                    }
                }
            }
            RunBus.liveStatus(g.id, results[g.id] ?: r.status.name)
            delay(800)
        }
        RunBus.liveGame(null)
        if (calls > 0) store.noteCheckinTry()

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
        RunBus.log(
            when {
                deferred -> "Paused: no connection. The rest is claimed automatically when you're back online."
                calls == 0 -> "Nothing to do. Everything was already claimed today."
                else -> "Done."
            }
        )

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
        if (s.redeemGames.isEmpty()) {
            RunBus.log("Auto-redeem is turned off for every game.")
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

        val games = Game.entries.filter { it.redeem != null && it.id in s.redeemGames }
        try {
            outer@ for ((gi, game) in games.withIndex()) {
                val rc = game.redeem!!
                RunBus.liveGame(game.id)
                RunBus.liveDetail("${game.title} · checking code page")
                RunBus.step("${game.title}: checking code page")

                awaitConnection(initial = false)
                val fetched = Game8Fetcher.fetch(ctx, store, game.id, rc.scrapeUrl, conditional = !full) { RunBus.log(it) }
                if (fetched.notModified) {
                    RunBus.log("• ${game.title}: page unchanged")
                    continue
                }
                val html = fetched.html
                if (html == null) {
                    if (!Connectivity.isOnline(ctx)) throw OfflineException()
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

                awaitConnection(initial = false)
                val accounts = HoyoApi.discover(rc.gameBiz, cr)
                if (accounts.isEmpty()) {
                    // An empty list while offline is the connection, not a missing account.
                    if (!Connectivity.isOnline(ctx)) throw OfflineException()
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

                        awaitConnection(initial = false)
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
                            // Not an answer about the code: leave it unclaimed so it is tried again when online.
                            if (!Connectivity.isOnline(ctx)) throw OfflineException()
                            RunBus.log("✖ ${c.code}: ${res.message}. Skipping account, will try again.")
                            deferred = true
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
        } catch (e: OfflineException) {
            // Codes already handled are saved as claimed. The rest is picked up by a full pass once online.
            deferred = true
            aborted = true
            RunBus.log("⚠ Lost the connection, the remaining codes are redeemed when it's back")
            notices.add(RecordItem(null, "No connection", "Remaining codes will be redeemed when it's back", ItemState.WARN))
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
        if (full && !aborted && !deferred) store.setLastFullRedeem(now)
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

    // ── Machine Workshop (Genshin) and Traveling Mimo (Star Rail, Zenless) ───
    // All three are the same kind of HoYoLAB event and run through the same code, one game after the other,
    // sharing one claim pacer so that no two claim requests (of any game) are closer than 5 seconds.
    // Per task, every run:
    //  - status 3 = already claimed on the server: never touched again.
    //  - completion is judged by progress: progress >= total but status not 3 means complete and unclaimed,
    //    so claim it (every run, always). Other statuses (e.g. 2) are not interpreted, only progress is.
    //  - otherwise try finish_task once per period. If the server accepts it, claim; if it refuses, the
    //    player has to do it: remember that for the period and just watch its progress from then on.
    // Runs: 30 min after each reset, 15 min before each reset (mandatory claim), and every 6 hours.
    // After the tasks, if the game's auto-exchange is on, its exchange shop is read once and rewards are claimed
    // (see autoExchange). There is no timer of its own: timed rewards are only looked at on these runs.
    private suspend fun workshop(trigger: String, force: Boolean, finalCall: Boolean, only: WorkshopGame?): RunReport? {
        val s = store.settings.value
        val cr = store.creds.value
        val games = WorkshopGame.entries.filter { (only == null || it == only) && it.id in s.workshopGames }
        if (games.isEmpty()) {
            RunBus.log("Machine Workshop and Traveling Mimo are turned off in settings.")
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
        for (wg in games) {
            RunBus.liveGame(wg.id)
            val expired = workshopGame(wg, cr, s, trigger, force)
            if (expired) break
        }
        RunBus.liveGame(null)
        return RunReport(System.currentTimeMillis(), trigger, emptyMap(), 0, 0, 0, emptyList())
    }

    /** One game's event. Returns true if the HoYoLAB session turned out to be expired (so the other games are skipped). */
    private suspend fun workshopGame(wg: WorkshopGame, cr: Credentials, s: Settings, trigger: String, force: Boolean): Boolean {
        val label = wg.label
        RunBus.step("$label: loading the event")
        val (indexRes, act) = netPair { WorkshopApi.index(cr, wg) }
        if (act == null) {
            when {
                indexRes.network -> RunBus.log("✖ $label: network error (${indexRes.message}), will try again")
                indexRes.retcode == -100 -> {
                    store.setHealth(hoyolabExpired = true)
                    RunBus.log("✖ $label: session expired")
                    return true
                }
                indexRes.retcode == 0 -> RunBus.log("• $label: no active event right now")
                else -> RunBus.log("✖ $label: [${indexRes.retcode}] ${indexRes.message}")
            }
            return false
        }
        val nowSec = System.currentTimeMillis() / 1000
        if (act.end in 1 until nowSec) {
            RunBus.log("• $label: the event has ended")
            return false
        }
        if (!act.bound) {
            RunBus.log("⚠ $label: no ${wg.game.title} account is linked to your HoYoLAB")
            return false
        }
        RunBus.log("$label v${act.versionId}: ${act.points} points so far")
        saveInfo(wg, act)

        val (taskRes, tasks) = netPair { WorkshopApi.tasks(cr, act) }
        if (taskRes.retcode != 0) {
            RunBus.log("✖ $label: couldn't load tasks [${taskRes.retcode}] ${taskRes.message}")
            return false
        }

        val items = ArrayList<RecordItem>()
        var attempted = 0
        var needYou = 0
        var sessionExpired = false

        var lostConnection = false
        try {
            for (t in tasks) {
                val prefix = "${wg.id}:${act.versionId}:${t.id}:${t.timeType}:"
                val key = prefix + Time.workshopPeriodKey(t.timeType, wg.isGameTask(t.type))

                // Status 3 = already claimed on the server. Never touch the task again.
                if (t.status == WorkshopTask.STATUS_CLAIMED) {
                    store.setWorkshopDone(prefix, key)
                    RunBus.log("• ${t.name}: already claimed")
                    items.add(RecordItem(wg.id, t.name, "Claimed", ItemState.INFO))
                    continue
                }
                if (!force && store.workshopDone(key)) {
                    RunBus.log("• ${t.name}: already collected")
                    items.add(RecordItem(wg.id, t.name, "Already collected", ItemState.INFO))
                    continue
                }

                val complete = t.progress >= t.total
                if (!complete && !force && store.workshopNeedsYou(key)) {
                    // Player has to do it. We only watch its progress (${t.progress}/${t.total}).
                    RunBus.log("• ${t.name}: needs you (${t.progress}/${t.total})")
                    items.add(RecordItem(wg.id, t.name, "Needs you · ${t.progress}/${t.total}", ItemState.INFO))
                    needYou++
                    continue
                }
                attempted++
                RunBus.step("$label: ${t.name}")

                if (!complete) {
                    if (t.readSeconds > 0) {
                        RunBus.liveDetail("${t.name} · waiting ${t.readSeconds}s")
                        delay((t.readSeconds + 2) * 1000L)
                    }
                    ClaimPacer.pace()
                    val f = netResult(idempotent = false) { WorkshopApi.finish(cr, act, t.id) }
                    RunBus.log("  finish_task #${t.id}: [${f.retcode}] ${f.message}")
                    if (f.retcode == -100) {
                        sessionExpired = true
                        break
                    }
                    if (f.network) {
                        items.add(RecordItem(wg.id, t.name, "Network error, will try next run", ItemState.WARN))
                        continue
                    }
                    if (f.retcode != 0) {
                        // The server refused: the player has to do this one. Track its progress from now on.
                        store.setWorkshopNeedsYou(prefix, key)
                        needYou++
                        val why = f.message.ifEmpty { "error ${f.retcode}" }
                        items.add(RecordItem(wg.id, t.name, "Needs you · ${t.progress}/${t.total} · $why", ItemState.INFO))
                        continue
                    }
                }

                ClaimPacer.pace()
                val r = netResult(idempotent = false) { WorkshopApi.receive(cr, act, t.id) }
                RunBus.log("  receive_point #${t.id}: [${r.retcode}] ${r.message}")
                when {
                    r.retcode == 0 -> {
                        store.setWorkshopDone(prefix, key)
                        RunBus.log("  ✔ ${t.name}: +${t.points} points")
                        items.add(RecordItem(wg.id, t.name, "+${t.points} points", ItemState.OK))
                    }
                    r.retcode == -100 -> {
                        sessionExpired = true
                        break
                    }
                    r.network -> items.add(RecordItem(wg.id, t.name, "Network error, will try next run", ItemState.WARN))
                    !complete -> {
                        // finish_task was accepted but the task still isn't complete: the player has to do it.
                        store.setWorkshopNeedsYou(prefix, key)
                        needYou++
                        val why = r.message.ifEmpty { "error ${r.retcode}" }
                        items.add(RecordItem(wg.id, t.name, "Needs you · ${t.progress}/${t.total} · $why", ItemState.INFO))
                    }
                    else -> {
                        val why = r.message.ifEmpty { "error ${r.retcode}" }
                        items.add(RecordItem(wg.id, t.name, "Points not claimed: $why", ItemState.WARN))
                    }
                }
            }
        } catch (e: OfflineException) {
            lostConnection = true
        }

        if (lostConnection) {
            // What was collected is saved. Keep the record, and let the rest continue when the internet is back.
            deferred = true
            RunBus.log("⚠ $label: lost the connection, the rest continues when it's back")
            items.add(RecordItem(wg.id, "Connection", "Lost the connection, will continue when it's back", ItemState.WARN))
            store.addRecord(RunRecord(System.currentTimeMillis(), wg.recordTask, trigger, "Paused: no connection", false, items))
            return false
        }

        if (sessionExpired) {
            store.setHealth(hoyolabExpired = true)
            RunBus.log("✖ $label: session expired")
            Notifier.alert(ctx, "Session expired", "Open Ergon and log in again to keep collecting.")
        }

        // Read the new total from the server (only if something was actually attempted).
        var total = act.points
        if (attempted > 0) {
            val (_, after) = WorkshopApi.index(cr, wg)
            if (after != null) {
                total = after.points
                saveInfo(wg, after)
            }
        }
        val gained = total - act.points

        // Spend points last, so what was just earned counts.
        val prefs = s.exchangePrefs(wg)
        var ex = ExchangeRun()
        var dr = DrawRun()
        var armed = false
        if (prefs.auto && !sessionExpired) {
            try {
                // DRAW mode claims nothing: the points only go into prize draws.
                val drawOnly = wg.canDraw && prefs.mode == ExchangeMode.DRAW
                if (!drawOnly) {
                    if (act.exchange) {
                        ex = autoExchange(wg, cr, act, total, prefs, items)
                        // A failed shop read leaves the alarm as it was.
                        if (!ex.failed) armRefresh(wg, if (ex.expired) 0L else ex.nextRefresh)
                        armed = true
                        if (ex.expired) {
                            store.setHealth(hoyolabExpired = true)
                            RunBus.log("✖ $label: session expired")
                            Notifier.alert(ctx, "Session expired", "Open Ergon and log in again to keep collecting.")
                        }
                    } else {
                        RunBus.log("• Auto exchange: the exchange isn't open right now")
                    }
                }
                val wantDraw = wg.canDraw && !ex.expired && (drawOnly || (prefs.drawWhenIdle && ex.idle))
                if (wantDraw) {
                    if (act.lottery) {
                        dr = autoDraw(wg, cr, act, items)
                        if (dr.expired) {
                            store.setHealth(hoyolabExpired = true)
                            RunBus.log("✖ $label: session expired")
                            Notifier.alert(ctx, "Session expired", "Open Ergon and log in again to keep collecting.")
                        }
                    } else {
                        RunBus.log("• Prize draw: the draw isn't open right now")
                    }
                } else if (wg.canDraw && prefs.drawWhenIdle && !ex.idle && !ex.expired) {
                    RunBus.log("• Prize draw: skipped, there is still a reward in the priority list to claim")
                }
                if (ex.claimed > 0 || dr.draws > 0) {
                    val (_, after) = WorkshopApi.index(cr, wg)
                    if (after != null) {
                        total = after.points
                        saveInfo(wg, after)
                    } else {
                        total -= ex.spent + dr.spent
                    }
                }
            } catch (e: OfflineException) {
                deferred = true
                RunBus.log("⚠ $label: lost the connection, exchange and draw continue when it's back")
                items.add(RecordItem(wg.id, "Exchange", "Lost the connection, will continue when it's back", ItemState.WARN))
            }
        }

        // No auto exchange (or no exchange open): no reason to wake up for a refresh.
        if (!armed && !sessionExpired) RefreshAlarm.cancel(ctx, store, wg)

        val spent = ex.spent + dr.spent
        val didSomething = attempted > 0 || ex.claimed > 0 || dr.draws > 0
        val spentText = if (spent > 0) " · $spent spent" else ""
        RunBus.log(
            when {
                !didSomething -> "Done. Nothing new to try."
                else -> "Done. +$gained points$spentText, $total in total" + if (needYou > 0) " · $needYou need you" else ""
            }
        )

        val now = System.currentTimeMillis()
        if (didSomething || force) {
            val exchangedText = (if (ex.claimed > 0) " · ${ex.claimed} exchanged" else "") +
                (if (dr.draws > 0) " · ${dr.draws} drawn" else "")
            val summary = when {
                items.isEmpty() -> "No tasks found"
                needYou > 0 -> "+$gained points$spentText$exchangedText · $total total · $needYou need you"
                else -> "+$gained points$spentText$exchangedText · $total total"
            }
            val ok = !sessionExpired && !ex.expired && !dr.expired && items.none { it.state == ItemState.ERROR || it.state == ItemState.WARN }
            store.addRecord(RunRecord(now, wg.recordTask, trigger, summary, ok, items))
        }
        if (gained > 0 && s.notify) Notifier.workshopPoints(ctx, wg, gained, total)
        if (ex.claimed > 0 && s.notify) Notifier.exchangeClaimed(ctx, wg, ex.names, ex.codes)
        if (dr.draws > 0 && s.notify) Notifier.prizeDraw(ctx, wg, dr.prizes, dr.codes)
        return sessionExpired || ex.expired || dr.expired
    }

    private fun saveInfo(wg: WorkshopGame, a: WorkshopAct) =
        store.saveWorkshopInfo(wg, WorkshopInfo(a.versionId, a.points, a.end, a.lottery, a.exchange, System.currentTimeMillis()))

    private data class ExchangeRun(
        val claimed: Int = 0,
        val spent: Int = 0,
        val expired: Boolean = false,
        val names: List<String> = emptyList(),
        val codes: List<String> = emptyList(),
        /** True when no reward of the priority list is left to claim, so spare points could go into a prize draw. */
        val idle: Boolean = true,
        /** Earliest refresh (epoch seconds, 0 = none) of a timed reward that would be claimed when it comes back. */
        val nextRefresh: Long = 0L,
        /** The shop couldn't be read, so nothing is known about it. */
        val failed: Boolean = false
    )

    private data class DrawRun(
        val draws: Int = 0,
        val spent: Int = 0,
        val expired: Boolean = false,
        val prizes: List<String> = emptyList(),
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
     * If a reward comes as a redeem code, it is redeemed right away through the gift-code API (when the second
     * sign-in step is done); otherwise the code is shown in the activity and the notification.
     */
    private suspend fun autoExchange(
        wg: WorkshopGame,
        cr: Credentials,
        act: WorkshopAct,
        startPoints: Int,
        prefs: ExchangePrefs,
        items: MutableList<RecordItem>
    ): ExchangeRun {
        RunBus.step("${wg.label}: exchange shop")
        val (listRes, list) = netPair { WorkshopApi.exchangeList(cr, act) }
        if (listRes.retcode == -100) return ExchangeRun(expired = true)
        if (listRes.retcode != 0) {
            val why = if (listRes.network) "network error" else "[${listRes.retcode}] ${listRes.message}"
            RunBus.log("✖ Auto exchange: couldn't load the shop ($why)")
            items.add(RecordItem(wg.id, "Exchange", "Couldn't load the shop, will try next run", ItemState.WARN))
            return ExchangeRun(idle = false, failed = true)
        }

        var points = startPoints
        var claimed = 0
        var spent = 0
        var expired = false
        val names = ArrayList<String>()
        val codes = ArrayList<String>()
        val claimedIds = HashSet<Int>()

        // Returns true if the request failed because of the network (so waiting on points makes no sense).
        suspend fun claim(a: ExchangeAward): Boolean {
            RunBus.step("${wg.label}: exchanging ${a.name}")
            ClaimPacer.pace()
            val o = netCall(idempotent = false, call = { WorkshopApi.exchange(cr, act, a.id) }, failed = { it.result.network })
            val r = o.result
            RunBus.log("  exchange #${a.id}: [${r.retcode}] ${r.message}")
            when {
                r.retcode == 0 -> {
                    claimed++
                    claimedIds.add(a.id)
                    points -= a.cost
                    spent += a.cost
                    names.add(a.name)
                    items.add(
                        RecordItem(wg.id, "Exchanged: ${a.name}", if (a.cost == 0) "Free" else "-${a.cost} points", ItemState.OK)
                    )
                    if (o.code.isNotEmpty()) redeemExchangeCode(wg, cr, o.code, a.name, items)?.let { codes.add(it) }
                }
                r.retcode == -100 -> expired = true
                r.network -> items.add(RecordItem(wg.id, a.name, "Network error, will try next run", ItemState.WARN))
                else -> {
                    val why = r.message.ifEmpty { "error ${r.retcode}" }
                    items.add(RecordItem(wg.id, a.name, "Not exchanged: $why", ItemState.WARN))
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
        for (id in prefs.priority) {
            val a = byId[id] ?: continue
            if (a.status == ExchangeAward.STATUS_CLAIMED || a.cost == 0) continue
            if (!a.claimable) {
                RunBus.log("• ${a.name}: not claimable now" + if (a.timed) " (on a refresh timer, checked again next run)" else "")
                continue
            }
            if (points < a.cost) {
                val missing = a.cost - points
                if (prefs.mode == ExchangeMode.WAIT) {
                    RunBus.log("• Waiting for ${a.name}: $missing more points needed ($points/${a.cost})")
                    items.add(RecordItem(wg.id, a.name, "Waiting for $missing more points · $points/${a.cost}", ItemState.INFO))
                    break
                }
                RunBus.log("• ${a.name}: costs ${a.cost}, have $points, moving on")
                continue
            }
            val network = claim(a)
            if (expired) break
            if (network && prefs.mode == ExchangeMode.WAIT) break
        }
        // Something in the list that can be claimed but wasn't (too expensive for now) means we are saving for it.
        val pending = list.any {
            it.id in prefs.priority && it.cost > 0 && it.status != ExchangeAward.STATUS_CLAIMED && it.claimable && it.id !in claimedIds
        }
        val watch = if (expired) 0L else nextRefreshToWatch(list, prefs, points, claimedIds)
        return ExchangeRun(claimed, spent, expired, names, codes, idle = !pending && !expired, nextRefresh = watch)
    }

    /**
     * The earliest refresh (epoch seconds, 0 = none) of a timed reward worth waking up for: free ones, and ones of
     * the priority list that the points cover and that aren't stuck behind a reward we are saving for.
     */
    private fun nextRefreshToWatch(list: List<ExchangeAward>, prefs: ExchangePrefs, points: Int, claimedIds: Set<Int>): Long {
        var next = 0L
        fun watch(a: ExchangeAward) {
            if (a.nextRefresh > 0 && (next == 0L || a.nextRefresh < next)) next = a.nextRefresh
        }
        for (a in list) {
            if (a.cost == 0 && a.status != ExchangeAward.STATUS_CLAIMED) watch(a)
        }
        val byId = list.associateBy { it.id }
        for (id in prefs.priority) {
            val a = byId[id] ?: continue
            if (a.status == ExchangeAward.STATUS_CLAIMED || a.cost == 0 || a.id in claimedIds) continue
            if (a.timed) {
                if (a.cost <= points) watch(a)
                continue
            }
            if (a.claimable && points < a.cost && prefs.mode == ExchangeMode.WAIT) break
        }
        return next
    }

    /** Sets (or clears) the alarm that wakes the app just before a timed reward refreshes. */
    private fun armRefresh(wg: WorkshopGame, nextRefresh: Long) {
        if (nextRefresh <= 0L) {
            RefreshAlarm.cancel(ctx, store, wg)
            return
        }
        val inSec = nextRefresh - System.currentTimeMillis() / 1000
        val text = when {
            inSec >= 86_400 -> "${inSec / 86_400}d ${inSec % 86_400 / 3_600}h"
            inSec >= 3_600 -> "${inSec / 3_600}h ${inSec % 3_600 / 60}m"
            else -> "${(inSec / 60).coerceAtLeast(0)}m"
        }
        RunBus.log("• ${wg.label}: a timed reward comes back in $text, an alarm will claim it right away")
        RefreshAlarm.schedule(ctx, store, wg, maxOf(nextRefresh * 1000 - REFRESH_LEAD_MS, System.currentTimeMillis() + 1_000))
    }

    /**
     * Claims rewards that come back on a refresh timer. Their stock is first come, first served, so this runs from
     * an alarm set a few seconds before the refresh: it keeps looking until the reward is claimable and claims it
     * at once. Always returns a report (null means "another run is busy" to the caller).
     */
    private suspend fun timedExchange(wg: WorkshopGame): RunReport {
        val report = RunReport(System.currentTimeMillis(), "refresh", emptyMap(), 0, 0, 0, emptyList())
        val s = store.settings.value
        val cr = store.creds.value
        val prefs = s.exchangePrefs(wg)
        val drawOnly = wg.canDraw && prefs.mode == ExchangeMode.DRAW
        if (!prefs.auto || drawOnly || wg.id !in s.workshopGames || !cr.hasHoyolab || cr.hoyolabExpired) {
            RefreshAlarm.cancel(ctx, store, wg)
            RunBus.log("• ${wg.label}: auto exchange isn't active, nothing to claim")
            return report
        }

        RunBus.liveGame(wg.id)
        val items = ArrayList<RecordItem>()
        val names = ArrayList<String>()
        val codes = ArrayList<String>()
        var claimed = 0
        var spent = 0
        var next = 0L
        var expired = false
        var retryLater = false
        for (attempt in 1..REFRESH_TRIES) {
            RunBus.step("${wg.label}: checking the exchange ($attempt of $REFRESH_TRIES)")
            val (indexRes, act) = netPair { WorkshopApi.index(cr, wg) }
            if (act == null) {
                retryLater = indexRes.network
                break
            }
            if (!act.exchange || !act.bound) break
            val round = ArrayList<RecordItem>()
            val ex = autoExchange(wg, cr, act, act.points, prefs, round)
            items.addAll(round.filter { it.state == ItemState.OK || it.state == ItemState.WARN })
            claimed += ex.claimed
            spent += ex.spent
            names.addAll(ex.names)
            codes.addAll(ex.codes)
            if (ex.failed) {
                retryLater = true
                break
            }
            next = ex.nextRefresh
            if (ex.expired) {
                expired = true
                break
            }
            // A refresh that is seconds away (or just passed without the list changing yet): look again shortly.
            if (next <= 0L || next - System.currentTimeMillis() / 1000 > REFRESH_SOON_SEC) break
            RunBus.step("${wg.label}: waiting for the refresh")
            delay(3_000)
        }

        if (expired) {
            store.setHealth(hoyolabExpired = true)
            RunBus.log("✖ ${wg.label}: session expired")
            Notifier.alert(ctx, "Session expired", "Open Ergon and log in again to keep collecting.")
        }
        if (retryLater) {
            RunBus.log("⚠ ${wg.label}: couldn't read the exchange, trying again in 5 minutes")
            RefreshAlarm.schedule(ctx, store, wg, System.currentTimeMillis() + REFRESH_RETRY_MS)
        } else if (next > 0L && next - System.currentTimeMillis() / 1000 <= REFRESH_SOON_SEC) {
            // Due, but the server still hasn't released it after all the tries: look again in a while, not in a loop.
            RunBus.log("⚠ ${wg.label}: a reward is due but not available yet, checking again in 10 minutes")
            RefreshAlarm.schedule(ctx, store, wg, System.currentTimeMillis() + REFRESH_STUCK_MS)
        } else {
            armRefresh(wg, if (expired) 0L else next)
        }
        if (claimed > 0) {
            val (_, after) = WorkshopApi.index(cr, wg)
            if (after != null) saveInfo(wg, after)
        }
        if (claimed > 0 || items.isNotEmpty()) {
            val spentText = if (spent > 0) " · $spent spent" else ""
            val summary = if (claimed > 0) "$claimed exchanged on refresh$spentText" else "Nothing claimed on refresh"
            val ok = !expired && items.none { it.state == ItemState.ERROR || it.state == ItemState.WARN }
            store.addRecord(RunRecord(System.currentTimeMillis(), wg.recordTask, "refresh", summary, ok, items))
        }
        if (claimed > 0 && s.notify) Notifier.exchangeClaimed(ctx, wg, names, codes)
        RunBus.liveGame(null)
        return report
    }

    /**
     * Prize draw: as many draws as the points and the draw limit allow, each one 5 seconds after the last request.
     * A prize that comes as a redeem code is redeemed right away, like an exchange code.
     */
    private suspend fun autoDraw(wg: WorkshopGame, cr: Credentials, act: WorkshopAct, items: MutableList<RecordItem>): DrawRun {
        RunBus.step("${wg.label}: prize draw")
        val (infoRes, info) = netPair { WorkshopApi.lotteryInfo(cr, act) }
        if (infoRes.retcode == -100) return DrawRun(expired = true)
        if (info == null) {
            val why = if (infoRes.network) "network error" else "[${infoRes.retcode}] ${infoRes.message}"
            RunBus.log("✖ Prize draw: couldn't load it ($why)")
            items.add(RecordItem(wg.id, "Prize draw", "Couldn't load the draw, will try next run", ItemState.WARN))
            return DrawRun()
        }
        val n = info.affordable
        if (n <= 0) {
            RunBus.log("• Prize draw: nothing to draw (${info.point} points, ${info.cost} per draw, ${info.count}/${info.limit} used)")
            return DrawRun()
        }

        RunBus.log("Prize draw: ${info.count}/${info.limit} draws used today, $n to make")
        var draws = 0
        var spent = 0
        var expired = false
        val prizes = ArrayList<String>()
        val codes = ArrayList<String>()
        for (i in 1..n) {
            RunBus.step("${wg.label}: prize draw $i of $n")
            ClaimPacer.pace()
            val o = netCall(idempotent = false, call = { WorkshopApi.draw(cr, act) }, failed = { it.result.network })
            val r = o.result
            RunBus.log("  draw $i: [${r.retcode}] ${r.message}")
            when {
                r.retcode == 0 -> {
                    draws++
                    spent += info.cost
                    prizes.add(o.name)
                    items.add(RecordItem(wg.id, "Prize draw: ${o.name}", "-${info.cost} points", ItemState.OK))
                    if (o.code.isNotEmpty()) redeemExchangeCode(wg, cr, o.code, o.name, items)?.let { codes.add(it) }
                }
                r.retcode == -100 -> {
                    expired = true
                    break
                }
                r.network -> {
                    items.add(RecordItem(wg.id, "Prize draw", "Network error, will try next run", ItemState.WARN))
                    break
                }
                else -> {
                    val why = r.message.ifEmpty { "error ${r.retcode}" }
                    items.add(RecordItem(wg.id, "Prize draw", "Not drawn: $why", ItemState.WARN))
                    break
                }
            }
        }
        return DrawRun(draws, spent, expired, prizes, codes)
    }

    /**
     * Redeems a code an exchange handed out, through the same gift-code API as the code pages.
     * Returns null when it was redeemed, otherwise a "reward: code" line so the player can use it by hand.
     */
    private suspend fun redeemExchangeCode(
        wg: WorkshopGame,
        cr: Credentials,
        code: String,
        reward: String,
        items: MutableList<RecordItem>
    ): String? {
        val game = wg.game
        val manual = "$reward: $code"
        fun keep(why: String): String {
            RunBus.log("  ⚠ code $code not redeemed: $why")
            items.add(RecordItem(wg.id, code, "Code for $reward · not redeemed: $why", ItemState.WARN, mono = true))
            return manual
        }

        val rc = game.redeem ?: return keep("redeeming isn't supported for ${game.title}")
        if (!cr.hasLogin || cr.loginExpired) return keep("finish the second sign-in step to redeem automatically")
        val acc = HoyoApi.discover(rc.gameBiz, cr).firstOrNull() ?: return keep("no linked ${game.title} account found")

        RunBus.step("${wg.label}: redeeming the code for $reward")
        ClaimPacer.pace()
        var res = HoyoApi.redeem(game, code, acc, cr)
        if (res.rateLimited) {
            RunBus.log("  ⚠ rate limited, waiting 30s")
            delay(RATE_LIMIT_WAIT_MS)
            ClaimPacer.pace()
            res = HoyoApi.redeem(game, code, acc, cr)
        }
        if (!res.rateLimited && !res.networkError) store.setLastRedeemCall(System.currentTimeMillis())
        return when {
            res.success -> {
                RunBus.log("  ✔ code $code redeemed for ${acc.nickname.ifEmpty { acc.uid }}")
                items.add(RecordItem(wg.id, code, "Redeemed in game: $reward", ItemState.OK, mono = true))
                null
            }
            res.tokenExpired -> {
                store.setHealth(loginExpired = true)
                keep("sign-in expired")
            }
            else -> keep(res.message)
        }
    }
}

/**
 * Spaces claim requests out: never two within 5 seconds. One instance for everything, so it holds across
 * games (a request for Star Rail never follows right behind one for Genshin) and across runs.
 */
private object ClaimPacer {
    private const val MIN_GAP_MS = 5_000L
    private var last = 0L

    suspend fun pace() {
        if (last != 0L) {
            val gap = MIN_GAP_MS - (System.nanoTime() - last) / 1_000_000L
            if (gap > 0) delay(gap)
        }
        last = System.nanoTime()
    }
}
