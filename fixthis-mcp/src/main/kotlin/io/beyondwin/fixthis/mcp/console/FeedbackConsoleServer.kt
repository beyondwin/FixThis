package io.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.beyondwin.fixthis.cli.BridgeConnectionException
import io.beyondwin.fixthis.mcp.console.events.ConsoleEventBus
import io.beyondwin.fixthis.mcp.session.FeedbackSessionException
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private const val HTTP_SERVICE_UNAVAILABLE = 503

private data class FeedbackConsoleServerConfig(
    val service: FeedbackSessionService,
    val host: String,
    val port: Int,
    val consoleAssetsDir: File?,
    val eventBus: ConsoleEventBus,
    val consoleToken: String = UUID.randomUUID().toString(),
) {
    val packagedIndexHtml: String? =
        if (consoleAssetsDir == null) FeedbackConsoleAssets.html(null, consoleToken) else null
}

class FeedbackConsoleServer private constructor(
    private val host: String,
    private val port: Int,
    private val consoleToken: String,
    private val routeTable: ConsoleRouteTable,
    private val diagnosticsSink: (String) -> Unit,
) {
    constructor(
        service: FeedbackSessionService,
        host: String = "127.0.0.1",
        port: Int = 0,
        consoleAssetsDir: File? = null,
        eventBus: ConsoleEventBus = ConsoleEventBus(),
        diagnosticsSink: (String) -> Unit = { System.err.print(it) },
    ) : this(
        config = FeedbackConsoleServerConfig(
            service = service,
            host = host,
            port = port,
            consoleAssetsDir = consoleAssetsDir,
            eventBus = eventBus,
        ),
        diagnosticsSink = diagnosticsSink,
    )

    private constructor(
        config: FeedbackConsoleServerConfig,
        diagnosticsSink: (String) -> Unit,
    ) : this(
        host = config.host,
        port = config.port,
        consoleToken = config.consoleToken,
        routeTable = consoleRouteTable(config),
        diagnosticsSink = diagnosticsSink,
    )

    internal constructor(
        routes: List<ConsoleRoute>,
        host: String = "127.0.0.1",
        port: Int = 0,
        diagnosticsSink: (String) -> Unit = { System.err.print(it) },
    ) : this(
        host = host,
        port = port,
        consoleToken = UUID.randomUUID().toString(),
        routeTable = ConsoleRouteTable(routes),
        diagnosticsSink = diagnosticsSink,
    )

    private val lock = Any()
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null

    val url: String
        get() = "http://${host.toUrlHost()}:${runningServer().address.port}"

    internal fun consoleTokenForTests(): String = consoleToken

    fun start(): String = synchronized(lock) {
        server?.let { return@synchronized url }
        val requestExecutor = consoleHttpExecutor()
        HttpServer.create(InetSocketAddress(InetAddress.getByName(host), port), 0)
            .also { httpServer ->
                httpServer.createContext("/") { exchange -> dispatch(exchange) }
                httpServer.executor = requestExecutor
                httpServer.start()
                executor = requestExecutor
                server = httpServer
            }
        url
    }

    fun stop() {
        synchronized(lock) {
            server?.stop(0)
            server = null
            executor?.shutdownNow()
            executor = null
        }
    }

    private fun runningServer(): HttpServer = synchronized(lock) {
        server ?: throw IllegalStateException("Feedback console server is not running")
    }

    private companion object {
        fun consoleRouteTable(config: FeedbackConsoleServerConfig) = ConsoleRouteTable(
            listOf(
                ServerVersionRoutes(),
                ConsoleEventRoutes(config.service, config.eventBus),
                SessionRoutes(
                    service = config.service,
                    consoleAssetsDir = config.consoleAssetsDir,
                    consoleToken = config.consoleToken,
                    eventBus = config.eventBus,
                    packagedIndexHtml = config.packagedIndexHtml,
                ),
                DeviceRoutes(config.service, config.eventBus),
                ConnectionRoutes(config.service, config.eventBus),
                PreviewRoutes(config.service, config.eventBus),
                FeedbackItemRoutes(config.service, config.eventBus),
                ArtifactRoutes(config.service),
                HandoffPreviewRoutes(config.service),
                MarkHandedOffRoutes(config.service, config.eventBus),
            ),
        )
    }

    internal fun dispatch(exchange: HttpExchange) {
        try {
            if (exchange.requiresConsoleMutationGuard()) {
                exchange.requireConsoleMutationAllowed(
                    ConsoleRequestAuthConfig(
                        token = consoleToken,
                        host = host,
                        port = runningServer().address.port,
                    ),
                )
            }
            if (!routeTable.handle(exchange)) {
                exchange.trySendErrorJson(404, "Not found")
            }
        } catch (error: FeedbackConsoleHttpException) {
            exchange.trySendErrorJson(error.statusCode, error.message)
        } catch (error: FeedbackSessionException) {
            val httpError = error.toConsoleHttpException()
            exchange.trySendErrorJson(httpError.statusCode, httpError.message)
        } catch (error: BridgeConnectionException) {
            exchange.trySendErrorJson(HTTP_SERVICE_UNAVAILABLE, error.message ?: "FixThis bridge is not connected")
        } catch (error: Throwable) {
            if (error.isClientDisconnect()) {
                exchange.closeQuietly()
                return
            }
            diagnosticsSink(
                "FeedbackConsoleServer: ${error::class.java.name}: ${error.message}\n${error.stackTraceToString()}",
            )
            exchange.trySendErrorJson(500, error.message ?: error::class.java.simpleName)
        }
    }
}

private fun consoleHttpExecutor(): ExecutorService {
    val requestIds = AtomicInteger(0)
    return Executors.newCachedThreadPool { task ->
        Thread(task, "fixthis-console-http-${requestIds.incrementAndGet()}")
    }
}

private fun FeedbackSessionException.toConsoleHttpException(): FeedbackConsoleHttpException {
    val text = message ?: "Feedback session request failed"
    val statusCode = when {
        text.startsWith("SESSION_NOT_FOUND:") -> 404
        text.startsWith("Unknown feedback session:") -> 404
        text.startsWith("PREVIEW_NOT_FOUND:") -> 404
        text.startsWith("PREVIEW_SCREENSHOT_NOT_FOUND:") -> 404
        text.startsWith("SCREEN_NOT_FOUND:") -> 400
        text.startsWith("NO_DRAFT_FEEDBACK:") -> 409
        text.startsWith("NO_ACTIVE_SESSION:") -> 409
        text.startsWith("ITEM_NOT_EDITABLE:") -> 409
        text.startsWith("DEVICE_NOT_AVAILABLE:") -> 409
        text.startsWith("PREVIEW_SAVE_IN_PROGRESS:") -> 409
        else -> 500
    }
    return FeedbackConsoleHttpException(statusCode, text)
}
