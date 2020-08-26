package se.kth.somabits.frontend

import dev.fritz2.binding.*
import dev.fritz2.dom.Tag
import dev.fritz2.dom.html.*
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
import org.w3c.dom.HTMLElement
import se.kth.somabits.common.*
import kotlin.browser.window
import kotlin.collections.Map

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
            custom("style") { domNode.innerText = css.render() }
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

@ExperimentalCoroutinesApi
private fun HtmlElements.messages(messagesStore: ConnectionsStore): Div =
    div("row") {
        h3("col-md-12") { text("Connections: ") }
        div("col-md-5 sensors") {
            messagesStore.data.map {
                it.filterKeys { (_, io) -> io.type == BitsInterfaceType.Sensor }
            }.render {
                sensors(it, messagesStore)
            }.bind()
        }
        div("col-md-2 couplings") { }
        div("col-md-5 actuators") {
            messagesStore.data.map {
                it.filterKeys { (_, io) -> io.type == BitsInterfaceType.Actuator }
            }.render {
                actuators(it, messagesStore)
            }.bind()
        }
    }

private fun HtmlElements.sensors(sensorConn: ConnectionsMap, messagesStore: ConnectionsStore): Tag<HTMLElement> =
    div("sensors") {
        sensorConn.entries.forEach { (connenctionId, connection) ->
            sensor(connenctionId, connection, messagesStore)
        }
    }

private fun HtmlElements.sensor(
    connectionId: ConnectionId,
    connection: WebSocketConnection,
    messagesStore: ConnectionsStore
): Tag<HTMLElement> {
    val (bit, io) = connectionId
    val sharedFlow = connection.messages.broadcastIn(GlobalScope).asFlow()
    val points = sharedFlow.windowed(300).sample(100).map {
        it.mapNotNull { event -> event.data.toString().toDoubleOrNull() }
    }
    return div("sensor") {
        h5 {
            +"${bit.name}: ${io.oscName()}"
        }
        button("btn btn-warning float-left") {
            +"x"
            clicks.map { connectionId } handledBy messagesStore.removeInterface
        }
        // To resolve type ambiguity
        val detailsInner: Details.() -> Unit = {
            summary {
                sharedFlow.map { it.data.toString() }.render { span { +"$it" } }.bind()
            }
            readingsPlot(points, io.range, widthPx = domNode.parentElement?.clientWidth?.toDouble() ?: 300.0)
        }
        details(content = detailsInner)
    }
}


@FlowPreview
@ExperimentalCoroutinesApi
private fun HtmlElements.bitsInterface(
    bitsIo: BitsInterface,
    socket: WebSocketConnection,
    deviceName: ServiceName,
    containerWidth: Int
): Tag<*> =
    when (bitsIo.type) {
        BitsInterfaceType.Unknown -> span { +"Unknown" }
        BitsInterfaceType.Sensor -> {
            // Take last 300 samples, emit at most once in 100ms
            val points = socket.messages.windowed(300).sample(100).map {
                it.mapNotNull { event -> event.data.toString().toDoubleOrNull() }
            }
            readingsPlot(points, bitsIo.range, widthPx = containerWidth.toDouble())
        }
        BitsInterfaceType.Actuator -> {
            val inputName = "${deviceName.name}-${bitsIo.type}-${bitsIo.id}"
            val valueStore = storeOf(0.0)
            input(id = inputName) {
                attr("style", "width: 100%;")
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

private fun HtmlElements.readingsPlot(
    points: Flow<List<Double>>,
    range: Pair<Int, Int>?,
    widthPx: Double = 300.0,
    heightPx: Double = 166.0,
    maxSamples: Int = 300
): Canvas {
    val gutterPx = 32.0
    val verticalGutter = heightPx * 0.05
    val canvas = canvas {
        width = flowOf((widthPx + gutterPx).toInt())
        height = flowOf(heightPx.toInt())
    }
    GlobalScope.launch {
        points.collect { points ->
            val ctx = canvas.domNode.getContext("2d") as CanvasRenderingContext2D
            val displayableData = points.take(widthPx.toInt())
            val domain = range?.let { it.first.toDouble() to it.second.toDouble() } ?: 0.0 to 1.0
            val yPosition = LinearScale(domain, heightPx - verticalGutter to verticalGutter)
            val xPosition = LinearScale(0.0 to maxSamples.toDouble(), 0.0 to widthPx - gutterPx)
            ctx.clearRect(0.0, 0.0, widthPx + gutterPx, heightPx)
            ctx.beginPath()
            ctx.lineWidth = 1.0
            displayableData.firstOrNull()?.let {
                ctx.moveTo(0.0, yPosition.apply(it))
            }
            displayableData.forEachIndexed { idx, v ->
                ctx.lineTo(xPosition.apply(idx.toDouble()), yPosition.apply(v))
            }
            ctx.strokeStyle = "rgb(0, 0, 0)"
            ctx.stroke()

            val fontSize = 9
            ctx.font = "${fontSize}px"
            ctx.fillText((range?.first ?: 0).toString(), widthPx + 4.0, heightPx)
            ctx.fillText((range?.second ?: 1).toString(), widthPx + 4.0, fontSize.toDouble())
            displayableData.lastOrNull()?.let {
                val pos = yPosition.apply(it)
                if (pos > fontSize * 2 && pos < heightPx - (fontSize)) {
                    ctx.fillText(it.toString(), widthPx + 4.0, pos)
                }
            }
        }
    }
    return canvas
}

fun HtmlElements.actuators(
    connectionsMap: Map<Pair<ServiceName, BitsInterface>, WebSocketConnection>,
    messagesStore: ConnectionsStore
): Tag<HTMLElement> =
    div("actuators") {
        connectionsMap.entries.forEach { (connenctionId, connection) ->
            actuator(connenctionId, connection, messagesStore)
        }
    }

fun HtmlElements.actuator(
    connectionId: Pair<ServiceName, BitsInterface>,
    connection: WebSocketConnection,
    messagesStore: ConnectionsStore
): Tag<HTMLElement> {
    val (bit, io) = connectionId
    return div("actuator") {
        h5 {
            +"${bit.name}: ${io.oscName()}"
        }
        button("btn btn-warning float-left") {
            +"x"
            clicks.map { connectionId } handledBy messagesStore.removeInterface
        }
        val inputName = "${bit.name}-${io.type}-${io.id}"
        val valueStore = storeOf(0.0)
        div("controller-wrapper") {
            div { +"${io.range?.first}" }
            div("input-wrapper") {
                input(baseClass = "input", id = inputName) {
                    type = const("range")
                    name = const(inputName)
                    io.range?.let {
                        min = const(io.range.first.toString())
                        max = const(io.range.second.toString())
                    }
                    value = valueStore.data.map { it.toString() }
                    changes.valuesAsNumber()
                        .onEach { connection.send(it.toString()) } handledBy valueStore.update
                }
                span {
                    valueStore.data.render {
                        label(`for` = inputName) {
                            +it.toString()
                        }
                    }.bind()
                }
            }
            div { +"${io.range?.second}" }
        }
    }
}

