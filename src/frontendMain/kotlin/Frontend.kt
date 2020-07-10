package se.kth.somabits.frontend

import dev.fritz2.binding.RootStore
import dev.fritz2.binding.const
import dev.fritz2.binding.each
import dev.fritz2.binding.handledBy
import dev.fritz2.dom.html.render
import dev.fritz2.dom.mount
import dev.fritz2.remote.body
import dev.fritz2.remote.onErrorLog
import dev.fritz2.remote.remote
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.ImplicitReflectionSerializer
import kotlinx.serialization.builtins.*
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import se.kth.somabits.common.BitsService

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
    val devicesStore = object : RootStore<List<BitsService>>(emptyList(), id = "bits") {
        val endpoint = remote("/discover").acceptJson().header("Content-Type", "application/json")

        val loadDevices = apply<Unit, List<BitsService>> {
            endpoint.get().onErrorLog()
                .body()
                .map { value ->
                    Json.parse(MapSerializer(String.serializer(), BitsService::class.serializer()), value)
                        .values
                        .toList()
                }
        } andThen update

        val timer = channelFlow {
            while (true) {
                send(Unit)
                delay(2000)
            }
        }

        init {
            timer handledBy loadDevices
        }
    }
    GlobalScope.launch {
        addressStore.start
            .onCompletion { println("Done") }
            .launchIn(this)
        devicesStore.timer
            .onEach { println("Got devices") }
            .launchIn(this)
    }
    render {
        div("container") {
            div("col-md-12") {
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

            div("row") {
                div("col-md-12") {
                    table("table") {
                        caption { text("Known devices") }
                        thead {
                            tr {
                                th { text("Name") }
                                th { text("Address") }
                                th { text("Interfaces") }
                            }
                        }
                        tbody {
                            devicesStore.data.each().map { device: BitsService ->
                                render {
                                    tr {
                                        td { text(device.name.name) }
                                        td { text("${device.address}:${device.port}") }
                                        td {
                                            text(device.interfaces.map {
                                                "${it.first} -> ${it.second}"
                                            }.joinToString(", "))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }.mount("root")
}
