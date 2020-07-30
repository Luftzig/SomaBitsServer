package se.kth.somabits.backend

import com.illposed.osc.MessageSelector
import com.illposed.osc.OSCMessage
import com.illposed.osc.OSCMessageEvent
import com.illposed.osc.OSCMessageListener
import com.illposed.osc.messageselector.OSCPatternAddressMessageSelector
import com.illposed.osc.transport.udp.OSCPortIn
import com.illposed.osc.transport.udp.OSCPortInBuilder
import com.illposed.osc.transport.udp.OSCPortOut
import javafx.application.Application.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import se.kth.somabits.common.BitsInterfaceType
import java.net.InetAddress
import java.net.InetSocketAddress

val allMessagesSelector = object : MessageSelector {
    override fun isInfoRequired(): Boolean = false

    override fun matches(messageEvent: OSCMessageEvent?): Boolean = true
}

sealed class OscConnection(
    val remoteAddress: InetAddress,
    val remotePort: Int
) {}

class SensorConnection(
    private val localAddress: InetAddress,
    val localPort: Int,
    remoteAddress: InetAddress,
    remotePort: Int
) : OscConnection(remoteAddress, remotePort) {
    private val receiver: OSCPortIn = OSCPortInBuilder()
        .setLocalSocketAddress(InetSocketAddress(localAddress, localPort))
        .setRemoteSocketAddress(InetSocketAddress(remoteAddress, remotePort))
        .build()

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

        log.debug("Initialized connection ${receiver.remoteAddress} from ${receiver.localAddress}")

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

    @ExperimentalCoroutinesApi
    suspend fun getListenChannel(scope: CoroutineScope, pattern: String): ReceiveChannel<OSCMessageEvent> {
        val channel = Channel<OSCMessageEvent>()
        receiver.dispatcher.addListener(
            OSCPatternAddressMessageSelector(
                pattern
            ), OSCMessageListener { scope.launch {
                channel.send(it)
            } })
        log.debug("Returning channel")
        return channel
    }

    fun isAlive() =
        receiver.isListening

    fun close() {
        receiver.stopListening()
    }
}

class ActuatorConnection(
    remoteAddress: InetAddress,
    remotePort: Int
) : OscConnection(remoteAddress, remotePort) {
    val sender = OSCPortOut(remoteAddress, remotePort)

    fun send(toAddress: String, values: List<*>) {
        sender.send(OSCMessage(toAddress, values))
    }
}

