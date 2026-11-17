package app.vesper

import app.vesper.app.DefaultRedactPatterns
import app.vesper.app.ShareFormat
import app.vesper.app.redactSecrets
import app.vesper.app.shareBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShareTest {
    private val p = DefaultRedactPatterns

    @Test
    fun countsAndNamesSecrets() {
        val r = redactSecrets("STRIPE_SECRET_KEY=sk_live_abcdefghijklmnop1234\nDATABASE_URL=postgres://u:pw@db/x", p, "••••")
        assertEquals("STRIPE_SECRET_KEY=••••\nDATABASE_URL=••••", r.text)
        assertEquals(2, r.count)
        assertEquals(listOf("STRIPE_SECRET_KEY", "DATABASE_URL"), r.names)
        assertEquals(0, redactSecrets("all clear", p).count)
    }

    @Test
    fun markdownCopyIsRedactedAndFenced() {
        val s = shareBlock(
            command = "pnpm test --filter @orbit/api",
            output = "ok\nGITHUB_TOKEN=ghp_abcdefghijklmnopqrstuvwxyz0123\n```nested```\n",
            meta = "exit 0 · 4.2s · 14:29",
            env = "~/projects/orbit-api · main · zsh",
            format = ShareFormat.Markdown,
            fullOutput = false,
            patterns = p,
            stamp = "1429",
        )
        assertFalse("ghp_" in s.content)
        assertTrue(s.content.startsWith("````sh\n$ pnpm test --filter @orbit/api\n````\nexit 0 · 4.2s · 14:29 · ~/projects/orbit-api · main · zsh\n"))
        assertTrue("GITHUB_TOKEN=••••" in s.content)
        assertEquals(1, s.secrets)
        assertEquals("vesper-pnpm-test-1429.md", s.fileName)
        assertEquals(3, s.totalLines)
    }

    @Test
    fun longOutputIsTrimmedUnlessFull() {
        val output = (1..250).joinToString("\n") { "line $it" }
        val short = shareBlock("seq 250", output, "exit 0", null, ShareFormat.Text, fullOutput = false, patterns = p, stamp = "0900")
        assertTrue(short.content.startsWith("$ seq 250\n# exit 0\n… 150 earlier lines not included\nline 151\n"))
        assertEquals(250, short.totalLines)
        assertEquals("vesper-seq-250-0900.txt", short.fileName)
        val full = shareBlock("seq 250", output, "exit 0", null, ShareFormat.Text, fullOutput = true, patterns = p, stamp = "0900")
        assertTrue("line 1\n" in full.content && "earlier lines" !in full.content)
    }
}
