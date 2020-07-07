package se.kth.somabits

import io.ktor.client.*
import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.websocket.WebSockets
import io.ktor.client.features.websocket.ws
import io.ktor.client.request.get
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess


val client = HttpClient(CIO) {
    install(WebSockets)
}

val HOST = "localhost"
val PORT = 8080

fun main(args: Array<String>): Unit = runBlocking {
    while (true) {
        val input = readLine()?.split("""\W""".toRegex(), limit = 2)
        val command = input?.first()
        val args = input?.last()
        when (command) {
            "c", "con", "connect" -> {
                if (args != null && args.matches("""[\w ]+/[\w ]+""".toRegex())) {
                    val (deviceName, serviceName) = args.split("/", limit = 2)
                    startSession(deviceName, serviceName)
                } else println("Expected to have format of 'Device name/service name' but found $args")
            }
            "q", "quit" -> {
                println("Exiting...")
                client.close()
                exitProcess(0)
            }
            "l", "list" -> {
                val result = client.get<String>(host = HOST, port = PORT, path = "/discover")
                println(result)
            }
            else -> {
                println("Unknown command '$command'")
            }
        }
    }
}

suspend fun startSession(device: String, service: String) {
    println("Connecting to $service")
    val result = runCatching {
        client.get<String>(host=HOST, port = PORT, path = "/connect/$device/")
        client.ws(host = HOST, port = PORT, path = "/connect/$device/$service") {
            while (true) {
                when (val frame = incoming.receive()) {
                    is Frame.Text -> println(frame.readText())
                }
            }
        }
    }
    result.fold(
        { println("Session ended...") },
        { println("Error in session: $it") }
    )
}
