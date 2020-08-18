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
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.consume
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.map
import kotlinx.coroutines.channels.mapNotNull
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.mapNotNull
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
@OptIn(InternalCoroutinesApi::class)
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

    val oscConnections = OscManager(defaultServerOscListeningPort())

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
                    call.parameters["name"]?.let {
                        ServiceName(it)
                    }?.let { name ->
                        servicesManager.services[name]
                    }?.let { bitsDevice: BitsDevice ->
                        oscConnections.closeAll(bitsDevice.address)
                        call.respond(HttpStatusCode.NoContent)
                    } ?: run {
                        call.respond(HttpStatusCode.NotFound)
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
            if (deviceAndInterface == null) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "No pattern ${pattern.joinToString("/")} for $serviceName"))
                return@webSocket
            }
            val patternString = "/${pattern.joinToString("/")}"
            val (device, io) = deviceAndInterface
            when (io.type) {
                BitsInterfaceType.Unknown -> {
                    close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Failed to connect"))
                }
                BitsInterfaceType.Sensor -> {
                    try {
                        oscConnections.incomingFrom(device.address, patternString, this).consumeEach {
                            outgoing.send(Frame.Text("${it.message.arguments.first()}"))
                        }
                    } catch (e: Throwable) {
                        log.error("Connection ${device.address}/$patternString terminated due to unexpected error", e)
                    } finally {
                        oscConnections.close(device.address, patternString)
                        close(CloseReason(CloseReason.Codes.GOING_AWAY, "BitsDevice is offline"))
                    }
                }
                BitsInterfaceType.Actuator -> {
                    try {
                        oscConnections.sendTo(
                            device.address,
                            device.port,
                            patternString,
                            incoming.consumeAsFlow().mapNotNull { frame ->
                                when (frame) {
                                    is Frame.Text -> listOf(frame.readText().toFloat())
                                    else -> null
                                }
                            })
                    } finally {
                        close(CloseReason(CloseReason.Codes.NORMAL, "Ended"))
                    }
                }
            }
            log.info("Connection ended to $serviceName:$patternString")
        }
    }
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