import kotlinx.coroutines.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runBlockingTest
import kotlinx.coroutines.test.setMain
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe
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
})