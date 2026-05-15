package io.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange
import io.beyondwin.fixthis.cli.fixThisJson
import io.beyondwin.fixthis.mcp.console.events.ConsoleEventBus
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal class DeviceRoutes(
    private val service: FeedbackSessionService,
    private val eventBus: ConsoleEventBus,
) : ConsoleRoute {
    override fun matches(path: String): Boolean = path == "/api/devices" ||
        path == "/api/device/select" ||
        path == "/api/device/disconnect"

    override fun handle(exchange: HttpExchange) {
        when (exchange.requestURI.path) {
            "/api/devices" -> exchange.requireMethod("GET") {
                val selectedSerial = service.selectedDeviceSerial()
                val response = ConsoleDeviceList(
                    devices = service.devices().map { it.toConsoleDevice(selectedSerial) },
                    selectedSerial = selectedSerial,
                )
                eventBus.emitDevicesUpdated(response)
                exchange.sendJson(
                    200,
                    response,
                )
            }
            "/api/device/select" -> exchange.requireMethod("POST") {
                val request = exchange.decodeSelectDeviceBody()
                try {
                    service.selectDevice(request.serial)
                } catch (error: IllegalArgumentException) {
                    throw FeedbackConsoleHttpException(400, error.message ?: "Invalid device selection request")
                }
                val selectedSerial = service.selectedDeviceSerial()
                val response = ConsoleDeviceList(
                    devices = service.devices().map { it.toConsoleDevice(selectedSerial) },
                    selectedSerial = selectedSerial,
                )
                eventBus.emitDevicesUpdated(response)
                exchange.sendJson(
                    200,
                    response,
                )
            }
            "/api/device/disconnect" -> exchange.requireMethod("POST") {
                service.disconnectDevice()
                val response = ConsoleDeviceList(devices = service.devices().map { it.toConsoleDevice(null) })
                eventBus.emitDevicesUpdated(response)
                exchange.sendJson(
                    200,
                    response,
                )
            }
        }
    }

    private fun HttpExchange.decodeSelectDeviceBody(): SelectDeviceRequest = decodeJsonBody(SelectDeviceRequest.serializer())
}

private fun ConsoleEventBus.emitDevicesUpdated(devices: ConsoleDeviceList) {
    emit(
        "devices-updated",
        buildJsonObject {
            put("devices", fixThisJson.encodeToJsonElement(ConsoleDeviceList.serializer(), devices).jsonObject)
        },
    )
}
