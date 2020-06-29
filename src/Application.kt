package se.kth.somabits

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.ContentNegotiation
import io.ktor.features.DataConversion
import io.ktor.gson.gson
import io.ktor.http.ContentType
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.pingPeriod
import io.ktor.http.cio.websocket.readText
import io.ktor.http.cio.websocket.timeout
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.websocket.webSocket
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.NetworkInterface
import java.text.DateFormat
import java.time.Duration

inline class ServiceName(val name: String)

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

fun getLocalAddresses(): Iterable<InetAddress> =
    NetworkInterface.getNetworkInterfaces().asSequence().asIterable()
        .flatMap { it.interfaceAddresses }
        .filter { it.address.isSiteLocalAddress }
        .map { it.address }

data class StatusResponse(val status: String)

@Suppress("unused") // Referenced in application.conf
@kotlin.jvm.JvmOverloads
fun Application.module(testing: Boolean = false) {

    val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
    loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).level = Level.INFO

    install(DataConversion)

    install(io.ktor.websocket.WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    install(ContentNegotiation) {
        gson {
            setDateFormat(DateFormat.LONG)
            setPrettyPrinting()
        }
    }

    val servicesManager =
        ServicesManager(
            getLocalAddresses().first(),
            defaultDiscoveryServices().map { ServiceName(it) },
            defaultDiscoveryServices().associate {
                ServiceName(it) to AdvertiseServerData(defaultOscPort(), mapOf("server" to ""))
            })

    routing {
        get("/") {
            call.respondText("HELLO WORLD!", contentType = ContentType.Text.Plain)
        }

        get("/local-addresses") {
            call.respond(getLocalAddresses().map { it.hostAddress })
        }

        get("/discover") {
            call.respond(servicesManager.services)
        }

        webSocket("/connect/{service}") {

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

private fun Application.defaultDiscoveryServices() =
    environment.config.property("ktor.application.defaultDiscoveryServices").getList()

private fun Application.defaultOscPort(): Int =
    environment.config.property("ktor.application.defaultOscPort").getString().toInt()

