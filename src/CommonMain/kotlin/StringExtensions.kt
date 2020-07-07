package se.kth.somabits.common

fun String.longestMatchingSubstring(other: String): String =
    if (this.isNotEmpty() && other.isNotEmpty() && this[0] == other[0]) {
        this[0] + this.drop(1).longestMatchingSubstring(other.drop(1))
    } else {
        ""
    }