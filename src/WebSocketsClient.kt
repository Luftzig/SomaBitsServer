package se.kth.somabits

import io.ktor.client.HttpClient
import io.ktor.websocket.WebSockets


val client = HttpClient {
    install(WebSockets)
}

fun main(args: Array<String>): Unit {

}