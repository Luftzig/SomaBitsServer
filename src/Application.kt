package se.kth.somabits

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.request.*
import io.ktor.features.*
import io.ktor.routing.*
import io.ktor.http.*
import io.ktor.http.ContentType.Application.Json
import io.ktor.websocket.*
import io.ktor.http.cio.websocket.*
import java.time.*
import java.io.*
import java.util.*
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.network.util.*
import io.ktor.serialization.DefaultJsonConfiguration
import io.ktor.serialization.json
import io.ktor.serialization.serialization
import kotlin.coroutines.*
import kotlinx.coroutines.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.net.NetworkInterface

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

fun getLocalAddresses(): Iterable<InetAddress> =
    NetworkInterface.getNetworkInterfaces().asSequence().asIterable()
        .flatMap { it.interfaceAddresses }
        .filter { it.address.isSiteLocalAddress }
        .map { it.address }

@Suppress("unused") // Referenced in application.conf
@kotlin.jvm.JvmOverloads
fun Application.module(testing: Boolean = false) {
    install(DataConversion)

    install(io.ktor.websocket.WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    install(ContentNegotiation) {
        json()
    }

    routing {
        get("/") {
            call.respondText("HELLO WORLD!", contentType = ContentType.Text.Plain)
        }

        get("/local-addresses") {
            call.respond(getLocalAddresses().map { it.hostAddress })
        }

        get("/discover") {

        }

        webSocket("/bitsws/echo") {
            send(Frame.Text("Hi from server"))
            while (true) {
                val frame = incoming.receive()
                if (frame is Frame.Text) {
                    send(Frame.Text("Client said: " + frame.readText()))
                }
            }
        }
    }
}

