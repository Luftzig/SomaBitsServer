package se.kth.somabits.backend

import com.illposed.osc.OSCMessage
import com.illposed.osc.OSCMessageEvent
import com.illposed.osc.OSCMessageListener
import com.illposed.osc.messageselector.OSCPatternAddressMessageSelector
import com.illposed.osc.transport.udp.OSCPortIn
import com.illposed.osc.transport.udp.OSCPortOut
import java.net.InetAddress
import java.net.InetSocketAddress

class OscConnection(
    private val localAddress: InetAddress,
    val localPort: Int,
    val remoteAddress: InetAddress,
    val remotePort: Int
) {
    private val receiver = OSCPortIn(
        InetSocketAddress(
            localAddress,
            localPort
        )
    )
    val sender = OSCPortOut(remoteAddress, remotePort)

    fun listenTo(pattern: String, listener: (OSCMessageEvent) -> Unit) {
        receiver.dispatcher.addListener(
            OSCPatternAddressMessageSelector(
                pattern
            ), OSCMessageListener { listener(it) })
    }

    fun send(toAddress: String, values: List<*>) {
        sender.send(OSCMessage(toAddress, values))
    }
}