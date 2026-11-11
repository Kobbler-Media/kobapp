package dev.kobbler.kobplayer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * libVLC is driven from one thread, so a native call that never returns holds up everything
 * queued behind it. A new title is the one command that must always get through: it drops
 * what was queued for the old one, and when the call ahead of it is stuck, it runs on a
 * fresh player instead.
 */
class CommandLaneTest {

    private class Native(val id: Int) {
        val ran: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var retired = false
        @Volatile var askedToUnstick = false
    }

    private val made = AtomicInteger()
    private val replaced = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val stuck = CountDownLatch(1)
    private val stalls = Collections.synchronizedList(mutableListOf<PlayerStall>())
    @Volatile private var unsticksWhenAsked = false

    private val lane = CommandLane(
        scope = scope,
        wedgedAfterMs = 300,
        newEngine = {
            val native = Native(made.incrementAndGet())
            val thread = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
            CommandLane.Engine(
                native,
                thread.asCoroutineDispatcher(),
                stuckIn = { "native ${native.id}" },
                unstick = {
                    native.askedToUnstick = true
                    if (unsticksWhenAsked) stuck.countDown()
                },
            ) {
                it.retired = true
                thread.shutdown()
            }
        },
        onReplaced = { old, new -> replaced += old.id to new.id },
        resumeTitle = { ran += "resume" },
        releaseWaitMs = 600,
    )

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { lane.stalls.collect { stalls += it } }
    }

    @AfterTest
    fun tearDown() {
        stuck.countDown()
        scope.cancel()
    }

    private fun postStuck() {
        val started = CountDownLatch(1)
        lane.post { started.countDown(); stuck.await() }
        assertTrue(started.await(3, TimeUnit.SECONDS), "the stuck call never started")
    }

    private fun eventually(what: String, check: () -> Boolean) = runBlocking {
        runCatching { withTimeout(3_000) { while (!check()) delay(10) } }
            .getOrElse { error("$what; on player ${lane.current.id}: ${lane.current.ran}, replaced $replaced") }
    }

    @Test
    fun commandsRunInTheOrderTheyWereGiven() {
        val first = lane.current
        listOf("open", "seek", "pause").forEach { name -> lane.post { ran += name } }
        eventually("all three run") { first.ran.size == 3 }
        assertEquals(listOf("open", "seek", "pause"), first.ran)
    }

    @Test
    fun aNewTitleDropsWhatWasQueuedForTheOldOne() = runBlocking {
        val first = lane.current
        val gate = CountDownLatch(1)
        lane.post { gate.await(); ran += "open A" }
        lane.post { ran += "seek A" }
        val position = scope.async(start = CoroutineStart.UNDISPATCHED) { lane.call { ran += "time A"; 42L } }
        lane.supersede { ran += "open B" }
        lane.post { ran += "play B" }
        gate.countDown()
        eventually("B playing") { first.ran.lastOrNull() == "play B" }
        assertEquals(listOf("open A", "open B", "play B"), first.ran)
        assertNull(position.await(), "a dropped call answers nothing")
    }

    @Test
    fun aNewTitleBehindAStuckCallRunsOnAFreshPlayer() {
        val first = lane.current
        postStuck()
        lane.post { ran += "seek A" }
        lane.supersede { ran += "open B" }
        lane.post { ran += "play B" }
        eventually("B playing on a new player") { lane.current !== first && lane.current.ran == listOf("open B", "play B") }
        assertEquals(listOf(1 to 2), replaced)
        assertEquals(emptyList(), first.ran)
    }

    @Test
    fun aNewTitleWhoseStopOfTheOldOneNeverReturnsStillStartsOnAFreshPlayer() {
        val first = lane.current
        val stopping = CountDownLatch(1)
        lane.replaceTitle(
            stop = { stopping.countDown(); stuck.await() },
            start = { ran += "open B" },
        )
        assertTrue(stopping.await(3, TimeUnit.SECONDS), "the stop never started")
        lane.post { ran += "play B" }
        eventually("B playing on a new player") { lane.current !== first && lane.current.ran == listOf("open B", "play B") }
        assertEquals(emptyList(), first.ran)
    }

    @Test
    fun aNewTitleWhoseStopReturnsStartsOnTheSamePlayer() {
        val first = lane.current
        lane.replaceTitle(stop = { ran += "stop A" }, start = { ran += "open B" })
        lane.post { ran += "play B" }
        eventually("B playing") { first.ran.lastOrNull() == "play B" }
        assertEquals(listOf("stop A", "open B", "play B"), first.ran)
        assertEquals(emptyList(), replaced)
    }

    @Test
    fun aStuckCallWithNothingQueuedBehindItIsLeftAlone() {
        val first = lane.current
        postStuck()
        Thread.sleep(1_000)
        assertTrue(lane.current === first)
        assertEquals(emptyList(), replaced)
    }

    @Test
    fun aPauseQueuedBehindAStuckSeekRunsOnAFreshPlayerThatPickedTheTitleBackUp() {
        val first = lane.current
        postStuck()
        lane.post { ran += "pause" }
        eventually("paused on a new player") { lane.current !== first && lane.current.ran == listOf("resume", "pause") }
        assertEquals(listOf(1 to 2), replaced)
        assertEquals(emptyList(), first.ran)
    }

    @Test
    fun aCallQueuedBehindAStuckCallIsAnsweredByTheFreshPlayer() = runBlocking {
        val first = lane.current
        postStuck()
        val answer = withTimeout(3_000) { lane.call { id } }
        assertTrue(lane.current !== first)
        assertEquals(lane.current.id, answer)
    }

    @Test
    fun anAbandonedPlayerIsRetiredOnceItsCallReturns() {
        val first = lane.current
        postStuck()
        lane.supersede { ran += "open B" }
        eventually("replaced") { lane.current !== first }
        assertTrue(!first.retired)
        stuck.countDown()
        eventually("the old player retired") { first.retired }
    }

    @Test
    fun closingRetiresThePlayerAfterWhatWasGivenAndTakesNoMore() {
        val first = lane.current
        lane.post { ran += "stop" }
        var afterRetired: Boolean? = null
        lane.close { afterRetired = first.retired }
        assertTrue(!lane.post { ran += "late" })
        eventually("retired, then the rest") { afterRetired != null }
        Thread.sleep(100)
        assertEquals(listOf("stop"), first.ran)
        assertEquals(true, afterRetired)
    }

    @Test
    fun closingBehindAStuckCallStillReturnsAtOnce() {
        postStuck()
        val started = System.nanoTime()
        lane.close()
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 100)
    }

    @Test
    fun closingBehindAStuckCallStartsNoFreshPlayer() {
        postStuck()
        lane.close()
        Thread.sleep(1_000)
        assertEquals(1, made.get())
        assertEquals(emptyList(), replaced)
    }

    @Test
    fun aStuckCallThatIsEscapedIsReportedWithHowLongAndWhere() {
        postStuck()
        lane.supersede { ran += "open B" }
        eventually("the stall reported") { stalls.isNotEmpty() }
        val stall = stalls.first()
        assertEquals("native 1", stall.stuckIn)
        assertNull(stall.released)
        assertTrue(stall.stuckForMs >= 300, "stuck for ${stall.stuckForMs}ms")
    }

    @Test
    fun anEscapedPlayerIsAskedToComeUnstuckAndReportedReleasedOnceItIs() {
        unsticksWhenAsked = true
        val first = lane.current
        postStuck()
        lane.supersede { ran += "open B" }
        eventually("the old player retired") { first.retired }
        assertTrue(first.askedToUnstick)
        eventually("its release reported") { stalls.any { it.released == true } }
    }

    @Test
    fun anEscapedPlayerThatStaysStuckIsReportedAsNeverReleased() {
        val first = lane.current
        postStuck()
        lane.supersede { ran += "open B" }
        eventually("reported as not released") { stalls.any { it.released == false } }
        assertTrue(first.askedToUnstick)
        assertTrue(!first.retired)
    }

    @Test
    fun closingBehindAStuckCallAsksItToComeUnstuckAndRetiresIt() {
        unsticksWhenAsked = true
        val first = lane.current
        postStuck()
        lane.close()
        eventually("retired after coming unstuck") { first.retired }
        assertTrue(first.askedToUnstick)
    }
}
