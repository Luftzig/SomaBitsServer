package se.kth.somabits.backend

import com.illposed.osc.MessageSelector
import com.illposed.osc.OSCMessage
import com.illposed.osc.OSCMessageEvent
import com.illposed.osc.OSCMessageListener
import com.illposed.osc.messageselector.OSCPatternAddressMessageSelector
import com.illposed.osc.transport.udp.OSCPortIn
import com.illposed.osc.transport.udp.OSCPortInBuilder
import com.illposed.osc.transport.udp.OSCPortOut
import java.net.InetAddress
import java.net.InetSocketAddress

val allMessagesSelector = object : MessageSelector {
    override fun isInfoRequired(): Boolean = false

    override fun matches(messageEvent: OSCMessageEvent?): Boolean = true
}

class OscConnection(
    private val localAddress: InetAddress,
    val localPort: Int,
    val remoteAddress: InetAddress,
    val remotePort: Int
) {
    private val receiver: OSCPortIn = OSCPortInBuilder()
        .setLocalSocketAddress(InetSocketAddress(localAddress, localPort))
        .setRemoteSocketAddress(InetSocketAddress(remoteAddress, remotePort))
        .build()
//    private val receiver: OSCPortIn = OSCPortIn(InetSocketAddress(localAddress, localPort))
    val sender = OSCPortOut(remoteAddress, remotePort)

    init {
        log.info("Creating OSC connection to $remoteAddress")
        kotlin.runCatching {
            receiver.isResilient = true
            receiver.startListening()
        }.onFailure {
            log.warn("Failed to listen to service $this due to $it")
        }.onSuccess {
            log.debug("Started listening for connections on ${receiver.localAddress}:${localPort}, isListening? ${receiver.isListening}")
        }

        receiver.dispatcher.addBadDataListener {
            log.warn("Received Bad OSC message from ${remoteAddress}: $it")
        }
    }

    fun listenTo(pattern: String, listener: (OSCMessageEvent) -> Unit) {
        receiver.dispatcher.addListener(
            OSCPatternAddressMessageSelector(
                pattern
            ), OSCMessageListener { listener(it) })
    }

    fun send(toAddress: String, values: List<*>) {
        sender.send(OSCMessage(toAddress, values))
    }

    fun close() {
        receiver.stopListening()
    }

    fun isAlive() =
        receiver.isListening

}