package se.kth.somabits.frontend

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.w3c.dom.MessageEvent
import org.w3c.dom.WebSocket
import org.w3c.dom.events.Event
import org.w3c.files.Blob

@ExperimentalCoroutinesApi
open class WebSocketConnection(
    val baseUrl: String = ""
) {
    private val socket: WebSocket

    init {
        try {
            socket = WebSocket(baseUrl)
        } catch (ex: Throwable) {
            console.error("Error initializing websocket!")
            console.error(ex)
            throw ex
        }
        console.log("Creating store for $baseUrl")
    }

    val messages = channelFlow<MessageEvent> {
        socket.onmessage = {
            launch {
                send(it)
            }
        }
        awaitClose()
    }

    val errors = channelFlow<Event> {
        socket.onerror = {
            launch {
                console.log("Websocket: Got error $it")
                send(it)
            }
        }
        awaitClose()
    }

    fun close() = socket.close()

    fun send(data: String) = socket.send(data)
    fun send(data: Blob) = socket.send(data)
}