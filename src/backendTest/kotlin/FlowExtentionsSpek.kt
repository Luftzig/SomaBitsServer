import kotlinx.coroutines.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runBlockingTest
import kotlinx.coroutines.test.setMain
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe
import se.kth.somabits.common.repeatEvery
import se.kth.somabits.common.windowed
import kotlin.test.assertEquals

@ExperimentalCoroutinesApi
object FlowExtentionsSpek : Spek({
    val mainThreadSurrogate = newSingleThreadContext("UI thread")

    beforeEachGroup {
        Dispatchers.setMain(mainThreadSurrogate)
    }

    afterEachGroup {
        Dispatchers.resetMain()
        mainThreadSurrogate.close()
    }

    describe("windowed") {
        it("accumulates up to window size") {
            runBlockingTest {
                val result = flowOf(1, 2, 3)
                    .windowed(3)
                    .toList()
                    .map { it.toList() }
                assertEquals(
                    listOf(
                        listOf(),
                        listOf(1),
                        listOf(1, 2),
                        listOf(1, 2, 3)
                    ), result
                )
            }
        }

        it("removes old values from window") {
            runBlockingTest {
                val result = flowOf(1, 2, 3, 4)
                    .windowed(3)
                    .toList()
                    .map { it.toList() }
                assertEquals(
                    listOf(
                        listOf(),
                        listOf(1),
                        listOf(1, 2),
                        listOf(1, 2, 3),
                        listOf(2, 3, 4)
                    ),
                    result
                )
            }
        }
    }

    describe("repeatEvery") {
        runBlockingTest {
            val f = flow {
                emit(1)
                delay(1000)
                emit(2)
                delay(1000)
                emit(3)
                delay(1000)
                emit(4)
            }.repeatEvery(100)
            val l = mutableListOf<Int>()
            val job = launch {
                f.toList(l)
            }
            advanceTimeBy(1499)
            job.cancel()
            assertEquals(List(10) { 1 } + List(5) { 2 }, l)
        }
    }
})