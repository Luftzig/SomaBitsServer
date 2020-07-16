package se.kth.somabits.backend

import arrow.core.*
import io.ktor.application.*
import io.ktor.features.ContentNegotiation
import io.ktor.features.DataConversion
import io.ktor.html.respondHtml
import io.ktor.http.cio.websocket.*
import io.ktor.http.content.resource
import io.ktor.http.content.static
import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openWriteChannel
import io.ktor.response.respond
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.serialization.serialization
import io.ktor.util.cio.write
import io.ktor.websocket.webSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.html.*
import se.kth.somabits.common.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
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
                    defaultServerOscListeningPort(),
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
            val oscPort = defaultServerOscListeningPort()
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

        webSocket("/ws/connect/{service}/{pattern}") {
            log.info("Create websocket channel with ${call.parameters}")
            val serviceName = call.parameters["service"]?.let { it1 ->
                ServiceName(
                    it1
                )
            }
            val pattern = call.parameters["pattern"]
            if (serviceName == null || pattern == null) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing arguments"))
                return@webSocket
            }
            val connection = oscConnections[serviceName] ?: createNewConnection(
                serviceName,
                servicesManager.services,
                oscConnections
            )
            log.info("starting OSC session with ${serviceName?.name}:$pattern")
            send("Stream started")
            connection?.listenTo("/$pattern") {
                log.debug("Received message on connection ${serviceName?.name}/${pattern}")
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

private suspend fun Application.createNewConnection(
    serviceName: ServiceName,
    services: Map<ServiceName, BitsService>,
    oscConnections: MutableMap<ServiceName, OscConnection>
): OscConnection? {
    log.info("Creating new connection to ${serviceName.name}")
    val bitsService = services[serviceName]
    val oscPort = defaultServerOscListeningPort()
    val result = bitsService?.let { connectToService(it, defaultHandshakePort(), oscPort, oscConnections) }
    return when (result) {
        is Either.Right -> oscConnections[serviceName]
        is Either.Left -> {
            log.warn("Failed to retrieve OSC connection due to: ${result.a}")
            null
        }
        else -> {
            log.warn("No service by the name ${serviceName.name} was found")
            null
        }
    }
}

private suspend fun connectToService(
    bitsService: BitsService,
    handshakePort: Int,
    serverOscListeningPort: Int,
    oscConnections: MutableMap<ServiceName, OscConnection>
): Either<Throwable, InetAddress> {
    val bestMatchingServerAddress =
        getLocalAddresses().maxBy { it.hostAddress.longestMatchingSubstring(bitsService.address).length }!!
    registerService(oscConnections, bitsService, bestMatchingServerAddress, serverOscListeningPort)
    return Either.catch {
        sendServerIp(
            bitsService,
            handshakePort,
            bestMatchingServerAddress
        )
        bestMatchingServerAddress
    }
}

private fun registerService(
    oscConnections: MutableMap<ServiceName, OscConnection>,
    bitsService: BitsService,
    bestMatchingServerAddress: InetAddress,
    serverListeningPort: Int
) {
    oscConnections[bitsService.name] =
        OscConnection(
            bestMatchingServerAddress,
            serverListeningPort,
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
    log.debug("Sending server IP $bestAddress to device ${bitsService.name}")
    boundSocket.openWriteChannel(autoFlush = true).write("${bestAddress?.hostName}\r\n")
    boundSocket.close()
}

private fun Application.defaultDiscoveryServices() =
    environment.config.property("ktor.application.defaultDiscoveryServices").getList()

private fun Application.defaultServerOscListeningPort(): Int =
    environment.config.property("ktor.application.defaultOscListeningPort").getString().toInt()

private fun Application.defaultHandshakePort(): Int =
    environment.config.property("ktor.application.defaultHandshakePort").getString().toInt()