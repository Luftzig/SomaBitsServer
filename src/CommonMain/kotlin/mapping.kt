package se.kth.somabits.common

interface Mapping<T> {
    fun apply(x: T): T
}

abstract class Scale<T: Number>: Mapping<T> {
    abstract fun scale(v: T): T
}

data class LinearScale(val domain: Pair<Double, Double>, val range: Pair<Double, Double>): Scale<Double>() {
    init {
        require(domain.first - domain.second != 0.0)
    }
    override fun scale(v: Double): Double =
        ( (range.second - range.first) / (domain.second - domain.first) * (v - domain.first) ) + (range.first)

    override fun apply(x: Double): Double = scale(x)
}