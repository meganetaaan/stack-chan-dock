package jp.stackchan.localvoicepoc.voicevox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class TtsTimingTest {
    @Test fun distinguishesIntervalsApiCallsAndOutputObservations() {
        var time = 1_000_000_000L
        val trace = TtsTiming("first") { time }
        trace.interval("query") { time += 20_000_000 }
        trace.event("playCall")
        time += 10_000_000
        trace.event("playCall") // Keep first call, not the later sample.
        trace.observation("frameZeroOutputEstimate", 1_025_000_000)
        val text = trace.report()
        assertTrue(text.contains("query=20.000 ms"))
        assertTrue(text.contains("playCall=20.000 ms"))
        assertTrue(text.contains("frameZeroOutputEstimate=25.000 ms"))
    }
    @Test fun comparisonReleasesEveryEngineBeforeNextAndMarksFirstWarm() = runBlocking {
        var active = false
        val calls = mutableListOf<String>()
        compareTtsThreads(
            reload = { assertFalse(active); active = true; calls += "load:$it" },
            play = { threads, iteration -> assertTrue(active); calls += "$threads:$iteration" },
            release = { assertTrue(active); active = false; calls += "release" },
        )
        assertFalse(active)
        assertEquals(listOf(1,2,4,0).flatMap { listOf("load:$it", "$it:0", "$it:1", "$it:2", "release") }, calls)
    }
    @Test fun playbackFailureReleasesAndStopsComparison() = runBlocking {
        val calls = mutableListOf<String>()
        try {
            compareTtsThreads({ calls += "load:$it" }, { _, _ -> error("audio failed") }, { calls += "release" })
            fail("expected failure")
        } catch (_: IllegalStateException) { }
        assertEquals(listOf("load:1", "release"), calls)
    }
    @Test fun failedReleasePreventsNextEngine() = runBlocking {
        var loads = 0
        try {
            compareTtsThreads({ loads++ }, { _, _ -> }, { error("finalization unconfirmed") })
            fail("expected failure")
        } catch (_: IllegalStateException) { }
        assertEquals(1, loads)
    }
    @Test fun cancelledNativeLikeLoadCannotPlayOrLoadAnotherEngine() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val unblock = CompletableDeferred<Unit>()
        var plays = 0
        var releases = 0
        var loads = 0
        val task = async {
            compareTtsThreads({
                loads++
                entered.complete(Unit)
                withContext(NonCancellable) { unblock.await() }
            }, { _, _ -> plays++ }, { releases++ })
        }
        entered.await()
        task.cancel()
        unblock.complete(Unit)
        task.cancelAndJoin()
        assertEquals(0, plays)
        assertEquals(1, loads)
        assertEquals(1, releases)
    }
}
