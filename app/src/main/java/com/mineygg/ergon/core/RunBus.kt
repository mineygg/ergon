package com.mineygg.ergon.core

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What's happening right now, for the progress visuals on the home screen. */
data class Live(
    val game: String? = null, // game currently being processed
    val statuses: Map<String, String> = emptyMap(), // check-in: gameId -> CheckinStatus name
    val claimed: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val detail: String = ""
)

enum class Task(val label: String) {
    CHECKIN("Daily check-in"),
    REDEEM("Gift codes"),
    WORKSHOP("Machine Workshop")
}

/** Live state of the current run, shared between the worker, the UI and the runner. */
object RunBus {
    private val _active = MutableStateFlow<Task?>(null)
    private val _running = MutableStateFlow(false)
    private val _status = MutableStateFlow("")
    private val _log = MutableStateFlow<List<String>>(emptyList())
    private val _live = MutableStateFlow(Live())
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    @Volatile private var job: Job? = null

    /** The coroutine running the current task, so the Stop button can cancel it. */
    fun attach(j: Job?) {
        job = j
    }

    fun stop() {
        job?.cancel()
    }

    val active: StateFlow<Task?> = _active.asStateFlow()
    val running: StateFlow<Boolean> = _running.asStateFlow()
    val status: StateFlow<String> = _status.asStateFlow()
    val log: StateFlow<List<String>> = _log.asStateFlow()
    val live: StateFlow<Live> = _live.asStateFlow()

    fun begin(task: Task, trigger: String) {
        _active.value = task
        _running.value = true
        _status.value = "Starting…"
        _live.value = Live()
        _log.update { (it + "──── ${task.label} · $trigger · ${LocalTime.now().format(fmt)} ────").takeLast(600) }
    }

    fun end() {
        job = null
        _live.value = Live()
        _active.value = null
        _running.value = false
        _status.value = ""
    }

    fun restore(lines: List<String>) {
        if (_log.value.isEmpty()) _log.value = lines
    }

    fun log(msg: String) {
        val line = "${LocalTime.now().format(fmt)}  $msg"
        _log.update { (it + line).takeLast(600) }
    }

    fun liveGame(id: String?) = _live.update { it.copy(game = id) }
    fun liveStatus(id: String, status: String) = _live.update { it.copy(statuses = it.statuses + (id to status)) }
    fun liveCounts(claimed: Int, skipped: Int, failed: Int) =
        _live.update { it.copy(claimed = claimed, skipped = skipped, failed = failed) }
    fun liveDetail(text: String) = _live.update { it.copy(detail = text) }

    /** Sets the one-line status shown on the home screen and logs it. */
    fun step(msg: String) {
        _status.value = msg
        log(msg)
    }
}
