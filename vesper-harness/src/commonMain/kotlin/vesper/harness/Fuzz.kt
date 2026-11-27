package vesper.harness

import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The fuzz target of the stub: arbitrary bytes must leave the grid size alone. The seeded corpus
 * test runs it under `./gradlew check`, and the CI fuzz job runs the same corpus for a time budget.
 * The size stays fixed because the property under test is that [Stub.feed] does not resize.
 */
public fun stubFeedKeepsSize(data: ByteArray) {
    val stub = Stub(80, 24)
    stub.feed(data)
    check(stub.cols == 80 && stub.rows == 24) { "feed resized the stub to ${stub.cols}x${stub.rows}" }
    check(stub.lines().all { it.length == 80 }) { "feed changed a row width" }
}

/** Seconds each [fuzzCases] call runs for instead of its fixed cases; the CI fuzz job sets it. */
public const val FUZZ_SECONDS_VARIABLE: String = "VESPER_FUZZ_SECONDS"

/** The seed a budgeted [fuzzCases] run starts from, to reproduce a failure that run reported. */
public const val FUZZ_SEED_VARIABLE: String = "VESPER_FUZZ_SEED"

// A typo in the CI job must not hold a runner for hours.
private const val MAX_FUZZ_SECONDS = 3600

/**
 * Runs [case] over a seeded corpus: [cases] times from [seed] by default, so `./gradlew check`
 * is repeatable. With [FUZZ_SECONDS_VARIABLE] set it runs for that many seconds from a fresh seed,
 * or from [FUZZ_SEED_VARIABLE], so CI explores past the fixed corpus. A failure names its case and
 * the seed to rerun it with.
 */
public fun fuzzCases(
    seed: Int,
    cases: Int,
    case: (Random) -> Unit,
) {
    val budget =
        environment(FUZZ_SECONDS_VARIABLE)
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?.coerceAtMost(MAX_FUZZ_SECONDS)
            ?.seconds
    if (budget == null) {
        val random = Random(seed)
        repeat(cases) { runCase(it, "fixed seed $seed", random, case) }
        return
    }
    val base = environment(FUZZ_SEED_VARIABLE)?.trim()?.toIntOrNull() ?: Random.nextInt()
    // Mixed with the call's own seed so the calls of one run do not draw the same inputs.
    val random = Random(base xor seed)
    val started = TimeSource.Monotonic.markNow()
    var index = 0
    while (started.elapsedNow() < budget) {
        runCase(index++, "$FUZZ_SEED_VARIABLE=$base", random, case)
    }
}

private fun runCase(
    index: Int,
    seed: String,
    random: Random,
    case: (Random) -> Unit,
) {
    try {
        case(random)
    } catch (e: Throwable) {
        throw AssertionError("fuzz case $index failed ($seed): ${e.message}", e)
    }
}
