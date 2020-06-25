package se.kth.somabits

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.request.*
import io.ktor.features.*
import io.ktor.gson.gson
import io.ktor.routing.*
import io.ktor.http.*
import io.ktor.websocket.*
import io.ktor.http.cio.websocket.*
import io.ktor.util.pipeline.PipelineContext
import java.time.*
import java.net.InetAddress
import java.net.NetworkInterface
import java.text.DateFormat
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

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

    val mDNSInstance: JmDNS = getLocalAddresses().first().let { JmDNS.create(it) }
    val services: MutableMap<ServiceName, ServiceInfo> = mutableMapOf()

    defaultDiscoveryServices().forEach { registerServiceListeners(mDNSInstance, it, services) }

    routing {
        get("/") {
            call.respondText("HELLO WORLD!", contentType = ContentType.Text.Plain)
        }

        get("/local-addresses") {
            call.respond(getLocalAddresses().map { it.hostAddress })
        }

        get("/discover") {
            call.respond(services.mapValues { serviceEntry ->
                mapOf(
                    "urls" to serviceEntry.value.urLs,
                    "addresses" to serviceEntry.value.inetAddresses.map { it.hostAddress },
                    "address" to serviceEntry.value.hostAddresses,
                    "port" to serviceEntry.value.port
                )
            })
        }

        post("/discover") {
            val service = call.request.queryParameters["service"]
            registerServiceListeners(mDNSInstance, service, services)
            call.respond(StatusResponse("ok"))
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

private fun Application.registerServiceListeners(
    mDNSInstance: JmDNS,
    service: String?,
    services: MutableMap<ServiceName, ServiceInfo>
) {
    log.debug("Listen to new service $service")
    mDNSInstance.addServiceListener(service, object : ServiceListener {
        override fun serviceResolved(event: ServiceEvent?) {
            log.info("Service $service resolved on ${mDNSInstance.name}: ${event}")
        }

        override fun serviceRemoved(event: ServiceEvent?) {
            log.info("Service $service removed on ${mDNSInstance.name}: ${event}")
            event?.run { services.remove(ServiceName(event.name)) }
        }

        override fun serviceAdded(event: ServiceEvent?) {
            log.info("Service $service added on ${mDNSInstance.name}: ${event}")
            event?.run { services.putIfAbsent(ServiceName(event.name), event.info) }
        }
    })
}

private fun Application.defaultDiscoveryServices() =
    environment.config.property("ktor.application.defaultDiscoveryServices").getList()
