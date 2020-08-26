import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe
import se.kth.somabits.common.LinearScale
import kotlin.test.assertEquals

object mappingSpek : Spek({
    describe("LinearScale") {
        it("maps zero base scales") {
            assertEquals(0.0, LinearScale(0.0 to 1.0, 0.0 to 2.0).scale(0.0))
            assertEquals(2.0, LinearScale(0.0 to 1.0, 0.0 to 2.0).scale(1.0))
            assertEquals(1.0, LinearScale(0.0 to 1.0, 0.0 to 2.0).scale(0.5))
        }

        it("maps inverted ranges") {
            val scale = LinearScale(0.0 to 1.0, 1.0 to 0.0)
            assertEquals(0.0, scale.scale(1.0))
            assertEquals(1.0, scale.scale(0.0))
            assertEquals(0.5, scale.scale(0.5))
        }

        it("maps ranges from arbitrary numbers") {
            val scale = LinearScale(-1.0 to 1.0, 10.0 to 20.0)
            assertEquals(10.0, scale.scale(-1.0))
            assertEquals(20.0, scale.scale(1.0))
            assertEquals(15.0, scale.scale(0.0))
        }
    }
})