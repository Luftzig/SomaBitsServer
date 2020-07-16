package se.kth.somabits.frontend

import kotlinx.coroutines.flow.flow
import org.w3c.dom.WebSocket
import org.w3c.files.Blob

open class WebSocketConnection(
    val baseUrl: String = ""
) {
    private val socket : WebSocket
    init {
        try {
            socket = WebSocket(baseUrl)
        } catch (ex : Throwable) {
            console.error("Error initializing websocket!")
            console.error(ex)
            throw ex
        }
        console.log("Creating store for $baseUrl")
    }

    val messages = flow {
        socket.onmessage = {
            suspend {
                this.emit(it)
            }
        }
    }

    val errors = flow {
        socket.onerror = {
            suspend {
                this.emit(it)
            }
        }
    }

    fun close() = socket.close()

    fun send(data: String) = socket.send(data)
    fun send(data: Blob) = socket.send(data)
}