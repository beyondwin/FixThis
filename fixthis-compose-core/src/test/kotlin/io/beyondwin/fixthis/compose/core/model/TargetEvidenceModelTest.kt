package io.beyondwin.fixthis.compose.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetEvidenceModelTest {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        explicitNulls = false
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun legacyAnnotationWithoutTargetEvidenceStillDecodes() {
        val annotation = json.decodeFromString<FixThisAnnotation>(
            """
            {
              "schemaVersion": "1.0",
              "id": "annotation-1",
              "createdAtEpochMillis": 100,
              "platform": "android-compose",
              "app": {
                "packageName": "io.beyondwin.fixthis.sample",
                "debuggable": true
              },
              "activity": {
                "className": "io.beyondwin.fixthis.sample.MainActivity"
              },
              "tap": {
                "xInWindow": 20.0,
                "yInWindow": 30.0
              },
              "selection": {
                "kind": "SEMANTICS_NODE",
                "confidence": "HIGH",
                "selectedUid": "compose:0:merged:7",
                "source": "TAP_SELECT"
              },
              "selectedNode": {
                "uid": "compose:0:merged:7",
                "composeNodeId": 7,
                "rootIndex": 0,
                "treeKind": "MERGED",
                "boundsInWindow": {
                  "left": 0.0,
                  "top": 0.0,
                  "right": 100.0,
                  "bottom": 48.0
                },
                "text": ["Sign In"],
                "role": "Button",
                "testTag": "comp:AppPrimaryButton:primary"
              },
              "userComment": "Button color is too muted"
            }
            """.trimIndent(),
        )

        assertNull(annotation.targetEvidence)
        assertEquals("annotation-1", annotation.id)
    }

    @Test
    fun annotationWithTargetEvidenceEncodesAndDecodes() {
        val annotation = baseAnnotation().copy(
            targetEvidence = TargetEvidence(
                identityHint = IdentityHint(
                    composableNameHint = "AppPrimaryButton",
                    variantHint = "primary",
                    stableLabel = "Button Sign In",
                    source = IdentityHintSource.TEST_TAG_CONVENTION,
                    confidence = IdentityHintConfidence.HIGH,
                ),
                occurrence = Occurrence(
                    signature = OccurrenceSignature(
                        type = OccurrenceSignatureType.IDENTITY_HINT,
                        value = "AppPrimaryButton:primary",
                    ),
                    count = 2,
                    selectedOrdinal = 1,
                ),
                sourceInterpretation = SourceInterpretation(
                    topCandidate = SourceCandidateSummary(
                        file = "sample/src/main/java/io/beyondwin/fixthis/sample/components/AppPrimaryButton.kt",
                        line = 42,
                        confidence = SelectionConfidence.HIGH,
                    ),
                    reasonSummary = listOf("selected testTag convention composable"),
                    caution = null,
                ),
                evidenceQuality = EvidenceQuality.STRUCTURED,
                screenshotKinds = listOf("full", "crop"),
                warnings = listOf("fallback source candidate was ignored"),
            ),
        )

        val encoded = json.encodeToString(annotation)
        val decoded = json.decodeFromString<FixThisAnnotation>(encoded)
        val targetEvidence = decoded.targetEvidence

        assertTrue(encoded.contains("\"targetEvidence\""))
        assertNotNull(targetEvidence)
        assertEquals("AppPrimaryButton", targetEvidence?.identityHint?.composableNameHint)
        assertEquals("primary", targetEvidence?.identityHint?.variantHint)
        assertEquals("Button Sign In", targetEvidence?.identityHint?.stableLabel)
        assertEquals(IdentityHintSource.TEST_TAG_CONVENTION, targetEvidence?.identityHint?.source)
        assertEquals(IdentityHintConfidence.HIGH, targetEvidence?.identityHint?.confidence)
        assertEquals("captured_merged_semantics_nodes", targetEvidence?.occurrence?.basis)
        assertEquals(OccurrenceSignatureType.IDENTITY_HINT, targetEvidence?.occurrence?.signature?.type)
        assertEquals("AppPrimaryButton:primary", targetEvidence?.occurrence?.signature?.value)
        assertEquals(2, targetEvidence?.occurrence?.count)
        assertEquals(1, targetEvidence?.occurrence?.selectedOrdinal)
        assertEquals(
            "sample/src/main/java/io/beyondwin/fixthis/sample/components/AppPrimaryButton.kt",
            targetEvidence?.sourceInterpretation?.topCandidate?.file,
        )
        assertEquals(42, targetEvidence?.sourceInterpretation?.topCandidate?.line)
        assertEquals(SelectionConfidence.HIGH, targetEvidence?.sourceInterpretation?.topCandidate?.confidence)
        assertEquals(
            listOf("selected testTag convention composable"),
            targetEvidence?.sourceInterpretation?.reasonSummary,
        )
        assertNull(targetEvidence?.sourceInterpretation?.caution)
        assertEquals(EvidenceQuality.STRUCTURED, targetEvidence?.evidenceQuality)
        assertEquals(listOf("full", "crop"), targetEvidence?.screenshotKinds)
        assertEquals(listOf("fallback source candidate was ignored"), targetEvidence?.warnings)
    }

    @Test
    fun decodesLegacyAnnotationWithoutTargetReliability() {
        val annotation = json.decodeFromString<FixThisAnnotation>(
            """
            {
              "schemaVersion": "1.0",
              "id": "annotation-1",
              "createdAtEpochMillis": 100,
              "platform": "android-compose",
              "app": {"packageName": "pkg", "debuggable": true},
              "activity": {"className": "MainActivity"},
              "tap": {"xInWindow": 10.0, "yInWindow": 11.0},
              "selection": {"kind": "VISUAL_AREA", "confidence": "LOW", "source": "AREA_SELECT"},
              "userComment": "Make this clearer"
            }
            """.trimIndent(),
        )

        assertNull(annotation.targetReliability)
    }

    @Test
    fun roundTripsTargetReliability() {
        val annotation = baseAnnotation().copy(
            targetReliability = TargetReliability(
                confidence = TargetConfidence.LOW,
                reasons = listOf(TargetReliabilityReason.VISUAL_AREA_SELECTION),
                warnings = listOf(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP),
            ),
        )

        val encoded = json.encodeToString(annotation)
        val decoded = json.decodeFromString<FixThisAnnotation>(encoded)

        assertEquals(annotation.targetReliability, decoded.targetReliability)
        assertTrue(encoded.contains("\"targetReliability\""))
        assertTrue(encoded.contains("POSSIBLE_VIEW_INTEROP"))
    }

    private fun baseAnnotation(): FixThisAnnotation = FixThisAnnotation(
        id = "annotation-1",
        createdAtEpochMillis = 100,
        app = AppInfo(packageName = "io.beyondwin.fixthis.sample", debuggable = true),
        activity = ActivityInfo(className = "io.beyondwin.fixthis.sample.MainActivity"),
        tap = TapPoint(xInWindow = 20f, yInWindow = 30f),
        selection = SelectionInfo(
            kind = SelectionKind.SEMANTICS_NODE,
            confidence = SelectionConfidence.HIGH,
            selectedUid = "compose:0:merged:7",
            source = SelectionSource.TAP_SELECT,
        ),
        selectedNode = FixThisNode(
            uid = "compose:0:merged:7",
            composeNodeId = 7,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(0f, 0f, 100f, 48f),
            text = listOf("Sign In"),
            role = "Button",
            testTag = "comp:AppPrimaryButton:primary",
        ),
        userComment = "Button color is too muted",
    )
}
