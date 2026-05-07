package io.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService

internal class DeviceRoutes(private val service: FeedbackSessionService) : ConsoleRoute {
    override fun matches(path: String): Boolean =
        path == "/api/devices" ||
            path == "/api/device/select" ||
            path == "/api/device/disconnect"

    override fun handle(exchange: HttpExchange) {
        when (exchange.requestURI.path) {
            "/api/devices" -> exchange.requireMethod("GET") {
                val selectedSerial = service.selectedDeviceSerial()
                exchange.sendJson(
                    200,
                    ConsoleDeviceList(
                        devices = service.devices().map { it.toConsoleDevice(selectedSerial) },
                        selectedSerial = selectedSerial,
                    ),
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
                exchange.sendJson(
                    200,
                    ConsoleDeviceList(
                        devices = service.devices().map { it.toConsoleDevice(selectedSerial) },
                        selectedSerial = selectedSerial,
                    ),
                )
            }
            "/api/device/disconnect" -> exchange.requireMethod("POST") {
                service.disconnectDevice()
                exchange.sendJson(
                    200,
                    ConsoleDeviceList(devices = service.devices().map { it.toConsoleDevice(null) }),
                )
            }
        }
    }

    private fun HttpExchange.decodeSelectDeviceBody(): SelectDeviceRequest =
        decodeJsonBody(SelectDeviceRequest.serializer())
}
