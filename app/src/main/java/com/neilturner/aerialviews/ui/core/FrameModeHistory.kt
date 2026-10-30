package com.neilturner.aerialviews.ui.core

/** Keeps the actual slide sequence when weighted modes jump across the playlist. */
internal class FrameModeHistory(
    private val capacity: Int = 200,
) {
    init {
        require(capacity > 0)
    }

    private val positions = mutableListOf<Int>()
    private var cursor = -1

    fun clear() {
        positions.clear()
        cursor = -1
    }

    fun record(position: Int) {
        require(position >= 0)
        if (cursor >= 0 && positions[cursor] == position) return
        while (positions.lastIndex > cursor) positions.removeAt(positions.lastIndex)
        positions.add(position)
        if (positions.size > capacity) positions.removeAt(0)
        cursor = positions.lastIndex
    }

    fun previous(): Int? {
        if (cursor <= 0) return null
        return positions[--cursor]
    }

    fun peekPrevious(): Int? = positions.getOrNull(cursor - 1)

    fun peekNext(): Int? = positions.getOrNull(cursor + 1)

    fun recordPrevious(position: Int) {
        require(position >= 0)
        if (cursor < 0) {
            record(position)
            return
        }
        positions.add(cursor, position)
        if (positions.size > capacity) positions.removeAt(positions.lastIndex)
    }

    fun next(): Int? {
        if (cursor < 0 || cursor >= positions.lastIndex) return null
        return positions[++cursor]
    }
}
