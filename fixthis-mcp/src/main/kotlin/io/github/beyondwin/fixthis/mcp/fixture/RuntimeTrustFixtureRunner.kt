package io.github.beyondwin.fixthis.mcp.fixture

import io.github.beyondwin.fixthis.cli.BridgeClient
import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.source.SourceIndex
import io.github.beyondwin.fixthis.mcp.McpProtocol
import io.github.beyondwin.fixthis.mcp.console.FeedbackTargetType
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.dto.FeedbackNavigationAction
import io.github.beyondwin.fixthis.mcp.session.dto.FeedbackNavigationRequest
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionStore
import io.github.beyondwin.fixthis.mcp.session.source.SourceIndexRegistry
import io.github.beyondwin.fixthis.mcp.tools.CliFixThisBridge
import io.github.beyondwin.fixthis.mcp.tools.FixThisBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

data class RuntimeTrustFixtureRunnerOptions(
    val inputPath: String,
    val outputPath: String,
    val strict: Boolean,
) {
    companion object {
        fun parse(args: Array<String>): RuntimeTrustFixtureRunnerOptions {
            val values = args.toList()
            fun valueAfter(name: String): String {
                val index = values.indexOf(name)
                require(index >= 0 && index + 1 < values.size) { "$name is required" }
                return values[index + 1]
            }
            return RuntimeTrustFixtureRunnerOptions(
                inputPath = valueAfter("--input"),
                outputPath = valueAfter("--output"),
                strict = values.contains("--strict"),
            )
        }
    }
}

fun main(args: Array<String>) {
    val options = runCatching { RuntimeTrustFixtureRunnerOptions.parse(args) }
        .getOrElse { error ->
            System.err.println(error.message ?: "invalid arguments")
            exitProcess(2)
        }
    val inputFile = File(options.inputPath).canonicalFile
    val outputFile = File(options.outputPath).canonicalFile
    val input = McpProtocol.json
        .decodeFromString(RuntimeTrustFixtureInput.serializer(), inputFile.readText())
        .copy(strict = options.strict)

    val output = RuntimeTrustFixtureRunner().run(input)
    outputFile.parentFile.mkdirs()
    outputFile.writeText(McpProtocol.json.encodeToString(RuntimeTrustFixtureOutput.serializer(), output))
    if (output.status == "fail" || (input.strict && output.cases.any { it.environment.isNotEmpty() })) {
        exitProcess(1)
    }
}

data class RuntimeCaptureRetryPolicy(
    val maxAttempts: Int = 10,
    val retryDelayMillis: Long = 250,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(retryDelayMillis >= 0) { "retryDelayMillis must be non-negative" }
    }
}

class RuntimeTrustFixtureRunner(
    private val bridgeFactory: (File) -> FixThisBridge = { projectRoot ->
        CliFixThisBridge(BridgeClient(projectRoot = projectRoot))
    },
    private val captureRetryPolicy: RuntimeCaptureRetryPolicy = RuntimeCaptureRetryPolicy(),
    private val delay: suspend (Long) -> Unit = { delayMillis -> kotlinx.coroutines.delay(delayMillis) },
) {
    private data class RuntimeFixtureRuntime(
        val bridge: FixThisBridge,
        val service: FeedbackSessionService,
        val store: FeedbackSessionStore,
    )

    private data class RuntimeTargetResolution(
        val screen: SnapshotDto,
        val node: FixThisNode,
    )

    private data class RuntimeFeedbackItemResult(
        val screen: SnapshotDto,
        val item: io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto,
    )

    fun run(input: RuntimeTrustFixtureInput): RuntimeTrustFixtureOutput = runBlocking {
        val projectRoot = File(input.projectDir).canonicalFile
        val hostSourceIndex = input.hostSourceIndexOrNull()
        val runtime = createRuntime(input, projectRoot, hostSourceIndex)
        val session = runtime.service.openSession(input.packageName, newSession = true)
        runCatching { runtime.bridge.launchApp(input.packageName) }.getOrElse {
            return@runBlocking environmentOutput(input, "app_launch_failed")
        }
        var screen = captureScreenWithRetry(runtime.service, session.sessionId).getOrElse {
            return@runBlocking environmentOutput(input, "capture_failed")
        }
        screen = screen.withHostSourceIndexFallback(hostSourceIndex)
        runtime.store.storeSourceIndexedScreen(session.sessionId, screen)

        val cases = input.cases.map { testCase ->
            try {
                testCase.navigateBefore?.let { target ->
                    screen = navigateToTarget(runtime, session.sessionId, screen, target, hostSourceIndex)
                        .withHostSourceIndexFallback(hostSourceIndex)
                    runtime.store.storeSourceIndexedScreen(session.sessionId, screen)
                }
                val result = addRuntimeFeedbackItem(runtime, session.sessionId, screen, testCase, hostSourceIndex)
                screen = result.screen
                RuntimeTrustCaseOutput(
                    caseId = testCase.caseId,
                    observed = RuntimeTrustObservationMapper.fromAnnotation(result.item),
                )
            } catch (error: RuntimeTargetResolutionException) {
                RuntimeTrustCaseOutput(caseId = testCase.caseId, failures = listOf(error.code))
            }
        }
        RuntimeTrustFixtureOutput(
            status = if (cases.any { it.failures.isNotEmpty() }) "fail" else "evaluated",
            cases = cases,
        )
    }

    private suspend fun createRuntime(
        input: RuntimeTrustFixtureInput,
        projectRoot: File,
        hostSourceIndex: SourceIndex?,
    ): RuntimeFixtureRuntime {
        val sourceIndexRegistry = SourceIndexRegistry()
        hostSourceIndex?.let { sourceIndexRegistry.put(input.packageName, it) }
        val store = FeedbackSessionStore()
        val bridge = bridgeFactory(projectRoot)
        val service = FeedbackSessionService(
            bridge = bridge,
            store = store,
            projectRoot = projectRoot.absolutePath,
            defaultPackageName = input.packageName,
            sourceIndexRegistry = sourceIndexRegistry,
        )
        return RuntimeFixtureRuntime(bridge = bridge, service = service, store = store)
    }

    private fun FeedbackSessionStore.storeSourceIndexedScreen(sessionId: String, screen: SnapshotDto) {
        if (screen.sourceIndexAvailable) {
            addOrReplaceScreenForDomain(sessionId, screen)
        }
    }

    private suspend fun addRuntimeFeedbackItem(
        runtime: RuntimeFixtureRuntime,
        sessionId: String,
        screen: SnapshotDto,
        testCase: RuntimeTrustCaseInput,
        hostSourceIndex: SourceIndex?,
    ): RuntimeFeedbackItemResult = if (testCase.runtimeTarget.visualArea != null) {
        RuntimeFeedbackItemResult(
            screen = screen,
            item = runtime.service.addAreaFeedback(
                sessionId = sessionId,
                screenId = screen.screenId,
                bounds = testCase.runtimeTarget.visualArea.rect(),
                comment = "Runtime trust fixture ${testCase.caseId}",
            ),
        )
    } else {
        val resolved = resolveTargetWithCaptureRetry(runtime, sessionId, screen, testCase.runtimeTarget, hostSourceIndex)
        RuntimeFeedbackItemResult(
            screen = resolved.screen,
            item = runtime.service.addFeedbackItem(
                sessionId = sessionId,
                screenId = resolved.screen.screenId,
                targetType = FeedbackTargetType.NODE,
                bounds = resolved.node.boundsInWindow,
                nodeUid = resolved.node.uid,
                comment = "Runtime trust fixture ${testCase.caseId}",
            ),
        )
    }

    private suspend fun resolveTargetWithCaptureRetry(
        runtime: RuntimeFixtureRuntime,
        sessionId: String,
        initialScreen: SnapshotDto,
        target: RuntimeTargetSelector,
        hostSourceIndex: SourceIndex?,
    ): RuntimeTargetResolution {
        var screen = initialScreen
        var lastTargetNotFound: RuntimeTargetResolutionException? = null
        repeat(captureRetryPolicy.maxAttempts) { attempt ->
            try {
                return RuntimeTargetResolution(screen, RuntimeTargetResolver.resolve(screen, target))
            } catch (error: RuntimeTargetResolutionException) {
                requireRetryableTarget(error)
                lastTargetNotFound = error
            }
            if (attempt < captureRetryPolicy.maxAttempts - 1) {
                delay(captureRetryPolicy.retryDelayMillis)
                screen = captureScreenWithRetry(runtime.service, sessionId)
                    .getOrElse { missingRuntimeTarget(lastTargetNotFound, target) }
                    .withHostSourceIndexFallback(hostSourceIndex)
                runtime.store.storeSourceIndexedScreen(sessionId, screen)
            }
        }
        missingRuntimeTarget(lastTargetNotFound, target)
    }

    private suspend fun captureScreenWithRetry(
        service: FeedbackSessionService,
        sessionId: String,
    ): Result<SnapshotDto> {
        var lastFailure: Throwable? = null
        repeat(captureRetryPolicy.maxAttempts) { attempt ->
            val result = runCatching { service.captureScreen(sessionId) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                }
            if (result.isSuccess) {
                return result
            }
            lastFailure = result.exceptionOrNull()
            if (attempt < captureRetryPolicy.maxAttempts - 1) {
                delay(captureRetryPolicy.retryDelayMillis)
            }
        }
        return Result.failure(lastFailure ?: IllegalStateException("captureScreen failed without an exception"))
    }

    private suspend fun navigateToTarget(
        runtime: RuntimeFixtureRuntime,
        sessionId: String,
        screen: SnapshotDto,
        target: RuntimeTargetSelector,
        hostSourceIndex: SourceIndex?,
    ): SnapshotDto {
        val resolved = resolveTargetWithCaptureRetry(runtime, sessionId, screen, target, hostSourceIndex)
        val bounds = resolved.node.boundsInWindow
        val result = runtime.service.navigate(
            sessionId,
            FeedbackNavigationRequest(
                action = FeedbackNavigationAction.TAP,
                x = (bounds.left + bounds.right) / 2f,
                y = (bounds.top + bounds.bottom) / 2f,
                captureAfter = true,
            ),
        )
        return result.screen ?: resolved.screen
    }

    private fun RuntimeTrustFixtureInput.hostSourceIndexOrNull(): SourceIndex? {
        val file = sourceIndexPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isFile }
        return file?.let {
            runCatching {
                McpProtocol.json.decodeFromString(SourceIndex.serializer(), it.readText())
                    .takeIf { sourceIndex -> sourceIndex.entries.isNotEmpty() }
            }.getOrNull()
        }
    }

    private fun SnapshotDto.withHostSourceIndexFallback(sourceIndex: SourceIndex?): SnapshotDto = if (sourceIndex != null && !sourceIndexAvailable) copy(sourceIndexAvailable = true) else this

    private fun environmentOutput(input: RuntimeTrustFixtureInput, label: String): RuntimeTrustFixtureOutput = RuntimeTrustFixtureOutput(
        status = if (input.strict) "fail" else "pass_with_environment_downgrade",
        cases = input.cases.map {
            RuntimeTrustCaseOutput(caseId = it.caseId, environment = listOf(label))
        },
    )
}

private fun requireRetryableTarget(error: RuntimeTargetResolutionException) {
    if (error.code != "target_not_found") throw error
}

private fun missingRuntimeTarget(
    lastTargetNotFound: RuntimeTargetResolutionException?,
    target: RuntimeTargetSelector,
): Nothing = throw lastTargetNotFound ?: RuntimeTargetResolutionException(
    "target_not_found",
    "No runtime target matched selector $target",
)
