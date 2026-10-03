package com.azrael.galleryflow

object SyncControl {
    data class Snapshot(
        val running: Boolean,
        val paused: Boolean,
        val stopping: Boolean,
        val pendingLive: Boolean,
        val mode: SyncMode?,
        val message: String
    )

    private val lock = Any()
    @Volatile private var active = false
    @Volatile private var paused = false
    @Volatile private var stopRequested = false
    @Volatile private var pendingLive = false
    @Volatile private var mode: SyncMode? = null
    @Volatile private var message = "Idle"

    fun tryStart(newMode: SyncMode, queueLiveIfBusy: Boolean = true): Boolean = synchronized(lock) {
        if (active) {
            if (queueLiveIfBusy && newMode == SyncMode.LIVE && !stopRequested) pendingLive = true
            return@synchronized false
        }
        active = true
        paused = false
        stopRequested = false
        pendingLive = false
        mode = newMode
        message = if (newMode == SyncMode.LIVE) "Live Sync starting…" else "Archive Backfill starting…"
        true
    }

    fun checkpoint(): Boolean {
        if (Thread.currentThread().isInterrupted) return false
        while (paused && !stopRequested) {
            try { Thread.sleep(200L) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); return false
            }
        }
        return !stopRequested && !Thread.currentThread().isInterrupted
    }

    fun pause() { if (active && !stopRequested) { paused = true; message = "Paused" } }
    fun resume() { if (active && !stopRequested) { paused = false; message = "Resuming…" } }
    fun stop() {
        synchronized(lock) {
            if (active) {
                stopRequested = true
                pendingLive = false
                paused = false
                message = "Stopping…"
            }
        }
        NetworkRequestRegistry.cancelAll()
    }
    fun isStopping(): Boolean = stopRequested
    fun updateMessage(value: String) { if (active) message = value }
    fun setMode(value: SyncMode) { if (active) mode = value }
    fun takePendingLive(): Boolean = synchronized(lock) {
        if (!active || stopRequested || !pendingLive) false else { pendingLive = false; true }
    }
    fun finish(finalMessage: String = "Idle") = synchronized(lock) {
        active = false; paused = false; stopRequested = false; pendingLive = false; mode = null; message = finalMessage
    }
    fun snapshot(): Snapshot = Snapshot(active, paused, stopRequested, pendingLive, mode, message)
}
