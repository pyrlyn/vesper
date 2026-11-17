package app.vesper

import app.vesper.app.AgentContext
import app.vesper.app.DefaultRedactPatterns
import app.vesper.app.isDestructive
import app.vesper.app.monthKey
import app.vesper.app.nextMonthStart
import app.vesper.app.redact
import app.vesper.app.secretFileIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentTest {
    private val p = DefaultRedactPatterns

    @Test
    fun redactsValuesOfMatchingNames() {
        assertEquals("export STRIPE_KEY=[redacted]", redact("export STRIPE_KEY=sk_live_abc123", p))
        assertEquals("GITHUB_TOKEN: \"[redacted]\"", redact("GITHUB_TOKEN: \"abcdef\"", p))
        assertEquals("DATABASE_URL=[redacted] next", redact("DATABASE_URL=postgres://u:pw@db/x next", p))
        assertEquals("db_password = [redacted]", redact("db_password = hunter2", p))
        // A prompt with no value on the line keeps the next line intact.
        assertEquals("Password:\nnext line", redact("Password:\nnext line", p))
    }

    @Test
    fun redactsWellKnownTokens() {
        val out = redact("key sk-ant-api03-abcdefghijklmnop and ghp_abcdefghijklmnopqrstuvwxyz0123 AKIAABCDEFGHIJKLMNOP", p)
        assertFalse("sk-ant-api03" in out)
        assertFalse("ghp_" in out)
        assertFalse("AKIA" in out)
        assertEquals("https://[redacted]@host/x", redact("https://user:secret@host/x", emptyList()))
        assertEquals("[redacted]", redact("-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----", emptyList()))
        assertEquals("plain output stays", redact("plain output stays", p))
    }

    @Test
    fun withholdsOutputOfSecretFiles() {
        assertEquals(".env", secretFileIn("cat .env", p))
        assertEquals(".env", secretFileIn("grep FOO ./.env", p))
        assertEquals("~/.aws/credentials", secretFileIn("less ~/.aws/credentials", p))
        assertNull(secretFileIn("cat .envrc", p))
        assertNull(secretFileIn("ls -la", p))
    }

    @Test
    fun flagsDestructiveCommands() {
        listOf(
            "rm -rf node_modules", "kill -9 48211", "pkill node", "git push --force", "git push -f origin main",
            "git reset --hard HEAD~1", "psql -c 'DROP TABLE users'", "sudo apt install x", "find . -name '*.log' -delete",
            "ls | xargs rm", "docker system prune -a", "kubectl delete pod web-1", "cd /tmp && rm x",
        ).forEach { assertTrue(isDestructive(it), it) }
        listOf("ls -la", "lsof -nP -iTCP:5173 -sTCP:LISTEN", "git status -sb", "git push origin main", "npm run build", "grep rm notes.txt")
            .forEach { assertFalse(isDestructive(it), it) }
    }

    @Test
    fun monthsAndTrimming() {
        assertEquals("1970-01", monthKey(0))
        assertEquals("2026-11", monthKey(20_774)) // 2026-11-17
        assertEquals("2024-02", monthKey(19_782)) // 2024-02-29
        assertEquals("Dec 1", nextMonthStart("2026-11"))
        assertEquals("Jan 1", nextMonthStart("2026-12"))
        val long = (1..200).joinToString("\n") { "line $it" }
        val trimmed = AgentContext.trimOutput(long, 10_000)
        assertTrue(trimmed.startsWith("… (120 earlier lines)"))
        assertTrue(trimmed.endsWith("line 200"))
    }
}
