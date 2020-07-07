package se.kth.somabits.frontend

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.features.websocket.WebSockets
import io.ktor.client.request.get
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import se.kth.somabits.common.*
import kotlin.browser.*
import kotlinx.html.*
import kotlinx.html.dom.*

val client = HttpClient(Js) {
    install(WebSockets)
}

suspend fun HttpClient.discoverService(): MutableMap<String, BitsService> =
    get("/discover")

suspend fun main() {
    coroutineScope {
        document.addEventListener("DOMContentLoaded", {
            launch {
                client.discoverService()
            }
            bootstrap("js-content")
        })
    }
}

@Suppress("unused")
@JsName("bootstrap")
fun bootstrap(target: String) {
    document.getElementById(target)?.append {
        div {
            div {
                h1 {
                    +"Welcome to the bits server!"
                }
                h2 {
                }
            }
            div {
                +"Here will be a list of known devices"
            }
        }
    }
}

fun getRunningOn() =
    ""


