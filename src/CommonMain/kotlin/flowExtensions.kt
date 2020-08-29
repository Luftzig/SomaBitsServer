package se.kth.somabits.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.flow

class ConnectionTerminated : Exception("Terminated") {}

@ExperimentalCoroutinesApi
fun <T> Flow<T>.repeatEvery(mSec: Long): Flow<T> =
    flow<T> {
        try {
            coroutineScope {
                onCompletion { this@coroutineScope.cancel() }
                    .transformLatest { value ->
                        while (true) {
                            emit(value)
                            delay(mSec)
                        }
                    }
                    .collect(::emit)
            }
        }
        catch (e: CancellationException) {
            // done
        }
    }

/**
 * Simple windowing: returns an iterable of `size` latest elements
 */
@ExperimentalCoroutinesApi
fun <T> Flow<T>.windowed(size: Int): Flow<Iterable<T>> {
    return scan(listOf()) { accumulator, value ->
        (accumulator + value).takeLast(size)
    }
}