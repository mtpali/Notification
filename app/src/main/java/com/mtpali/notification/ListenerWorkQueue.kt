package com.mtpali.notification

import java.util.concurrent.Executors
import java.util.ArrayDeque

/** A removal is a barrier: coalescing never moves an update across that removal. */
class CoalescingBuffer<T>(private val capacity: Int) {
    private data class Entry<T>(val key: String, val coalesces: Boolean, val value: T)
    private val items = ArrayDeque<Entry<T>>()
    init { require(capacity > 0) }

    fun offer(key: String, value: T, coalesces: Boolean): Boolean {
        if (coalesces) {
            val previous = items.toList().lastOrNull { it.key == key }
            if (previous?.coalesces == true) items.remove(previous)
        }
        if (items.size >= capacity) return false
        items.addLast(Entry(key, coalesces, value))
        return true
    }

    fun poll(): T? = if (items.isEmpty()) null else items.removeFirst().value
    fun clear() { items.clear() }
    val size: Int get() = items.size
}

/** Only one drain is submitted to the executor. Control work has two reserved, coalesced slots. */
class ListenerWorkQueue(private val onGap: () -> Unit, private val onError: () -> Unit) {
    private val lock = Any()
    private val events = CoalescingBuffer<() -> Unit>(256)
    private val controls = linkedMapOf<String, () -> Unit>()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "notification-capture") }
    private var draining = false
    private var gap = false
    private var closed = false

    fun event(key: String, coalesces: Boolean, work: () -> Unit) = synchronized(lock) {
        if (!closed) {
            if (!events.offer(key, work, coalesces)) gap = true
            start()
        }
    }

    fun commands(work: () -> Unit) = control("commands", work)
    fun snapshot(work: () -> Unit) = control("snapshot", work)

    private fun control(key: String, work: () -> Unit) = synchronized(lock) {
        if (!closed) { controls[key] = work; start() }
    }

    private fun start() {
        if (!draining) {
            draining = true
            executor.execute {
                while (true) {
                    val task = synchronized(lock) {
                        val next = if (closed) null
                        else controls.remove("commands") ?: events.poll() ?: controls.remove("snapshot") ?:
                            if (gap) { gap = false; onGap } else null
                        if (next == null) { draining = false; return@execute }
                        next
                    }
                    try { task() } catch (_: Exception) { onError() }
                }
            }
        }
    }

    fun close() = synchronized(lock) {
        closed = true
        events.clear()
        controls.clear()
        executor.shutdown()
    }
}
