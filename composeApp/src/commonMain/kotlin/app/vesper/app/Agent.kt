package app.vesper.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.serialization.Serializable

// ── What the model returns ───────────────────────────────────────────────

@Serializable
data class AgentStep(val command: String, val description: String = "", val destructive: Boolean = false)

@Serializable
data class AgentPlan(val explanation: String, val steps: List<AgentStep> = emptyList())

@Serializable
data class AgentOption(val title: String, val detail: String = "")

@Serializable
data class ErrorExplanation(
    /** What went wrong, in two or three sentences. */
    val summary: String,
    /** How to fix it, one or two sentences. */
    val fix: String = "",
    /** File the fix changes, relative to the working directory; empty when the fix isn't a code change. */
    val file: String = "",
    /** The change as unified-diff lines (" ", "-" or "+" first), without file headers. */
    val diff: String = "",
    /** Commands that fix it or check the fix. */
    val commands: List<AgentStep> = emptyList(),
    val alternatives: List<AgentOption> = emptyList(),
)

/** A reply and what it cost. */
class AgentReply<T>(val value: T, val inputTokens: Long, val outputTokens: Long)

/** A request the agent could not answer; [message] is shown to the user as is. */
class AgentException(message: String, val needsKey: Boolean = false) : Exception(message)

/** Claude API access. The key never leaves this service except in requests to the API. */
interface AgentService {
    /** The key's last characters for display, "ANTHROPIC_API_KEY" when it comes from the environment, null when there is none. Blocking. */
    fun keyHint(): String?
    /** Stores [key] (Keychain on macOS, an owner-only file elsewhere). Blocking. */
    fun saveKey(key: String): Boolean
    fun clearKey()
    suspend fun plan(model: String, ask: String, context: String): AgentReply<AgentPlan>
    suspend fun explain(model: String, context: String): AgentReply<ErrorExplanation>
}

// ── Settings › AI agent ──────────────────────────────────────────────────

enum class AgentModel(val id: String, val title: String) {
    Opus("claude-opus-5-5", "Claude Opus 5.5"),
    Sonnet("claude-sonnet-5-5", "Claude Sonnet 5.5 · fast"),
    Haiku("claude-haiku-5-5", "Claude Haiku 5.5 · fastest"),
    ;

    companion object {
        fun of(id: String): AgentModel = entries.firstOrNull { it.id == id } ?: Opus
    }
}

@Serializable
enum class AgentRunMode(val title: String) { Ask("Ask every time"), SafeSteps("Run safe steps") }

@Serializable
data class AgentUsage(val month: String = "", val requests: Int = 0, val inputTokens: Long = 0, val outputTokens: Long = 0)

val DefaultRedactPatterns = listOf("*_KEY", "*_TOKEN", "*_SECRET", "*PASSWORD*", "DATABASE_URL", ".env", "~/.aws/credentials")

// ── Safety ───────────────────────────────────────────────────────────────

private val DestructivePatterns = listOf(
    Regex("""(^|[;&|(]\s*|\bsudo\s+|\bxargs\s+)(rm|rmdir|shred|unlink)(\s|$)"""),
    Regex("""\b(kill|pkill|killall)\b"""),
    Regex("""\b(DROP|TRUNCATE)\s+(TABLE|DATABASE|SCHEMA|INDEX)\b|\bDELETE\s+FROM\b""", RegexOption.IGNORE_CASE),
    Regex("""\bgit\s+push\b.*(\s-f\b|--force|\+\S)"""),
    Regex("""\bgit\s+(reset\s+--hard|clean\s+-\S*f|checkout\s+--\s|restore\s|branch\s+-D|stash\s+(drop|clear))"""),
    Regex("""\b(mkfs\S*|dd\s+if=|diskutil\s+erase\S*|shutdown|reboot|halt)\b"""),
    Regex("""\b(chmod|chown)\s+-R\b"""),
    Regex("""(^|\s)>\s*/(dev|etc)/|\bsudo\b"""),
    Regex("""\b(docker\s+(system\s+prune|volume\s+(rm|prune)|rm\s+-f)|kubectl\s+delete|terraform\s+destroy|helm\s+uninstall)\b"""),
    Regex("""\bfind\b.*\s-delete\b"""),
)

/** True when [command] deletes or overwrites data, kills processes, rewrites git history or changes the system. */
fun isDestructive(command: String): Boolean = DestructivePatterns.any { it.containsMatchIn(command) }

// ── Redaction ────────────────────────────────────────────────────────────

/** Well-known secret formats, with what each one is for the share dialog. */
private val TokenPatterns = listOf(
    "Anthropic key" to Regex("""sk-ant-[A-Za-z0-9_-]{8,}"""),
    "GitHub token" to Regex("""\b(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}|\bgithub_pat_[A-Za-z0-9_]{20,}"""),
    "AWS key" to Regex("""\b(AKIA|ASIA)[0-9A-Z]{16}\b"""),
    "Slack token" to Regex("""\bxox[abprs]-[A-Za-z0-9-]{10,}"""),
    "Stripe key" to Regex("""\b(sk|rk|pk)_(live|test)_[A-Za-z0-9]{16,}"""),
    "JWT" to Regex("""\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}"""),
    "bearer token" to Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/-]{16,}=*"""),
    "private key" to Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?(-----END [A-Z ]*PRIVATE KEY-----|$)"""),
    // user:password@ in URLs
    "URL password" to Regex("""(?<=://)[^/\s:@]+:[^/\s@]+(?=@)"""),
)

private fun isPathPattern(p: String) = p.startsWith(".") || p.startsWith("~") || p.contains('/')

private fun nameRegex(pattern: String): Regex {
    val body = pattern.split('*').joinToString("[A-Za-z0-9_]*") { Regex.escape(it) }
    // NAME=value, NAME: value, "NAME": "value"
    return Regex("""(?i)(\b$body\b["']?[ \t]*(?:=|:)[ \t]*)(["']?)([^\s"',;]+)""")
}

/** [text] with secrets replaced by "[redacted]": values of names matching [patterns], and well-known token formats. */
fun redact(text: String, patterns: List<String>): String = redactSecrets(text, patterns).text

/** Text after redaction: how many values were replaced, and the names or kinds of secret they were. */
class Redacted(val text: String, val count: Int, val names: List<String>)

/** Like [redact], replacing each secret with [mark] and counting them. */
fun redactSecrets(text: String, patterns: List<String>, mark: String = "[redacted]"): Redacted {
    var out = text
    var count = 0
    val names = LinkedHashSet<String>()
    for (p in patterns) {
        if (p.isBlank() || isPathPattern(p)) continue
        out = nameRegex(p.trim()).replace(out) { m ->
            count++
            IdentifierRegex.find(m.groupValues[1])?.let { names += it.value }
            m.groupValues[1] + m.groupValues[2] + mark
        }
    }
    for ((label, r) in TokenPatterns) {
        out = r.replace(out) {
            count++
            names += label
            mark
        }
    }
    return Redacted(out, count, names.toList())
}

private val IdentifierRegex = Regex("""[A-Za-z0-9_]+""")

/** The path pattern [command] reads (e.g. ".env", "~/.aws/credentials"), whose output must not be sent at all. */
fun secretFileIn(command: String, patterns: List<String>): String? = patterns.filter(::isPathPattern).firstOrNull { p ->
    val name = p.trim().removePrefix("~/").trimEnd('/')
    name.isNotEmpty() && Regex("""(^|[\s/'"=<])${Regex.escape(name)}(\b|$)""").containsMatchIn(command)
}

// ── Context ──────────────────────────────────────────────────────────────

class BlockContext(val command: String, val exitCode: Int?, val output: String)

/** What the agent is told about the session; every field is already redacted. */
class AgentContext(
    val system: String,
    val shell: String,
    val cwd: String,
    val git: String?,
    val blocks: List<BlockContext>,
    val monitor: String?,
) {
    /** "cwd, git, last 3 blocks, Monitor", for the plan card. */
    val used: String get() = buildList {
        add("cwd")
        if (git != null) add("git status")
        if (blocks.isNotEmpty()) add(if (blocks.size == 1) "last block" else "last ${blocks.size} blocks")
        if (monitor != null) add("Monitor › Ports")
    }.joinToString(", ")

    fun render(): String = buildString {
        appendLine("<session>")
        appendLine("System: $system")
        appendLine("Shell: $shell")
        appendLine("Working directory: $cwd")
        if (git != null) appendLine("Git: $git")
        if (monitor != null) appendLine("Listening ports and busy processes:\n$monitor")
        if (blocks.isNotEmpty()) {
            appendLine("Recent commands, oldest first:")
            for (b in blocks) {
                append("$ ").append(b.command)
                if (b.exitCode != null) append("   [exit ${b.exitCode}]")
                appendLine()
                if (b.output.isNotBlank()) appendLine(b.output)
            }
        }
        append("</session>")
    }

    companion object {
        /** About 4k tokens of output in all. */
        const val MaxOutputChars = 16_000
        const val MaxLinesPerBlock = 80

        /** Last lines of [output], so the end of a long log (where errors usually are) survives. */
        fun trimOutput(output: String, budget: Int): String {
            val lines = output.trimEnd().lines()
            var kept = lines.takeLast(MaxLinesPerBlock).joinToString("\n")
            if (kept.length > budget) kept = "…" + kept.takeLast(budget)
            else if (lines.size > MaxLinesPerBlock) kept = "… (${lines.size - MaxLinesPerBlock} earlier lines)\n$kept"
            return kept
        }
    }
}

// ── Live state ───────────────────────────────────────────────────────────

enum class StepStatus { Pending, Running, Done, Failed }

class StepState(step: AgentStep) {
    var command by mutableStateOf(step.command)
    val description = step.description
    private val modelSaysDestructive = step.destructive
    var status by mutableStateOf(StepStatus.Pending)
    var editing by mutableStateOf(false)
    /** Completed when the block this step started finishes: true when it succeeded. */
    var finished: CompletableDeferred<Boolean>? = null

    val destructive: Boolean get() = modelSaysDestructive || isDestructive(command)
}

enum class TurnStatus { Thinking, Ready, Failed }

/** A "# …" question in a pane and the plan the agent answered with. */
class AgentTurn(val ask: String, val at: Long) {
    var status by mutableStateOf(TurnStatus.Thinking)
    var explanation by mutableStateOf("")
    var used by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var needsKey by mutableStateOf(false)
    /** Running the remaining steps one after another. */
    var running by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)
    val steps = mutableStateListOf<StepState>()
    var job: Job? = null
}

enum class ExplainStatus { Thinking, Ready, Failed }

/** "Explain error" for one failed block, shown in the right panel. */
class ExplainState(val pane: Pane, val blockId: Long, val command: String) {
    var status by mutableStateOf(ExplainStatus.Thinking)
    var result by mutableStateOf<ErrorExplanation?>(null)
    var error by mutableStateOf<String?>(null)
    var needsKey by mutableStateOf(false)
    var job: Job? = null
}

/** A shell script that runs [steps] in order and stops at the first failure. */
fun planScript(ask: String, steps: List<String>): String = buildString {
    appendLine("#!/bin/sh")
    appendLine("# ${ask.replace('\n', ' ')}")
    appendLine("set -e")
    steps.forEach { appendLine(it) }
}
