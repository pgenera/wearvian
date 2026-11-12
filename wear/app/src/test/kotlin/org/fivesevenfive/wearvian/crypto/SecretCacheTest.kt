package org.fivesevenfive.wearvian.crypto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SecretCacheTest {
    private val cache = SecretCache(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    private val calls = AtomicInteger()
    private fun secret(b: Int): () -> ByteArray = { calls.incrementAndGet(); byteArrayOf(b.toByte(), 2, 3) }

    @Test
    fun computesOnceAndReuses() = runBlocking {
        assertArrayEquals(byteArrayOf(1, 2, 3), cache.get("v", secret(1)))
        assertArrayEquals(byteArrayOf(1, 2, 3), cache.get("v", secret(9)))
        assertEquals(1, calls.get())
    }

    @Test
    fun concurrentCallersShareOneComputation() = runBlocking {
        val gate = CountDownLatch(1)
        val slow: () -> ByteArray = { gate.await(5, TimeUnit.SECONDS); calls.incrementAndGet(); byteArrayOf(7) }
        cache.prefetch("v", slow)
        val waiters = (1..5).map { async(Dispatchers.Default) { cache.get("v", slow) } }
        gate.countDown()
        waiters.awaitAll().forEach { assertArrayEquals(byteArrayOf(7), it) }
        assertEquals(1, calls.get())
    }

    @Test
    fun returnsCopiesSoCallersCannotCorruptTheCache() = runBlocking {
        cache.get("v", secret(1)).fill(0)
        assertArrayEquals(byteArrayOf(1, 2, 3), cache.get("v", secret(1)))
    }

    @Test
    fun failureIsNotCached() = runBlocking {
        try {
            cache.get("v") { calls.incrementAndGet(); error("keystore busy") }
            fail("expected failure")
        } catch (e: IllegalStateException) {
            assertEquals("keystore busy", e.message)
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), cache.get("v", secret(1)))
        assertEquals(2, calls.get())
    }

    @Test
    fun differentKeyRecomputes() = runBlocking {
        cache.get("a", secret(1))
        assertArrayEquals(byteArrayOf(5, 2, 3), cache.get("b", secret(5)))
        assertEquals(2, calls.get())
    }

    @Test
    fun clearZeroesTheCachedValueButNotCopiesAlreadyHandedOut() = runBlocking {
        val held = cache.get("v", secret(1))
        cache.clear()
        assertArrayEquals("caller's copy is the caller's to zero", byteArrayOf(1, 2, 3), held)
        assertArrayEquals(byteArrayOf(5, 2, 3), cache.get("v", secret(5)))
        assertEquals(2, calls.get())
    }

    @Test
    fun clearForcesRecompute() = runBlocking {
        cache.get("v", secret(1))
        cache.clear()
        assertArrayEquals(byteArrayOf(4, 2, 3), cache.get("v", secret(4)))
        assertEquals(2, calls.get())
    }

    @Test
    fun computationFinishingAfterClearDoesNotRepopulate() = runBlocking {
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        cache.prefetch("v") { started.countDown(); gate.await(5, TimeUnit.SECONDS); byteArrayOf(1) }
        started.await(5, TimeUnit.SECONDS)
        cache.clear()
        gate.countDown()
        assertArrayEquals(byteArrayOf(2), cache.get("v") { byteArrayOf(2) })
    }

    private val locked = java.util.concurrent.atomic.AtomicBoolean(false)
    private val guarded = SecretCache(CoroutineScope(SupervisorJob() + Dispatchers.Default)) { locked.get() }

    @Test
    fun getWhileLockedClearsAndThrowsWithoutComputing() = runBlocking {
        guarded.get("v", secret(1))
        locked.set(true)
        try {
            guarded.get("v", secret(2))
            fail("expected WatchLockedException")
        } catch (_: WatchLockedException) {
        }
        assertEquals("nothing computed while locked", 1, calls.get())
        locked.set(false)
        assertArrayEquals("cache was cleared: recomputed after unlock", byteArrayOf(3, 2, 3), guarded.get("v", secret(3)))
        assertEquals(2, calls.get())
    }

    @Test
    fun prefetchWhileLockedComputesNothingAndDoesNotThrow() = runBlocking {
        locked.set(true)
        guarded.prefetch("v", secret(1))
        locked.set(false)
        assertArrayEquals(byteArrayOf(4, 2, 3), guarded.get("v", secret(4)))
        assertEquals(1, calls.get())
    }

    @Test
    fun lockDuringComputationWithholdsTheResultAndCachesNothing() = runBlocking {
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val slow: () -> ByteArray = {
            started.countDown(); gate.await(5, TimeUnit.SECONDS); calls.incrementAndGet(); byteArrayOf(7)
        }
        val waiter = async(Dispatchers.Default) { runCatching { guarded.get("v", slow) } }
        started.await(5, TimeUnit.SECONDS)
        locked.set(true) // watch locks while the ECDH is running
        gate.countDown()
        assertEquals(true, waiter.await().exceptionOrNull() is WatchLockedException)
        locked.set(false)
        assertArrayEquals(byteArrayOf(1, 2, 3), guarded.get("v", secret(1)))
        assertEquals("not cached across the lock", 2, calls.get())
    }

    @Test
    fun prefetchOfACachedKeyIsANoOp() = runBlocking {
        cache.get("v", secret(1))
        cache.prefetch("v", secret(9))
        assertArrayEquals(byteArrayOf(1, 2, 3), cache.get("v", secret(9)))
        assertEquals(1, calls.get())
    }
}
