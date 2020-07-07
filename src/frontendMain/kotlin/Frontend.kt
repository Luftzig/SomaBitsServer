package se.kth.somabits.frontend

import se.kth.somabits.common.*
import kotlin.browser.*

@Suppress("unused")
@JsName("helloWorld")
fun helloWorld(salutation: String) {
    val message = "$salutation from Kotlin.JS"
    document.getElementById("js-response")?.textContent = message
}

fun main() {
    document.addEventListener("DOMContentLoaded", {
        helloWorld("Hi!")
    })
}
