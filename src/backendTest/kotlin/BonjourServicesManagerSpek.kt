import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe
import se.kth.somabits.backend.breakWith
import se.kth.somabits.backend.parseInterfaces
import se.kth.somabits.common.BitsInterface
import se.kth.somabits.common.BitsInterfaceType
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals

object BonjourServicesManagerSpek : Spek({
    describe("parsing services") {
        it("process interfaces") {
            val interfaceStr = "sensor1=/sensor/in:-10%10"
            val input = createRawInterface(interfaceStr)
            val result = parseInterfaces(input)
            assertEquals(
                listOf(
                    BitsInterface(BitsInterfaceType.Sensor, "1", "/sensor/in", -10 to 10)
                ),
                result
            )
        }
        it("process interface with units") {
            val interfaceStr = "sensor1=/sensor/in:-10%10:V"
            val input = createRawInterface(interfaceStr)
            val result = parseInterfaces(input)
            assertEquals(
                listOf(
                    BitsInterface(BitsInterfaceType.Sensor, "1", "/sensor/in", -10 to 10, "V")
                ),
                result
            )
        }
    }

    describe("breakWith") {
        it("break with") {
            val l: List<Char> = "3aaa5bbbbb".toList()
            val result = l.breakWith {
                val first = it.first()
                val length = Integer.parseInt(first.toString())
                Pair(it.drop(1).take(length), it.drop(length + 1))
            }
            assertEquals("aaa".toList(), result.first())
        }
    }
})

private fun createRawInterface(interfaceStr: String): ByteArray {
    return ByteArray(interfaceStr.length + 1) {
        when (it) {
            0 -> interfaceStr.length.toByte()
            else -> interfaceStr[it - 1].toShort().toByte()
        }
    }
}