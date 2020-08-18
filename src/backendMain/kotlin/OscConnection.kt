package se.kth.somabits.backend

import com.illposed.osc.*
import com.illposed.osc.messageselector.OSCPatternAddressMessageSelector
import com.illposed.osc.transport.udp.OSCPortIn
import com.illposed.osc.transport.udp.OSCPortOut
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import se.kth.somabits.common.ConnectionId
import java.net.InetAddress
import java.net.InetSocketAddress

val allMessagesSelector = object : MessageSelector {
    override fun isInfoRequired(): Boolean = true

    override fun matches(messageEvent: OSCMessageEvent?): Boolean {
        return true
    }
}

class RemoteAndPatternOscSelector(val remote: InetAddress, val pattern: String) :
    OSCPatternAddressMessageSelector(pattern) {
    override fun isInfoRequired(): Boolean = false

    override fun matches(messageEvent: OSCMessageEvent?): Boolean =
        when (val source = messageEvent?.source) {
        is OSCPortIn.OSCPortInSource ->
            (source.sender as? InetSocketAddress)?.address == remote
                    && super.matches(messageEvent)
        else -> false
    }
}

typealias OSCSelectorAndListener = Pair<MessageSelector, OSCMessageListener>
typealias UDPPort = Int
typealias RemoteID = String
typealias OscPattern = String

@InternalCoroutinesApi
class OscManager(val listenPort: Int) {
    val listeners: MutableMap<Pair<RemoteID, OscPattern>, OSCSelectorAndListener> = mutableMapOf()
    val incomingConnections: MutableMap<UDPPort, OSCPortIn> = mutableMapOf()
    val outgoingConnections: MutableMap<Pair<RemoteID, UDPPort>, OSCPortOut> = mutableMapOf()

    suspend fun incomingFrom(
        remoteAddress: RemoteID,
        listeningPort: UDPPort,
        pattern: OscPattern,
        scope: CoroutineScope = MainScope()
    ): ReceiveChannel<OSCMessageEvent> =
        incomingConnections.computeIfAbsent(listeningPort) { port ->
            OSCPortIn(port)
        }.let { port ->
            val channel = Channel<OSCMessageEvent>()
            val (selector, listener) = listeners.computeIfAbsent(remoteAddress to pattern) { address ->
                val selector = RemoteAndPatternOscSelector(InetAddress.getByName(remoteAddress), pattern)
                val channelListener = OSCMessageListener {
                    scope.launch {
                        channel.send(it)
                    }
                }
                selector to channelListener
            }
            port.dispatcher.addListener(selector, listener)
            port.startListening()
            log.debug("Returning channel for $port")
            channel
        }

    suspend fun incomingFrom(
        remoteAddress: String,
        pattern: String,
        scope: CoroutineScope = MainScope()
    ): ReceiveChannel<OSCMessageEvent> =
        incomingFrom(remoteAddress, listenPort, pattern, scope)

    suspend fun sendTo(remoteAddress: String, remotePort: UDPPort, pattern: String, data: Flow<List<*>>) {
        outgoingConnections.computeIfAbsent(remoteAddress to remotePort) { (address, port): Pair<RemoteID, UDPPort> ->
            OSCPortOut(InetAddress.getByName(address), port)
        }.let { portOut ->
            data.collect { input ->
                portOut.send(OSCMessage(pattern, input))
            }
        }
    }

    fun close(remoteAddress: RemoteID, pattern: OscPattern) {
        incomingConnections.values.forEach { port ->
            listeners[remoteAddress to pattern]?.let { (selector, listener) ->
                port.dispatcher.removeListener(selector, listener)
            }
            listeners.remove(remoteAddress to pattern)
        }
    }

    fun closeAll(remoteAddress: RemoteID) {
        incomingConnections.values.forEach { port ->
            val targetListeners = listeners.filterKeys { (remote, _) -> remote == remoteAddress }
            targetListeners.values.forEach { (selector, listener) ->
                port.dispatcher.removeListener(selector, listener)
            }
            listeners -= targetListeners.keys
        }
    }
}

