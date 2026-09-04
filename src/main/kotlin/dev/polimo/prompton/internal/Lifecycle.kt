package dev.polimo.prompton.internal

import dev.polimo.prompton.HttpTransport
import java.lang.ref.Cleaner
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Everything one SDK instance owns that has to be released: the log queue, the use case document poller and
 * the transport.
 *
 * It deliberately holds no reference back to the [dev.polimo.prompton.PromptOn] that owns it, so an
 * instance the app forgot to close stays collectible and [PromptOnLifecycle] can release its threads
 * for it.
 */
internal class PromptOnResources(
    private val snapshots: SnapshotManager,
    private val buffer: LogBuffer?,
    private val transport: HttpTransport?,
) {
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean get() = closed.get()

    /** Idempotent: the app, the JVM shutdown hook and the cleaner may all get here. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { buffer?.close() }
        runCatching { snapshots.close() }
        runCatching { transport?.close() }
    }
}

/**
 * One JVM shutdown hook for the whole process, and one [Cleaner] registration per instance.
 *
 * A hook per instance would pin every SDK instance the app ever built — a shutdown hook is a GC root
 * — so a test suite or a per-request instantiation would accumulate hooks and threads without bound.
 * Instead a single hook walks weak references to the instances that are still alive, and the cleaner
 * closes an instance the app dropped without calling `close()`, which is what actually releases its
 * `prompton-use-cases` and `prompton-logs` threads.
 */
internal object PromptOnLifecycle {
    private val cleaner: Cleaner by lazy {
        Cleaner.create { runnable -> Thread(runnable, "prompton-cleaner").apply { isDaemon = true } }
    }
    private val live = ConcurrentHashMap<Long, WeakReference<PromptOnResources>>()
    private val tokens = AtomicLong()
    private val hookInstalled = AtomicBoolean(false)
    private val reclaimed = AtomicLong()

    /** What `PromptOn.close` holds on to: closing through it also deregisters the cleaner. */
    class Registration internal constructor(
        private val resources: PromptOnResources,
        private val cleanable: Cleaner.Cleanable,
    ) {
        fun closeNow() {
            // Close first, so the cleanup that follows knows this was a deliberate close and not a
            // forgotten instance being collected.
            resources.close()
            cleanable.clean()
        }
    }

    fun register(
        owner: Any,
        resources: PromptOnResources,
        flushAtShutdown: Boolean,
        ownsThreads: Boolean = flushAtShutdown,
    ): Registration {
        val token =
            if (flushAtShutdown) {
                tokens.incrementAndGet().also {
                    live[it] = WeakReference(resources)
                    installHook()
                }
            } else {
                null
            }
        val cleanable =
            cleaner.register(owner) {
                if (!resources.isClosed && ownsThreads) {
                    reclaimed.incrementAndGet()
                    PtnLog.throttled("unclosed-instance", 60_000) {
                        "[PromptOn] an instance was garbage collected without close() — " +
                            "releasing its threads; prefer close() or use {}"
                    }
                }
                token?.let { live.remove(it) }
                resources.close()
            }
        return Registration(resources, cleanable)
    }

    private fun installHook() {
        if (!hookInstalled.compareAndSet(false, true)) return
        val hook = Thread({ closeAll() }, "prompton-shutdown")
        if (runCatching { Runtime.getRuntime().addShutdownHook(hook) }.isFailure) hookInstalled.set(false)
    }

    /**
     * Closes every instance still alive, in parallel.
     *
     * Each close spends up to a few seconds trying to hand its queue to the server, and the JVM is
     * already on its way out, so they run side by side rather than adding up.
     */
    private fun closeAll() {
        val targets = live.values.mapNotNull { it.get() }
        live.clear()
        when (targets.size) {
            0 -> return
            1 -> runCatching { targets.first().close() }
            else -> {
                val pool =
                    Executors.newFixedThreadPool(minOf(targets.size, MAX_SHUTDOWN_THREADS)) { runnable ->
                        Thread(runnable, "prompton-shutdown-worker").apply { isDaemon = true }
                    }
                targets.forEach { resources -> pool.execute { runCatching { resources.close() } } }
                pool.shutdown()
                runCatching { pool.awaitTermination(SHUTDOWN_BUDGET_SECONDS, TimeUnit.SECONDS) }
            }
        }
    }

    internal fun liveCount(): Int = live.size

    internal fun reclaimedCount(): Long = reclaimed.get()

    internal fun hookInstalled(): Boolean = hookInstalled.get()

    private const val MAX_SHUTDOWN_THREADS = 8
    private const val SHUTDOWN_BUDGET_SECONDS = 10L
}
