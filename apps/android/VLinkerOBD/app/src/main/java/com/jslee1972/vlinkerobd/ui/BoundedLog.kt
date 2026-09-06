package com.jslee1972.vlinkerobd.ui

/** In-memory BLE/OBD log capped to the most recent [maxEntries] lines. Cleared on app restart. */
class BoundedLog(private val maxEntries: Int = 500) {

    private val backing = ArrayDeque<String>()

    val entries: List<String>
        get() = backing.toList()

    fun append(line: String) {
        backing.addLast(line)
        while (backing.size > maxEntries) {
            backing.removeFirst()
        }
    }

    fun clear() {
        backing.clear()
    }
}
