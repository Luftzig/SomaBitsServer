package se.kth.somabits.frontend

import dev.fritz2.binding.*
import dev.fritz2.dom.Tag
import dev.fritz2.dom.html.Canvas
import dev.fritz2.dom.html.Div
import dev.fritz2.dom.html.HtmlElements
import dev.fritz2.dom.html.render
import dev.fritz2.dom.mount
import dev.fritz2.dom.valuesAsNumber
import dev.fritz2.remote.getBody
import dev.fritz2.remote.remote
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.ImplicitReflectionSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.list
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.Element
import se.kth.somabits.common.*
import kotlin.browser.window

const val devicesSampleRateMs: Long = 5000

fun websocketPathToFullUrl(path: String): String {
    val browserUrl = window.location
    return "ws://${browserUrl.host}/$path"
}

@ImplicitReflectionSerializer
class DevicesStore : RootStore<List<BitsDevice>>(emptyList(), id = "bits") {
    val deviceEndpoint = remote("/device").acceptJson().header("Content-Type", "application/json")

    val loadDevices = handleAndOffer<Unit> { model ->
        deviceEndpoint.get()
            .getBody()
            .let { value ->
                val parsed = Json.parse(MapSerializer(String.serializer(), BitsDevice::class.serializer()), value)
                parsed.values.toList()
            } as? List<BitsDevice> ?: model
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
    val clearMissing = handle<List<BitsDevice>> { model, deviceList ->
        val serviceNames = deviceList.map { it.name }.toHashSet()
        model.filterKeys { (service, _) ->
            service !in serviceNames
        }.onEach {
            it.value.close()
        }.let {
            model - it.keys
        }
    }
}

@ImplicitReflectionSerializer
@ExperimentalCoroutinesApi
@FlowPreview
suspend fun main() {
    val addressStore = object : RootStore<List<String>>(listOf("Loading..."), id = "addresses") {
        val addresses = remote("/local-addresses").acceptJson().header("Content-Type", "application/json")

        val loadAddresses = handleAndOffer<Unit> { model ->
            addresses.get().getBody().let { obj ->
                Json.parse(String.serializer().list, obj)
            } as? List<String> ?: model
        }
    }
    val devicesStore = DevicesStore()
    val messagesStore = ConnectionsStore()
    devicesStore.data handledBy messagesStore.clearMissing
    action() handledBy addressStore.loadAddresses

    GlobalScope.launch {
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
                            +if (io.units != "Unknown") " ${io.units}" else ""
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

private fun HtmlElements.messages(messagesStore: ConnectionsStore): Div =
    div("row") {
        h3("col-md-12") { text("Connections: ") }
        div("col-md-12") {
            messagesStore.data
                .map { it.entries.toList() }
                .each()
                .render { (connectionId, socket) ->
                    val (deviceName, bitsIo) = connectionId
                    div("row") {
                        div("col-md-3") {
                            +"${deviceName.name} ${bitsIo.oscPattern}"
                        }
                        div("col-md-5") {
                            bitsInterface(bitsIo, socket, deviceName)
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
                }.bind()
        }
    }


@FlowPreview
@ExperimentalCoroutinesApi
private fun HtmlElements.bitsInterface(
    bitsIo: BitsInterface,
    socket: WebSocketConnection,
    deviceName: ServiceName
): Tag<*> =
    when (bitsIo.type) {
        BitsInterfaceType.Unknown -> span { +"Unknown" }
        BitsInterfaceType.Sensor -> {
            // Take last 300 samples, emit at most once in 100ms
            val points = socket.messages.windowed(300).sample(100).map {
                it.mapNotNull { event -> event.data.toString().toDoubleOrNull() }
            }
            readingsPlot(points, bitsIo.range)
        }
        BitsInterfaceType.Actuator -> {
            val inputName = "${deviceName.name}-${bitsIo.type}-${bitsIo.id}"
            val valueStore = storeOf(0.0)
            input(id = inputName) {
                type = const("range")
                name = const(inputName)
                bitsIo.range?.let {
                    min = const(bitsIo.range.first.toString())
                    max = const(bitsIo.range.second.toString())
                }
                defaultValue = flowOf("0")
                changes.valuesAsNumber()
                    .repeatEvery(50)
                    .onEach { socket.send(it.toString()) } handledBy valueStore.update
            }
            span {
                valueStore.data.render {
                    label(`for` = inputName) {
                        +it.toString()
                    }
                }.bind()
            }
        }
    }

private fun HtmlElements.readingsPlot(points: Flow<List<Double>>, range: Pair<Int, Int>?): Canvas {
    val widthPx = 300.0
    val gutterPx = 32.0
    val heightPx = 56.0
    val canvas = canvas {
        width = flowOf((widthPx + gutterPx).toInt())
        height = flowOf(heightPx.toInt())
    }
    GlobalScope.launch {
        points.collect { points ->
            val ctx = canvas.domNode.getContext("2d") as CanvasRenderingContext2D
            val displayableData = points.take(widthPx.toInt())
            fun yPosition(v: Double): Double =
                heightPx - ((heightPx / (range?.second ?: 1)) * 0.9 * v + (0.1 * heightPx))
            ctx.clearRect(0.0, 0.0, widthPx + gutterPx, heightPx)
            ctx.beginPath()
            ctx.lineWidth = 1.0
            displayableData.firstOrNull()?.let {
                ctx.moveTo(0.0, yPosition(it))
            }
            displayableData.forEachIndexed { idx, v ->
                ctx.lineTo(idx.toDouble(), yPosition(v))
            }
            ctx.strokeStyle = "rgb(0, 0, 0)"
            ctx.stroke()

            val fontSize = 9
            ctx.font = "${fontSize}px"
            ctx.fillText((range?.first ?: 0).toString(), widthPx + 4.0, heightPx)
            ctx.fillText((range?.second ?: 1).toString(), widthPx + 4.0, fontSize.toDouble())
            displayableData.lastOrNull()?.let {
                val pos = yPosition(it)
                if (pos > fontSize * 2 && pos < heightPx - (fontSize)) {
                    ctx.fillText(it.toString(), widthPx + 4.0, pos)
                }
            }
        }
    }
    return canvas
}

