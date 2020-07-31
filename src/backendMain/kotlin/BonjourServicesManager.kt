package se.kth.somabits.backend

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import org.slf4j.LoggerFactory
import se.kth.somabits.common.BitsDevice
import se.kth.somabits.common.BitsInterface
import se.kth.somabits.common.BitsInterfaceType
import se.kth.somabits.common.ServiceName
import java.net.InetAddress
import javax.jmdns.*
import kotlin.system.exitProcess

const val SOMA_BITS_BONJOUR_SERVICE_NAME = "_osc._udp.local."

const val SERVER_SUB_TYPE = "_server"

val log = LoggerFactory.getLogger(BonjourServicesManager::class.qualifiedName)!!

data class AdvertiseServerData(
    val port: Int,
    val properties: Map<String, String> = emptyMap(),
    val weight: Int = 0,
    val priority: Int = 0
)

class BonjourServicesManager(
    val address: InetAddress?,
    val servicesToUse: Iterable<ServiceName>,
    val advertiseServers: Map<ServiceName, AdvertiseServerData>
) {
    val mDNSInstance: JmDNS = JmDNS.create(address)
    val services: MutableMap<ServiceName, BitsDevice> = mutableMapOf()

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
                            BitsDevice.from(event.info)
                        )
                    }
                }

                override fun serviceRemoved(event: ServiceEvent?) {
                    log.debug("Service $it removed on ${mDNSInstance.name}: $event")
                    event?.run { services.remove(ServiceName(event.name)) }
                }

                override fun serviceAdded(event: ServiceEvent?) {
                    log.debug("Service $it added on ${mDNSInstance.name}: $event")
                    event?.run {
                        services.putIfAbsent(
                            ServiceName(
                                event.name
                            ),
                            BitsDevice.from(event.info)
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


fun BitsDevice.Companion.from(serviceInfo: ServiceInfo): BitsDevice =
    BitsDevice(
        ServiceName(serviceInfo.name),
        serviceInfo.hostAddresses.first(),
        serviceInfo.port,
        parseInterfaces(
            serviceInfo.textBytes
        )
    )

fun parseInterfaces(textBytes: ByteArray?): List<BitsInterface> =
    textBytes?.let { parseBonjourTxtRecord(it) }
        .orEmpty()
        .map {
            BitsInterface(
                type = extractInterfaceType(it.first),
                id = extractInterfaceId(it.first),
                oscPattern = extractInterfaceChannel(it.second),
                range = extractInterfaceRange(it.second),
                units = extractInterfaceUnits(it.second)
            )
        }

fun extractInterfaceUnits(value: String): String =
    value.split(":")
        .getOrElse(2) { "Unknown" }

fun extractInterfaceRange(value: String): Pair<Int, Int>? =
    value.split(":")
        .getOrNull(1)
        ?.split("%")
        ?.take(2)
        ?.mapNotNull { it.toIntOrNull() }
        ?.let { Pair(it[0], it[1]) }


fun extractInterfaceChannel(value: String): String = value.split(":").firstOrNull() ?: "N/A"

fun extractInterfaceId(key: String): String =
    Regex("\\d+$").find(key)?.value ?: "N/A"

fun extractInterfaceType(key: String): BitsInterfaceType = when {
    key.toLowerCase().startsWith("sensor") -> BitsInterfaceType.Sensor
    key.toLowerCase().startsWith("actuator") -> BitsInterfaceType.Actuator
    else -> BitsInterfaceType.Unknown
}

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

fun <T> List<T>.breakWith(selector: (List<T>) -> Pair<List<T>, List<T>>): List<List<T>> =
    if (this.isNotEmpty()) {
        val (part, rest) = selector(this)
        listOf(part) + rest.breakWith(selector)
    } else listOf()