package se.kth.somabits

import kotlin.test.*

class ListExtensions {
    @Test
    fun breakWith() {
        val l: List<Char> = "3aaa5bbbbb".toList()
        val result = l.breakWith {
            val first = it.first()
            val length = Integer.parseInt(first.toString())
            Pair(it.drop(1).take(length), it.drop(length + 1))
        }
        assertEquals("aaa".toList(), result.first())
    }
}