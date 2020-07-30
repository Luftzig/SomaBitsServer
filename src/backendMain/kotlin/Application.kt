package se.kth.somabits.backend

import arrow.core.*
import io.ktor.application.*
import io.ktor.features.ContentNegotiation
import io.ktor.features.DataConversion
import io.ktor.html.respondHtml
import io.ktor.http.HttpStatusCode
import io.ktor.http.cio.websocket.*
import io.ktor.http.content.resource
import io.ktor.http.content.static
import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openWriteChannel
import io.ktor.response.respond
import io.ktor.routing.*
import io.ktor.serialization.serialization
import io.ktor.util.cio.write
import io.ktor.util.pipeline.PipelineContext
import io.ktor.websocket.webSocket
import io.netty.handler.codec.spdy.SpdyHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.consume
import kotlinx.coroutines.channels.consumeEach
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
@ExperimentalCoroutinesApi
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
        BonjourServicesManager(
            null,
            defaultDiscoveryServices().map { ServiceName(it) },
            defaultDiscoveryServices().associate {
                ServiceName(it) to AdvertiseServerData(
                    defaultServerOscListeningPort(),
                    mapOf("server" to "")
                )
            })

    val oscConnections: MutableMap<Pair<ServiceName, BitsInterface>, OscConnection> = mutableMapOf()

    routing {
        get("/", serveFrontend())

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

        route("/device") {
            get {
                call.respond(servicesManager.services)
            }

            route("{name}/connect") {
                post {
                    val serviceName = call.parameters["name"]?.let { it1 ->
                        ServiceName(
                            it1
                        )
                    }
                    val handshakePort = call.request.queryParameters["usePort"]?.toIntOrNull() ?: defaultHandshakePort()
                }

                delete {
                    call.parameters["name"]?.let { it1 ->
                        ServiceName(
                            it1
                        )
                    }?.let { name ->
                        val toRemove = oscConnections.filterKeys { (devName, _) -> name == devName }.keys
                        if (toRemove.isNotEmpty()) {
                            oscConnections.minusAssign(toRemove)
                            call.respond(HttpStatusCode.NoContent)
                        } else {
                            call.respond(HttpStatusCode.NotFound)
                        }
                    }
                }
            }
        }

        webSocket("/ws/{device}/connect/{pattern...}") {
            log.info("Creating websocket channel with ${call.parameters}")
            val serviceName = call.parameters["device"]?.let { it1 ->
                ServiceName(it1)
            }
            val pattern = call.parameters.getAll("pattern")
            if (serviceName == null || pattern.isNullOrEmpty()) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing arguments"))
                return@webSocket
            }
            val deviceAndInterface = getDeviceAndInterface(serviceName, pattern, servicesManager.services)
            val connection = deviceAndInterface?.let { (device, io): Pair<BitsDevice, BitsInterface> ->
                oscConnections.computeIfAbsent(device.name to io) {
                    when (io.type) {
                        BitsInterfaceType.Unknown -> TODO()
                        BitsInterfaceType.Sensor -> {
                            SensorConnection(
                                bestMatchingAddress(device.address),
//                                device.port,
                                32000,
                                InetAddress.getByName(device.address),
                                device.port
                            )
                        }
                        BitsInterfaceType.Actuator -> ActuatorConnection(
                            InetAddress.getByName(device.address),
                            device.port
                        )
                    }
                }
            }
            when (connection) {
                is SensorConnection -> {
                    try {
                        connection.getListenChannel(CoroutineScope(coroutineContext), "/${pattern.joinToString("/")}")
                            .consumeEach {
                                outgoing.send(Frame.Text(it.toString()))
                            }
                    } finally {
                        connection.close()
                        close(CloseReason(CloseReason.Codes.GOING_AWAY, "BitsDevice is offline"))
                    }
                }
                is ActuatorConnection -> {
                    try {
                        incoming.consumeEach { frame ->
                            when (frame) {
                                is Frame.Text ->
                                    connection.send("/${pattern.joinToString("/")}", listOf(frame.readText()))
                            }
                        }
                    } finally {
                        close(CloseReason(CloseReason.Codes.NORMAL, "Ended"))
                    }
                }
                null -> {
                    close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Failed to connect"))
                }
            }
            log.info("Connection ended to $serviceName")
        }
    }
}

private fun bestMatchingAddress(remoteAddress: String): InetAddress {
    return getLocalAddresses().maxBy { it.hostAddress.longestMatchingSubstring(remoteAddress).length }!!
}

private fun getDeviceAndInterface(
    name: ServiceName,
    pattern: List<String>,
    devices: Map<ServiceName, BitsDevice>
): Pair<BitsDevice, BitsInterface>? {
    val device = devices.get(name)
    val io = device?.interfaces?.find { it.oscPattern == "/${pattern.joinToString("/")}" }
    return device?.let { d -> io?.let { io1 -> d to io1 } }
}

private fun serveFrontend(): suspend PipelineContext<Unit, ApplicationCall>.(Unit) -> Unit {
    return {
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
}

private fun Application.defaultDiscoveryServices() =
    environment.config.property("ktor.application.defaultDiscoveryServices").getList()

private fun Application.defaultServerOscListeningPort(): Int =
    environment.config.property("ktor.application.defaultOscListeningPort").getString().toInt()

private fun Application.defaultHandshakePort(): Int =
    environment.config.property("ktor.application.defaultHandshakePort").getString().toInt()