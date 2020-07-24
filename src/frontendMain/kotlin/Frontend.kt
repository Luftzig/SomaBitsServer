package se.kth.somabits.frontend

import dev.fritz2.binding.*
import dev.fritz2.dom.Tag
import dev.fritz2.dom.html.HtmlElements
import dev.fritz2.dom.html.render
import dev.fritz2.dom.mount
import dev.fritz2.remote.body
import dev.fritz2.remote.onErrorLog
import dev.fritz2.remote.remote
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.ImplicitReflectionSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.list
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.w3c.dom.Element
import org.w3c.dom.events.MouseEvent
import se.kth.somabits.common.BitsDevice
import se.kth.somabits.common.BitsInterface
import se.kth.somabits.common.ServiceName
import kotlin.browser.window

const val devicesSampleRateMs: Long = 5000

fun buildWsUrl(path: String): String {
    val browserUrl = window.location
    return "ws://${browserUrl.host}/$path"
}

@ImplicitReflectionSerializer
class DevicesStore : RootStore<List<BitsDevice>>(emptyList(), id = "bits") {
    val deviceEndpoint = remote("/device").acceptJson().header("Content-Type", "application/json")

    val loadDevices = apply<Unit, List<BitsDevice>> {
        deviceEndpoint.get().onErrorLog()
            .body()
            .map { value ->
                val parsed = Json.parse(MapSerializer(String.serializer(), BitsDevice::class.serializer()), value)
                parsed.values
                    .toList()
            }
    } andThen update

    val connectToDevice = handle { model: List<BitsDevice>, service: ServiceName ->
        launch {
            remote("/device").acceptJson().header("Content-Type", "application/json")
                .post("/${service.name}/connect")
                .collect()
        }
        model
    }

    val disconnectFromDevice = handle { model, device: ServiceName ->
        launch {
            remote("/device").acceptJson().header("Content-Type", "application/json")
                .delete("/${device.name}/connect")
                .collect()
        }
        model
    }

    val timer = channelFlow {
        while (true) {
            send(Unit)
            delay(devicesSampleRateMs)
        }
    }

    init {
        timer handledBy loadDevices
    }
}

class ConnectionsStore : RootStore<Map<String, WebSocketConnection>>(emptyMap()) {
    val removePrefix: SimpleHandler<String> = handle { model, prefix ->
        model.minus(model.keys.filter { buildWebsocketUrl(ServiceName(it), "").startsWith(prefix) })
    }
    val toggle: SimpleHandler<String> = handle { data, addr ->
        console.log("Toggling socket for ${addr} in $data, ${addr in data}")
        if (addr in data) {
            data - addr
        } else {
            val webSocketConnection = WebSocketConnection(buildWsUrl(addr))
            console.log("Adding connection: $webSocketConnection")
            data + (addr to webSocketConnection)
        }
    }
}

@ImplicitReflectionSerializer
@ExperimentalCoroutinesApi
@FlowPreview
suspend fun main() {
    val addressStore = object : RootStore<List<String>>(listOf("Loading..."), id = "addresses") {
        val addresses = remote("/local-addresses").acceptJson().header("Content-Type", "application/json")

        val loadAddresses = apply<Unit, List<String>> {
            addresses.get().onErrorLog().body().map { Json.parse(String.serializer().list, it) }
        } andThen update

        val start = const(Unit)

        init {
            start.handledBy(loadAddresses)
        }
    }
    val devicesStore = DevicesStore()
    val messagesStore = ConnectionsStore()

    GlobalScope.launch {
        val initial = flowOf<Unit>()
            .onCompletion { println("Done") }
        initial handledBy addressStore.loadAddresses
        initial.launchIn(this)
        devicesStore.timer
            .onEach { println("Got devices") }
            .launchIn(this)
    }

    render {
        div("container") {
            div("col-md-12") {
                header(addressStore)

                div("row") {
                    div("col-md-12") {
                        table("table") {
                            caption { text("Known devices") }
                            thead {
                                tr {
                                    th { text("Name") }
                                    th { text("Address") }
                                    th { text("Interfaces") }
                                    th { }
                                }
                            }
                            tbody {
                                devicesStore.data.each().map(interfaceLine(messagesStore, devicesStore)).bind()
                            }
                        }
                    }
                }

                messages(messagesStore)
            }
        }
    }.mount("root")
}

@ImplicitReflectionSerializer
private fun interfaceLine(messagesStore: ConnectionsStore, deviceStore: DevicesStore): (BitsDevice) -> Tag<Element> =
    { device: BitsDevice ->
        render {
            tr {
                td { text(device.name.name) }
                td { text("${device.address}:${device.port}") }
                td {
                    device.interfaces.map { io ->
                        button {
                            clicks.map { buildWebsocketUrl(device.name, io.oscPattern) } handledBy messagesStore.toggle
                            text(io.oscPattern)
                        }
                    }
                }
                td {
                    button {
                        val clicksBroadcast = clicks.map { device.name }.broadcastIn(MainScope()).asFlow()
                        clicksBroadcast handledBy deviceStore.disconnectFromDevice
                        clicksBroadcast.map { it.name } handledBy messagesStore.removePrefix
                        text("Disconnect")
                    }
                }
            }
        }
    }

private fun buildWebsocketUrl(
    deviceName: ServiceName,
    pattern: String
): String = "ws/${deviceName.name}/connect/${pattern.dropWhile { it == '/' }}"

private fun HtmlElements.header(addressStore: RootStore<List<String>>) {
    div("row") {
        h1("col-md-8") { text("Somabits Server") }
        div("col-md-4") {
            div("row") {
                h4 { text("server IP(s)") }
            }
            div("row") {
                addressStore.data.each().map { addr ->
                    render { code("col-sm-6") { text(addr) } }
                }.bind()
            }
        }
    }
}

private fun HtmlElements.messages(messagesStore: RootStore<Map<String, WebSocketConnection>>) {
    div("row") {
        h3("col-md-12") { text("Connections: ") }
        div("col-md-12") {
            messagesStore.data
                .map { it.entries.toList() }
                .each()
                .map { (addr, socket) ->
                    render {
                        div("row") {
                            div("col-md-3") {
                                text(addr)
                            }
                            div("col-md-4") {
                                socket.messages.map {
                                    render { span { text(it.data.toString()) } }
                                }.bind()
                            }
                            div("col-md-4") {
                                socket.errors.map {
                                    console.warn("Error from websocket")
                                    console.warn(it)
                                    render {
                                        span { text(it.toString()) }
                                    }
                                }.bind()
                            }
                        }
                    }
                }.bind()
        }
    }
}
