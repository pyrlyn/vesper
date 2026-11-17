package app.vesper.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.vesper.terminal.Block
import app.vesper.terminal.BlockStatus
import app.vesper.util.localDay
import app.vesper.util.nowMillis
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the right panel shows next to a terminal tab. */
enum class SidePanel { Monitor, Agent }

/** "#" questions, plans and error explanations. UI state lives on the main thread. */
class AgentController(private val state: AppState, private val scope: CoroutineScope) {
    private val services get() = state.services
    private val settings get() = state.settings

    /** "…a1b2" or "ANTHROPIC_API_KEY"; null when no key is set. */
    var keyHint by mutableStateOf<String?>(null)
        private set
    var keyLoaded by mutableStateOf(false)
        private set
    var explain by mutableStateOf<ExplainState?>(null)
        private set

    init {
        scope.launch(Dispatchers.IO) {
            val hint = runCatching { services.agent.keyHint() }.getOrNull()
            withContext(Dispatchers.Main) {
                keyHint = hint
                keyLoaded = true
            }
        }
    }

    fun saveKey(key: String, done: (Boolean) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val ok = runCatching { services.agent.saveKey(key) }.getOrDefault(false)
            val hint = if (ok) runCatching { services.agent.keyHint() }.getOrNull() else keyHint
            withContext(Dispatchers.Main) {
                keyHint = hint
                done(ok)
            }
        }
    }

    fun clearKey() {
        scope.launch(Dispatchers.IO) {
            runCatching { services.agent.clearKey() }
            val hint = runCatching { services.agent.keyHint() }.getOrNull()
            withContext(Dispatchers.Main) { keyHint = hint }
        }
    }

    // ── "#" questions ────────────────────────────────────────────────────

    fun ask(pane: Pane, text: String) {
        val ask = text.trim().removePrefix("#").trim()
        if (ask.isEmpty()) return
        pane.agentTurn?.job?.cancel()
        val turn = AgentTurn(ask, nowMillis())
        pane.agentTurn = turn
        state.updateSettings { it.copy(agentRecent = (listOf(ask) + it.agentRecent.filter { r -> r != ask }).take(6)) }
        turn.job = scope.launch {
            try {
                val context = gather(pane, blocks = null)
                turn.used = context.used
                val reply = withContext(Dispatchers.IO) { services.agent.plan(settings.agentModel, ask, context.render()) }
                count(reply)
                turn.explanation = reply.value.explanation
                turn.steps.addAll(reply.value.steps.filter { it.command.isNotBlank() }.map(::StepState))
                turn.status = TurnStatus.Ready
                if (settings.agentRunMode == AgentRunMode.SafeSteps) runRemaining(pane, turn, safeOnly = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                turn.error = e.message.takeIf { e is AgentException } ?: "Something went wrong: ${e::class.simpleName}"
                turn.needsKey = (e as? AgentException)?.needsKey == true
                turn.status = TurnStatus.Failed
            }
        }
    }

    fun cancel(pane: Pane) {
        pane.agentTurn?.job?.cancel()
        pane.agentTurn = null
    }

    /** Why [step] can't run now, or null when it can. */
    fun blockedReason(pane: Pane, step: StepState): String? = when {
        step.destructive && !settings.agentAllowDestructive -> "Destructive steps are off in Settings › AI agent"
        pane.session.currentBlock() != null -> "Wait for the running command to finish"
        pane.session.exited -> "This session has ended"
        else -> null
    }

    fun runStep(pane: Pane, turn: AgentTurn, step: StepState) {
        if (turn.running) return
        turn.notice = blockedReason(pane, step)
        if (turn.notice != null) return
        start(pane, step)
    }

    /** Runs the pending steps in order; stops at a failure, a step that is blocked, or (with [safeOnly]) a destructive one. */
    fun runRemaining(pane: Pane, turn: AgentTurn, safeOnly: Boolean = false) {
        if (turn.running) return
        turn.notice = null
        turn.running = true
        turn.job = scope.launch {
            try {
                for (step in turn.steps.toList()) {
                    if (step.status == StepStatus.Done) continue
                    if (safeOnly && step.destructive) break
                    // Wait briefly for a command that is still finishing.
                    withTimeoutOrNull(5_000) { while (pane.session.currentBlock() != null) kotlinx.coroutines.delay(100) }
                    blockedReason(pane, step)?.let {
                        turn.notice = it
                        return@launch
                    }
                    val finished = start(pane, step)
                    if (!finished.await()) {
                        turn.notice = "Stopped: step ${turn.steps.indexOf(step) + 1} failed"
                        return@launch
                    }
                }
            } finally {
                turn.running = false
            }
        }
    }

    private fun start(pane: Pane, step: StepState): CompletableDeferred<Boolean> {
        val done = CompletableDeferred<Boolean>()
        step.finished = done
        step.editing = false
        step.status = StepStatus.Running
        pane.session.submit(step.command, byAgent = true)
        return done
    }

    /** Called on the main thread when a block in [pane] finishes. */
    fun onBlockFinished(pane: Pane, block: Block) {
        if (!block.byAgent) return
        val turn = pane.agentTurn ?: return
        val step = turn.steps.firstOrNull { it.status == StepStatus.Running && it.command.trim() == block.command }
            ?: turn.steps.firstOrNull { it.status == StepStatus.Running }
            ?: return
        val ok = block.status == BlockStatus.Success
        step.status = if (ok) StepStatus.Done else StepStatus.Failed
        step.finished?.complete(ok)
    }

    // ── Explain error ────────────────────────────────────────────────────

    fun explain(pane: Pane, block: Block) {
        explain?.job?.cancel()
        val ex = ExplainState(pane, block.id, block.command)
        explain = ex
        state.sidePanel = SidePanel.Agent
        if (!settings.showMonitor) state.updateSettings { it.copy(showMonitor = true) }
        ex.job = scope.launch {
            try {
                val context = gather(pane, blocks = listOf(block))
                val reply = withContext(Dispatchers.IO) { services.agent.explain(settings.agentExplainModel, context.render()) }
                count(reply)
                ex.result = reply.value
                ex.status = ExplainStatus.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ex.error = e.message.takeIf { e is AgentException } ?: "Something went wrong: ${e::class.simpleName}"
                ex.needsKey = (e as? AgentException)?.needsKey == true
                ex.status = ExplainStatus.Failed
            }
        }
    }

    /** ⌘E: explains the newest failed block in the focused pane; false when there is none. */
    fun explainLastFailure(): Boolean {
        val pane = state.focusedPane ?: return false
        val block = pane.session.blocksSnapshot().lastOrNull { it.status == BlockStatus.Failed } ?: return false
        explain(pane, block)
        return true
    }

    fun closeExplain() {
        explain?.job?.cancel()
        explain = null
    }

    /** Runs a command the explanation suggested, in the pane that failed. */
    fun runFix(step: AgentStep): String? {
        val ex = explain ?: return null
        val s = StepState(step)
        val reason = blockedReason(ex.pane, s)
        if (reason == null) ex.pane.session.submit(step.command, byAgent = true)
        return reason
    }

    // ── Context ──────────────────────────────────────────────────────────

    /** Session context for a request, redacted. [blocks] null means the last few finished ones. */
    private suspend fun gather(pane: Pane, blocks: List<Block>?): AgentContext {
        val s = settings
        val session = pane.session
        val chosen = when {
            blocks != null -> blocks
            !s.agentSendOutput -> emptyList()
            else -> session.blocksSnapshot().filter { it.command.isNotEmpty() && !it.isRunning }.takeLast(3)
        }
        val ports = if (s.agentSendMonitor) runCatching { services.monitor.listeningPorts() }.getOrDefault(emptyList()) else emptyList()
        return withContext(Dispatchers.Default) {
            val budget = AgentContext.MaxOutputChars / chosen.size.coerceAtLeast(1)
            val blockContext = chosen.map { b ->
                val secret = secretFileIn(b.command, s.redactPatterns)
                val output = if (secret != null) {
                    "[output not sent: it reads $secret]"
                } else {
                    val text = synchronized(b.terminal.lock) { b.terminal.plainText() }
                    redact(AgentContext.trimOutput(text, budget), s.redactPatterns)
                }
                BlockContext(redact(b.command, s.redactPatterns), b.exitCode, output)
            }
            val ctx = pane.context
            val git = ctx.gitBranch?.takeIf { s.agentSendGit }?.let { branch ->
                buildList {
                    add("branch $branch")
                    if (ctx.ahead > 0) add("${ctx.ahead} ahead")
                    if (ctx.behind > 0) add("${ctx.behind} behind")
                    add(if (ctx.changes == 0) "clean" else "${ctx.changes} changed files")
                }.joinToString(", ")
            }
            val monitor = if (s.agentSendMonitor) {
                val busy = services.monitor.snapshot.value.processes.sortedByDescending { it.cpu }.take(5)
                buildList {
                    ports.take(20).forEach { add("port ${it.port}/${it.protocol} ${it.process} (pid ${it.pid})") }
                    busy.forEach { add("process ${it.name} (pid ${it.pid}) ${it.cpu.roundToInt()}% CPU") }
                }.joinToString("\n").ifEmpty { null }
            } else {
                null
            }
            AgentContext(
                system = services.systemInfo.substringBefore(" · Java"),
                shell = session.shellName,
                cwd = services.displayPath(session.cwd),
                git = git,
                blocks = blockContext,
                monitor = monitor,
            )
        }
    }

    private fun count(reply: AgentReply<*>) {
        val month = monthKey(localDay(nowMillis()))
        state.updateSettings { s ->
            val u = s.agentUsage.takeIf { it.month == month } ?: AgentUsage(month)
            s.copy(agentUsage = u.copy(requests = u.requests + 1, inputTokens = u.inputTokens + reply.inputTokens, outputTokens = u.outputTokens + reply.outputTokens))
        }
    }
}

/** "2026-11" for a local epoch day. */
fun monthKey(epochDay: Long): String {
    val (y, m) = civil(epochDay)
    return "$y-${m.toString().padStart(2, '0')}"
}

/** "Dec 1": when the month after [monthKey] starts. */
fun nextMonthStart(monthKey: String): String {
    val m = monthKey.substringAfter('-').toIntOrNull() ?: return ""
    val names = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    return "${names[m % 12]} 1"
}

/** Year and month (1–12) of an epoch day (days since 1970-01-01). */
private fun civil(epochDay: Long): Pair<Long, Int> {
    val z = epochDay + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
    return (yoe + era * 400 + if (m <= 2) 1 else 0) to m
}
