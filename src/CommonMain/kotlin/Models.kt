package se.kth.somabits.common

import kotlinx.serialization.*

/**
 * Wrapper to a mDNS style service name
 */
@Serializable
class ServiceName(val name: String) {
    @Serializer(forClass = ServiceName::class)
    companion object : KSerializer<ServiceName> {
        override val descriptor: SerialDescriptor
            get() = PrimitiveDescriptor("ServiceName", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): ServiceName =
            ServiceName(decoder.decodeString())

        override fun serialize(encoder: Encoder, value: ServiceName) {
            encoder.encodeString(value.name)
        }
    }
}


/**
 * Represents a single Soma Bit and its interfaces. A Soma bit device is a service available at a specific network
 * address, with a discovery style name etc. 'Bit 1._osc._udp.local.'.
 * @property interfaces is a list local service address such as '/Motor1' and information regarding it.
 */
@Serializable
data class BitsService(
    val name: ServiceName,
    val address: String,
    val port: Int,
    val interfaces: List<Pair<String, String>>
) {
    companion object {}
}

