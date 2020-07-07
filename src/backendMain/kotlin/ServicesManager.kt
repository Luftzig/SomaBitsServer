package se.kth.somabits.backend

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import org.slf4j.LoggerFactory
import se.kth.somabits.common.ServiceName
import java.net.InetAddress
import javax.jmdns.*
import kotlin.system.exitProcess

const val OSC_SERVICE_TYPE = "_osc._udp.local."

const val SERVER_SUB_TYPE = "_server"

val log = LoggerFactory.getLogger(ServicesManager::class.qualifiedName)!!

data class AdvertiseServerData(
    val port: Int,
    val properties: Map<String, String> = emptyMap(),
    val weight: Int = 0,
    val priority: Int = 0
)

class ServicesManager(
    val address: InetAddress?,
    val servicesToUse: Iterable<ServiceName>,
    val advertiseServers: Map<ServiceName, AdvertiseServerData>
) {
    val mDNSInstance: JmDNS = JmDNS.create(address)
    val services: MutableMap<ServiceName, BitsService> = mutableMapOf()

    init {
        log.debug("Starting listeners for $servicesToUse on $address")
        servicesToUse.forEach {
            mDNSInstance.addServiceListener(it.name, object : ServiceListener {
                override fun serviceResolved(event: ServiceEvent?) {
                    log.debug("Service $it resolved on ${mDNSInstance.name}: $event")
                    event?.run {
                        services.put(
                            ServiceName(
                                event.name
                            ),
                            BitsService.from(event.info)
                        )
                    }
                }

                override fun serviceRemoved(event: ServiceEvent?) {
                    log.debug("Service $it removed on ${mDNSInstance.name}: $event")
//                    event?.run { services.remove(ServiceName(event.name)) }
                }

                override fun serviceAdded(event: ServiceEvent?) {
                    log.debug("Service $it added on ${mDNSInstance.name}: $event")
                    event?.run {
                        services.putIfAbsent(
                            ServiceName(
                                event.name
                            ),
                            BitsService.from(event.info)
                        )
                    }
                }
            })
        }
        advertiseServers.entries.forEach { (service, serverData) ->
            mDNSInstance.registerService(
                ServiceInfo.create(
                    service.name,
                    address?.hostAddress,
                    SERVER_SUB_TYPE,
                    serverData.port,
                    serverData.weight,
                    serverData.priority,
                    serverData.properties
                )
            )
        }
    }
}

fun main(arg: Array<String>) {
    val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
    loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).level = Level.INFO
    val jmDnsInstance = JmDNS.create()
    jmDnsInstance.addServiceListener(OSC_SERVICE_TYPE, object : ServiceListener {
        override fun serviceResolved(event: ServiceEvent?) {
//            println("Resolved: $event")
        }

        override fun serviceRemoved(event: ServiceEvent?) {
            println("Removed: $event")
        }

        override fun serviceAdded(event: ServiceEvent?) {
            println("Added: $event")
        }

    })
    while (true) {
        val line = readLine()?.split(" ")
        when (line?.first()) {
            "q", "quit" -> exitProcess(0)
            "l", "list" -> println(jmDnsInstance.list(OSC_SERVICE_TYPE))
            "p", "print" -> if (line.size > 1) {
                val serviceName = line.drop(1).joinToString(" ")
                println("$serviceName:")
                val serviceInfo = jmDnsInstance.getServiceInfo(OSC_SERVICE_TYPE, serviceName)
                println(serviceInfo.hostAddresses.joinToString(", "))
                println(serviceInfo.port)
                println(serviceInfo.niceTextString)
            }
        }
    }
}

/**
 * Represents a single Soma Bit and its interfaces. A Soma bit device is a service available at a specific network
 * address, with a discovery style name etc. 'Bit 1._osc._udp.local.'.
 * @property interfaces is a list local service address such as '/Motor1' and information regarding it.
 */
data class BitsService(
    val name: ServiceName,
    val address: String,
    val port: Int,
    val interfaces: List<Pair<String, String>>
) {
    companion object {
        fun from(serviceInfo: ServiceInfo): BitsService =
            BitsService(
                ServiceName(serviceInfo.name),
                serviceInfo.hostAddresses.first(),
                serviceInfo.port,
                parseInterfaces(
                    serviceInfo.textBytes
                )
            )

        private fun parseInterfaces(textBytes: ByteArray?): List<Pair<String, String>> =
            textBytes?.let {
                parseBonjourTxtRecord(
                    it
                )
            }.orEmpty()

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

fun <T> List<T>.breakWith(selector: (List<T>) -> Pair<List<T>, List<T>>): List<List<T>> =
    if (this.isNotEmpty()) {
        val (part, rest) = selector(this)
        listOf(part) + rest.breakWith(selector)
    } else listOf()