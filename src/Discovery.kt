package se.kth.somabits

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import com.sun.javafx.util.Logging
import org.slf4j.LoggerFactory
import java.util.logging.Logger
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener
import javax.jmdns.ServiceTypeListener
import javax.jmdns.impl.JmDNSImpl
import kotlin.system.exitProcess

val OSC_SERVICE_TYPE = "_osc._udp.local."

fun main(arg: Array<String>) {
    val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
    loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).level = Level.INFO
    val jmDnsInstance = JmDNS.create()
    jmDnsInstance.addServiceListener(OSC_SERVICE_TYPE, object : ServiceListener {
        override fun serviceResolved(event: ServiceEvent?) {
//            println("Resolved: $event")
        }

        override fun serviceRemoved(event: ServiceEvent?) {
            println("Removed: $event")
        }

        override fun serviceAdded(event: ServiceEvent?) {
            println("Added: $event")
        }

    })
    while (true) {
        val line = readLine()?.split(" ")
        when (line?.first()) {
            "q", "quit" -> exitProcess(0)
            "l", "list" -> println(jmDnsInstance.list(OSC_SERVICE_TYPE))
            "p", "print" -> if (line.size > 1) {
                val serviceName = line.drop(1).joinToString(" ")
                println("$serviceName:")
                val serviceInfo = jmDnsInstance.getServiceInfo(OSC_SERVICE_TYPE, serviceName)
                println(serviceInfo.hostAddresses.joinToString(", "))
                println(serviceInfo.port)
                println(serviceInfo.niceTextString)
            }
        }
    }
}