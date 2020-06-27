package se.kth.somabits

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.application.log
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
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.websocket.webSocket
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.NetworkInterface
import java.text.DateFormat
import java.time.Duration
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

data class BitsService(val address: String, val port: Int, val interfaces: List<Pair<String, String>>) {
    companion object {
        fun from(serviceInfo: ServiceInfo): BitsService =
            BitsService(serviceInfo.hostAddresses.first(), serviceInfo.port, parseInterfaces(serviceInfo.textBytes))

        private fun parseInterfaces(textBytes: ByteArray?): List<Pair<String, String>> =
            textBytes?.let { parseBonjourTxtRecord(it) }.orEmpty()

        private fun parseBonjourTxtRecord(bytes: ByteArray): List<Pair<String, String>> =
            bytes.toList().breakWith {
                val length = it.first().toUShort().toInt()
                Pair(it.drop(1).take(length), it.drop(length + 1))
            }.map {
                it.toByteArray().toString(Charsets.UTF_8)
            }.map {
                it.split("=")
            }.map {
                Pair(it.first(), it.drop(1).joinToString(""))
            }
    }
}

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

    val mDNSInstance: JmDNS = getLocalAddresses().first().let { JmDNS.create(it) }
    val services: MutableMap<ServiceName, BitsService> = mutableMapOf()

    defaultDiscoveryServices().forEach { registerServiceListeners(mDNSInstance, it, services) }

    routing {
        get("/") {
            call.respondText("HELLO WORLD!", contentType = ContentType.Text.Plain)
        }

        get("/local-addresses") {
            call.respond(getLocalAddresses().map { it.hostAddress })
        }

        get("/discover") {
            call.respond(services)
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
    services: MutableMap<ServiceName, BitsService>
) {
    log.debug("Listen to new service $service")
    mDNSInstance.addServiceListener(service, object : ServiceListener {
        override fun serviceResolved(event: ServiceEvent?) {
            log.info("Service $service resolved on ${mDNSInstance.name}: ${event}")
            event?.run { services.put(ServiceName(event.name), BitsService.from(event.info)) }
        }

        override fun serviceRemoved(event: ServiceEvent?) {
            log.info("Service $service removed on ${mDNSInstance.name}: ${event}")
            event?.run { services.remove(ServiceName(event.name)) }
        }

        override fun serviceAdded(event: ServiceEvent?) {
            log.info("Service $service added on ${mDNSInstance.name}: ${event}")
            event?.run { services.putIfAbsent(ServiceName(event.name), BitsService.from(event.info)) }
        }
    })
}

private fun Application.defaultDiscoveryServices() =
    environment.config.property("ktor.application.defaultDiscoveryServices").getList()

fun <T> List<T>.breakWith(selector: (List<T>) -> Pair<List<T>, List<T>>): List<List<T>> =
    if (this.isNotEmpty()) {
        val (part, rest) = selector(this)
        listOf(part) + rest.breakWith(selector)
    } else listOf()
