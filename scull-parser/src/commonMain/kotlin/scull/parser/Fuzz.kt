package scull.parser

/**
 * The parser's fuzz target: any input, cut anywhere, must give the same actions as the whole
 * input, and every print must be valid UTF-8. Throws [IllegalStateException] on a violation.
 * The tests run it over a seeded random corpus.
 */
public fun parserFeedIsChunkingInvariant(data: ByteArray) {
    val whole = Recorder().also { Parser().feed(data, it) }.actions
    val bytewise = Recorder()
    val single = Parser()
    for (i in data.indices) single.feed(data, i, i + 1, bytewise)
    check(bytewise.actions == whole) { "byte-by-byte feed differs" }

    // Chunk lengths come from the data itself, so a failing input reproduces on its own.
    val chunked = Recorder()
    val parser = Parser()
    var from = 0
    var seed = data.fold(FNV_OFFSET) { hash, byte -> (hash xor (byte.toInt() and BYTE_MASK)) * FNV_PRIME }
    while (from < data.size) {
        seed = seed * FNV_PRIME + 1
        val to = minOf(data.size, from + (seed ushr CHUNK_SHIFT) % MAX_CHUNK + 1)
        parser.feed(data, from, to, chunked)
        from = to
    }
    check(chunked.actions == whole) { "chunked feed differs" }
}

private const val FNV_OFFSET = 0x811C9DC5.toInt()
private const val FNV_PRIME = 0x01000193
private const val CHUNK_SHIFT = 16
private const val MAX_CHUNK = 37

/** One parser action, with every borrowed buffer copied so actions can be compared. */
internal sealed interface Action {
    data class Print(
        val text: String,
    ) : Action

    data class Execute(
        val byte: Int,
    ) : Action

    data class Esc(
        val intermediates: List<Int>,
        val finalByte: Int,
    ) : Action

    data class Csi(
        val params: List<List<Int>>,
        val intermediates: List<Int>,
        val private: Int,
        val finalByte: Int,
        val truncated: Boolean,
    ) : Action

    data class Osc(
        val data: List<Byte>,
        val bellTerminated: Boolean,
    ) : Action

    data class Hook(
        val params: List<List<Int>>,
        val intermediates: List<Int>,
        val finalByte: Int,
    ) : Action

    data class Put(
        val data: List<Byte>,
    ) : Action

    data class Unhook(
        val end: End,
    ) : Action

    data object ApcStart : Action

    data class ApcPut(
        val data: List<Byte>,
    ) : Action

    data class ApcEnd(
        val end: End,
    ) : Action
}

/** Records actions, merging adjacent text and payload pieces so a stream split anywhere records the same list. */
internal class Recorder : Handler {
    val actions = mutableListOf<Action>()

    override fun print(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ) {
        check(end > start) { "empty print" }
        val text = bytes.decodeToString(start, end, throwOnInvalidSequence = true)
        val last = actions.lastOrNull()
        if (last is Action.Print) actions[actions.lastIndex] = Action.Print(last.text + text) else actions += Action.Print(text)
    }

    override fun execute(byte: Int) {
        actions += Action.Execute(byte)
    }

    override fun escDispatch(esc: Esc) {
        actions += Action.Esc(esc.intermediates.toList(), esc.finalByte)
    }

    override fun csiDispatch(csi: Csi) {
        actions += Action.Csi(csi.params.toLists(), csi.intermediates.toList(), csi.private, csi.finalByte, csi.truncated)
    }

    override fun oscDispatch(osc: Osc) {
        actions += Action.Osc(osc.toByteArray().toList(), osc.bellTerminated)
    }

    override fun dcsHook(header: Csi) {
        actions += Action.Hook(header.params.toLists(), header.intermediates.toList(), header.finalByte)
    }

    override fun dcsPut(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ) {
        check(end > start) { "empty put" }
        val last = actions.lastOrNull()
        val data = bytes.copyOfRange(start, end).toList()
        if (last is Action.Put) actions[actions.lastIndex] = Action.Put(last.data + data) else actions += Action.Put(data)
    }

    override fun dcsUnhook(end: End) {
        actions += Action.Unhook(end)
    }

    override fun apcStart() {
        actions += Action.ApcStart
    }

    override fun apcPut(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ) {
        check(end > start) { "empty put" }
        val last = actions.lastOrNull()
        val data = bytes.copyOfRange(start, end).toList()
        if (last is Action.ApcPut) actions[actions.lastIndex] = Action.ApcPut(last.data + data) else actions += Action.ApcPut(data)
    }

    override fun apcEnd(end: End) {
        actions += Action.ApcEnd(end)
    }
}

private fun Params.toLists(): List<List<Int>> = groups().map { it.toList() }

private fun Intermediates.toList(): List<Int> = List(size) { this[it] }
