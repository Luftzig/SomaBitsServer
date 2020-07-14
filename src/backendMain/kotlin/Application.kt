package se.kth.somabits.backend

import arrow.core.Either
import arrow.core.getOrHandle
import io.ktor.application.*
import io.ktor.features.ContentNegotiation
import io.ktor.features.DataConversion
import io.ktor.gson.gson
import io.ktor.html.respondHtml
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.pingPeriod
import io.ktor.http.cio.websocket.readText
import io.ktor.http.cio.websocket.timeout
import io.ktor.http.content.resource
import io.ktor.http.content.static
import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openWriteChannel
import io.ktor.response.respond
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.serialization.json
import io.ktor.serialization.serialization
import io.ktor.util.cio.write
import io.ktor.util.pipeline.PipelineContext
import io.ktor.websocket.webSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.html.*
import se.kth.somabits.common.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.text.DateFormat
import java.time.Duration

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

fun getLocalAddresses(): Iterable<InetAddress> =
    NetworkInterface.getNetworkInterfaces().asSequence().asIterable()
        .flatMap { it.interfaceAddresses }
        .filter { it.address.isSiteLocalAddress }
        .map { it.address }

//@Suppress("unused") // Referenced in application.conf
fun Application.module() {
    install(DataConversion)

    install(io.ktor.websocket.WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    install(ContentNegotiation) {
        serialization()
    }

    val servicesManager =
        ServicesManager(
            null,
            defaultDiscoveryServices().map { ServiceName(it) },
            defaultDiscoveryServices().associate {
                ServiceName(it) to AdvertiseServerData(
                    defaultOscPort(),
                    mapOf("server" to "")
                )
            })

    val oscConnections: MutableMap<ServiceName, OscConnection> = mutableMapOf()

    routing {
        get("/") {
            call.respondHtml {
                head {
                    link(
                        rel = "stylesheet",
                        href = "https://stackpath.bootstrapcdn.com/bootstrap/4.5.0/css/bootstrap.min.css"
                    ) {
                        attributes.put(
                            "integrity",
                            "sha384-9aIt2nRpC12Uk9gS9baDl411NQApFmC26EwAOH8WgZl5MYYxFfc+NcPb1dKGj7Sk"
                        )
                        attributes.put("crossorigin", "anonymous")
                    }

                    title("Somabits Server")
                }
                body {
                    div {
                        id = "root"
                        +"Loading..."
                    }
                    script(src = "/static/somabits.js") {}
                }
            }
        }

        static("/static") {
            resource("somabits.js")
            resource("somabits.map.js")
        }

        get("/local-addresses") {
            call.respond(getLocalAddresses().map { it.hostAddress })
        }

        get("/discover") {
            call.respond(servicesManager.services)
        }

        get("/connect/{service}/{port?}") {
            val serviceName = call.parameters["service"]?.let { it1 ->
                ServiceName(
                    it1
                )
            }
            val handshakePort = call.parameters["port"]?.toIntOrNull() ?: defaultHandshakePort()
            val bitsService = servicesManager.services[serviceName]
            val oscPort = defaultOscPort()
            log.info("Initializing handshake with $serviceName:$handshakePort")
            if (bitsService != null) {
                // We know that by know the server has at least one IP address
                connectToService(bitsService, handshakePort, oscPort, oscConnections)
                    .map { serverAddressUsed ->
                        log.debug("Connection established with $bitsService")
                        launch {
                            call.respond(
                                StatusResponse(
                                    "ok",
                                    "Sent address $serverAddressUsed to ${bitsService.address}:$handshakePort"
                                )
                            )
                        }
                    }
                    .mapLeft {
                        launch {
                            call.respond(
                                StatusResponse(
                                    "failed",
                                    "Failed to handshake with bit at ${bitsService.address}:$handshakePort: $it"
                                )
                            )
                        }
                    }
            } else {
                call.respond(
                    StatusResponse(
                        "failed",
                        "Failed to find service '$serviceName'"
                    )
                )
            }
        }

        webSocket("/connect/{service}/{pattern}") {
            val serviceName = call.parameters["service"]?.let { it1 ->
                ServiceName(
                    it1
                )
            }
            val pattern = call.parameters["pattern"]
            // Get connection or try to establish new one
            val connection = serviceName?.let {
                if (it in oscConnections) {
                    oscConnections[serviceName]
                } else {
                    val bitsService = servicesManager.services[serviceName]
                    val oscPort = defaultOscPort()
                    bitsService?.let { it1 ->
                        connectToService(it1, defaultHandshakePort(), oscPort, oscConnections)
                            .map { oscConnections[serviceName] }
                            .getOrHandle {
                                log.debug("Failed to retrieve OSC connection due to: $it")
                                null
                            }
                    }
                }
            }
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

private suspend fun connectToService(
    bitsService: BitsService,
    handshakePort: Int,
    oscPort: Int,
    oscConnections: MutableMap<ServiceName, OscConnection>
): Either<Throwable, InetAddress> {
    val bestMatchingServerAddress =
        getLocalAddresses().maxBy { it.hostAddress.longestMatchingSubstring(bitsService.address).length }!!
    return Either.catch {
        sendServerIp(
            bitsService,
            handshakePort,
            bestMatchingServerAddress
        )
        bestMatchingServerAddress
    }.also {
        when (it) {
            is Either.Right ->
                registerService(oscConnections, bitsService, bestMatchingServerAddress, oscPort)
        }
    }
}

private fun registerService(
    oscConnections: MutableMap<ServiceName, OscConnection>,
    bitsService: BitsService,
    bestMatchingServerAddress: InetAddress,
    connectionPort: Int
) {
    oscConnections[bitsService.name] =
        OscConnection(
            bestMatchingServerAddress,
            connectionPort,
            InetAddress.getByName(bitsService.address),
            bitsService.port
        )
}

private suspend fun sendServerIp(
    bitsService: BitsService,
    handshakePort: Int,
    bestAddress: InetAddress?
) {
    val socket = aSocket(
        ActorSelectorManager(
            Dispatchers.IO
        )
    ).tcp()
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