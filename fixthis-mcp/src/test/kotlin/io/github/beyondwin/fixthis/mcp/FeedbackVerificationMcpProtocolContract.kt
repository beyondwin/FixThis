package io.github.beyondwin.fixthis.mcp

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.console.FeedbackTargetType
import io.github.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationStatusDto
import io.github.beyondwin.fixthis.mcp.tools.FixThisToolException
import io.github.beyondwin.fixthis.mcp.tools.FixThisTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal object FeedbackVerificationMcpProtocolContract {
    fun verifySchema() = runBlocking {
        withFixture { fixture ->
            val definitions = fixture.tools.listTools().map { it.jsonObject }
            val verify = definitions.single { it.getValue("name").jsonPrimitive.content == "fixthis_verify_feedback" }
            val verifySchema = verify.getValue("inputSchema").jsonObject
            val required = verifySchema.getValue("required").jsonArray.map { it.jsonPrimitive.content }
            val assertions = verifySchema.getValue("properties").jsonObject.getValue("assertions").jsonObject
            val assertionItems = assertions.getValue("items").jsonObject
            val assertionProperties = assertionItems.getValue("properties").jsonObject

            assertEquals(listOf("itemId"), required)
            assertEquals("array", assertions.getValue("type").jsonPrimitive.content)
            assertEquals("8", assertions.getValue("maxItems").jsonPrimitive.content)
            assertFalse(assertionItems.getValue("additionalProperties").jsonPrimitive.content.toBoolean())
            assertEquals(listOf("kind"), assertionItems.getValue("required").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(
                listOf("text_present", "text_absent", "target_present"),
                assertionProperties.getValue("kind").jsonObject.getValue("enum").jsonArray.map {
                    it.jsonPrimitive.content
                },
            )
            assertEquals("256", assertionProperties.getValue("value").jsonObject.getValue("maxLength").jsonPrimitive.content)
            assertEquals("64", assertionProperties.getValue("role").jsonObject.getValue("maxLength").jsonPrimitive.content)

            val resolve = definitions.single { it.getValue("name").jsonPrimitive.content == "fixthis_resolve_feedback" }
            val resolveProperties = resolve.getValue("inputSchema").jsonObject.getValue("properties").jsonObject
            assertEquals("string", resolveProperties.getValue("verificationReceiptId").jsonObject.getValue("type").jsonPrimitive.content)
        }
    }

    fun verifyResult() = runBlocking {
        withFixture { fixture ->
            val result = fixture.tools.call(
                "fixthis_verify_feedback",
                verificationArguments(
                    fixture,
                    buildJsonArray {
                        add(assertion("text_present", value = "Email address"))
                        add(assertion("target_present"))
                    },
                ),
            )
            val structured = result.getValue("structuredContent").jsonObject
            val receipt = structured.getValue("receipt").jsonObject
            val persisted = fixture.service.getSession(fixture.sessionId).verificationReceipts.single()
            val serializedResult = result.toString()

            assertEquals(setOf("receipt"), structured.keys)
            assertEquals(persisted.receiptId, receipt.getValue("receiptId").jsonPrimitive.content)
            assertEquals(fixture.itemId, receipt.getValue("itemId").jsonPrimitive.content)
            assertEquals("pass", receipt.getValue("verdict").jsonPrimitive.content)
            assertTrue(result.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content.contains("PASS"))
            assertFalse(serializedResult.contains("roots"))
            assertFalse(serializedResult.contains("sourceIndex"))
            assertFalse(serializedResult.contains("FormScreen.kt"))
            assertEquals(
                AnnotationStatusDto.IN_PROGRESS,
                fixture.service.getSession(fixture.sessionId).items.single().status,
            )
        }
    }

    fun verifyValidatorBudget() = runBlocking {
        withFixture { fixture ->
            val assertions = buildJsonArray {
                repeat(9) { add(assertion("target_present")) }
            }

            val error = assertFailsWith<FixThisToolException> {
                fixture.tools.call("fixthis_verify_feedback", verificationArguments(fixture, assertions))
            }

            assertTrue(error.message.orEmpty().startsWith("VERIFICATION_ASSERTIONS_INVALID:"), error.message)
            assertTrue(fixture.service.getSession(fixture.sessionId).verificationReceipts.isEmpty())
        }
    }

    private fun verificationArguments(fixture: Fixture, assertions: JsonArray): JsonObject = buildJsonObject {
        put("sessionId", fixture.sessionId)
        put("itemId", fixture.itemId)
        put("assertions", assertions)
    }

    private fun assertion(kind: String, value: String? = null): JsonObject = buildJsonObject {
        put("kind", kind)
        value?.let { put("value", it) }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val root = createTempDirectory("fixthis-verification-mcp-").toFile()
        val sourceFile = root.resolve("sample/src/main/java/io/github/fixthis/sample/screens/FormScreen.kt").apply {
            parentFile.mkdirs()
            writeText("Text(\"Email address\")")
            setLastModified(1_000L)
        }
        val bridge = FakeFixThisBridge().apply { installEpochMillis = 2_000L }
        val service = FeedbackSessionService(
            bridge = bridge,
            projectRoot = root.absolutePath,
            defaultPackageName = PACKAGE_NAME,
        )
        val session = service.openSession(null, newSession = true)
        val screen = service.captureScreen(session.sessionId)
        val item = service.addFeedbackItem(
            sessionId = session.sessionId,
            screenId = screen.screenId,
            targetType = FeedbackTargetType.NODE,
            bounds = FixThisRect(28f, 77f, 692f, 186f),
            nodeUid = "email-label",
            comment = "Verify the email field",
        )
        service.sendDraftToAgent(session.sessionId, listOf(item.itemId))
        service.claimFeedback(session.sessionId, item.itemId, "checking")
        val tools = FixThisTools(
            bridge = bridge,
            defaultPackageName = PACKAGE_NAME,
            projectRoot = root,
            feedbackService = service,
        )
        try {
            block(Fixture(tools, service, session.sessionId, item.itemId))
        } finally {
            tools.close()
            sourceFile.delete()
            root.deleteRecursively()
        }
    }

    private data class Fixture(
        val tools: FixThisTools,
        val service: FeedbackSessionService,
        val sessionId: String,
        val itemId: String,
    )

    private const val PACKAGE_NAME = "io.github.beyondwin.fixthis.sample"
}
