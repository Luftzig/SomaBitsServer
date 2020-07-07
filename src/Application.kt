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
import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openWriteChannel
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.util.cio.write
import io.ktor.websocket.webSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.InetSocketAddress
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

data class StatusResponse(val status: String, val message: String?)

@Suppress("unused") // Referenced in application.conf
@kotlin.jvm.JvmOverloads
fun Application.module(testing: Boolean = false) {

    val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
    loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).level = Level.INFO
    loggerContext.getLogger(log.name).level = Level.DEBUG

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
            null,
            defaultDiscoveryServices().map { ServiceName(it) },
            defaultDiscoveryServices().associate {
                ServiceName(it) to AdvertiseServerData(defaultOscPort(), mapOf("server" to ""))
            })

    val oscConnections: MutableMap<ServiceName, OscConnection> = mutableMapOf()

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

        get("/connect/{service}/{port?}") {
            val serviceName = call.parameters["service"]?.let { it1 -> ServiceName(it1) }
            val handshakePort = call.parameters["port"]?.toIntOrNull() ?: defaultHandshakePort()
            val bitsService = servicesManager.services[serviceName]
            log.info("Initializing handshake with $serviceName:$handshakePort")
            if (bitsService != null) {
                // We know that by know the server has at least one IP address
                val bestMatchingServerAddress =
                    getLocalAddresses().maxBy { it.hostAddress.longestMatchingSubstring(bitsService.address).length }!!
                kotlin.runCatching { sendServerIp(bitsService, handshakePort, bestMatchingServerAddress) }
                    .fold({
                        oscConnections[bitsService.name] = OscConnection(
                            bestMatchingServerAddress,
                            defaultOscPort(),
                            InetAddress.getByName(bitsService.address),
                            bitsService.port
                        )
                        log.debug("Connection established with $bitsService")
                        call.respond(
                            StatusResponse(
                                "ok",
                                "Sent address $bestMatchingServerAddress to ${bitsService.address}:$handshakePort"
                            )
                        )
                    },
                        {
                            call.respond(
                                StatusResponse(
                                    "failed",
                                    "Failed to handshake with bit at ${bitsService.address}:$handshakePort: $it"
                                )
                            )
                        }
                    )
            } else {
                call.respond(StatusResponse("failed", "Failed to find service '$serviceName'"))
            }
        }

        webSocket("/connect/{service}/{pattern}") {
            val serviceName = call.parameters["service"]?.let { it1 -> ServiceName(it1) }
            val pattern = call.parameters["pattern"]
            val connection = serviceName?.let { oscConnections[serviceName] }
            log.info("starting OSC session with $serviceName:$pattern")
            pattern?.let { pattern ->
                connection?.listenTo("/$pattern") {
                    launch {
                        send(Frame.Text("${it.time}: ${it.source}: ${it.message}"))
                    }
                }
                while (true) {
                    val frame = incoming.receive()
                    if (frame is Frame.Text) {
                        connection?.send("/$pattern", listOf(frame.readText()))
                    }
                }
            }
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

private suspend fun sendServerIp(
    bitsService: BitsService,
    handshakePort: Int,
    bestAddress: InetAddress?
) {
    val socket = aSocket(ActorSelectorManager(Dispatchers.IO)).tcp()
    val boundSocket = socket.connect(InetSocketAddress(bitsService.address, handshakePort))
    boundSocket.openWriteChannel(autoFlush = true).write("${bestAddress?.hostName}\r\n")
    boundSocket.close()
}

private fun Application.defaultDiscoveryServices() =
    environment.config.property("ktor.application.defaultDiscoveryServices").getList()

private fun Application.defaultOscPort(): Int =
    environment.config.property("ktor.application.defaultOscPort").getString().toInt()

private fun Application.defaultHandshakePort(): Int =
    environment.config.property("ktor.application.defaultHandshakePort").getString().toInt()

private fun String.longestMatchingSubstring(other: String): String =
    if (this.isNotEmpty() && other.isNotEmpty() && this[0] == other[0]) {
        this[0] + this.drop(1).longestMatchingSubstring(other.drop(1))
    } else {
        ""
    }

