package se.kth.somabits.frontend

import dev.fritz2.binding.*
import dev.fritz2.dom.Tag
import dev.fritz2.dom.html.HtmlElements
import dev.fritz2.dom.html.render
import dev.fritz2.dom.mount
import dev.fritz2.dom.valuesAsNumber
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
import se.kth.somabits.common.BitsDevice
import se.kth.somabits.common.BitsInterface
import se.kth.somabits.common.BitsInterfaceType
import se.kth.somabits.common.ServiceName
import kotlin.browser.window

const val devicesSampleRateMs: Long = 5000

fun websocketPathToFullUrl(path: String): String {
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

typealias ConnectionId = Pair<ServiceName, BitsInterface>
typealias ConnectionsMap = Map<ConnectionId, WebSocketConnection>

@ExperimentalCoroutinesApi
class ConnectionsStore : RootStore<ConnectionsMap>(emptyMap()) {
    val addInterface: SimpleHandler<ConnectionId> = handle { model, connectionId: ConnectionId ->
        val (device, bitsIo) = connectionId
        val webSocket = WebSocketConnection(websocketPathToFullUrl(buildWebsocketUrl(device, bitsIo.oscPattern)))
        model + (connectionId to webSocket)
    }
    val removeInterface: SimpleHandler<ConnectionId> = handle { model, connectionId ->
        model[connectionId]?.close()
        model - (connectionId)
    }
    val removeDevice: SimpleHandler<ServiceName> = handle { model, service ->
        model.filterKeys { it.first == service }
            .onEach { it.value.close() }
            .keys
            .let {
                model - it
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
                                devicesStore.data
                                    .onEach { console.log("New device store $it") }
                                    .each().map(renderDevice(messagesStore, devicesStore)).bind()
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
private fun renderDevice(messagesStore: ConnectionsStore, deviceStore: DevicesStore): (BitsDevice) -> Tag<Element> =
    { device: BitsDevice ->
        render {
            tr {
                td { text(device.name.name) }
                td { text("${device.address}:${device.port}") }
                td {
                    device.interfaces.map { io ->
                        button {
                            clicks.map { device.name to io } handledBy messagesStore.addInterface
                            +(io.oscPattern)
                            +when (io.type) {
                                BitsInterfaceType.Sensor -> "⇥"
                                BitsInterfaceType.Actuator -> "⤇"
                                BitsInterfaceType.Unknown -> ""
                            }
                        }
                    }
                }
                td {
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

private fun HtmlElements.messages(messagesStore: ConnectionsStore) {
    div("row") {
        h3("col-md-12") { text("Connections: ") }
        div("col-md-12") {
            messagesStore.data
                .map { it.entries.toList() }
                .each()
                .map { (connectionId, socket) ->
                    val (deviceName, bitsIo) = connectionId
                    render {
                        div("row") {
                            div("col-md-3") {
                                +"${deviceName.name} ${bitsIo.oscPattern}"
                            }
                            div("col-md-5") {
                                when (bitsIo.type) {
                                    BitsInterfaceType.Unknown -> +"Unknown"
                                    BitsInterfaceType.Sensor ->
                                        socket.messages
                                            .map {
                                                it.data.toString().split(":").drop(1)
                                            }
                                            .map {
                                                render { span { text(it.joinToString(",")) } }
                                            }.bind()
                                    BitsInterfaceType.Actuator ->
                                        input(id = connectionId.toString()) {
                                            type = const("range")
                                            name = const(connectionId.toString())
                                            bitsIo.range?.let {
                                                min = const(bitsIo.range.first.toString())
                                                max = const(bitsIo.range.second.toString())
                                            }
                                            changes.valuesAsNumber()
                                                .onEach { socket.send(it.toString()) }
                                                .map {
                                                    render {
                                                        label(`for` = connectionId.toString()) {
                                                            +it.toString()
                                                        }
                                                    }
                                                }.bind()
                                        }
                                }
                            }
                            div("col-md-3") {
                                socket.errors.map {
                                    console.warn("Error from websocket")
                                    console.warn(it)
                                    render {
                                        span { text(it.toString()) }
                                    }
                                }.bind()
                                button {
                                    +"×"
                                    clicks.map { connectionId } handledBy messagesStore.removeInterface
                                }
                            }
                        }
                    }
                }.bind()
        }
    }
}
