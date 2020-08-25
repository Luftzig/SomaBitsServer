package se.kth.somabits.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.flow

fun <T> repeat(value: T, everMilliSeconds: Long): Flow<T> =
    flow {
        while(true) {
            emit(value)
            delay(everMilliSeconds)
        }
    }

@ExperimentalCoroutinesApi
fun <T>Flow<T>.repeatEvery(mSec: Long): Flow<T> =
    this.flatMapLatest {
        se.kth.somabits.common.repeat(it, mSec)
    }

/**
 * Simple windowing: returns an iterable of `size` latest elements
 */
@ExperimentalCoroutinesApi
fun <T>Flow<T>.windowed(size: Int): Flow<Iterable<T>> {
    return scan(listOf()) { accumulator, value ->
        (accumulator + value).takeLast(size)
    }
}