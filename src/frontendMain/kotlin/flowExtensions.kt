package se.kth.somabits.frontend

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

fun <T> repeat(value: T, everMilliSeconds: Long): Flow<T> =
    flow {
        while(true) {
            emit(value)
            delay(everMilliSeconds)
        }
    }

fun <T>Flow<T>.repeatEvery(mSec: Long): Flow<T> =
    this.flatMapLatest {
        repeat(it, mSec)
    }