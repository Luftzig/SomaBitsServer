package se.kth.somabits.common

/**
 * Wrapper to a mDNS style service name
 */
inline class ServiceName(val name: String)

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
    companion object {}
}

