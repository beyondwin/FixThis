package io.beyondwin.fixthis.cli

import com.github.ajalt.clikt.core.CliktError
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class MainPrintTest {

    private val originalErr = System.err
    private val originalOut = System.out
    private lateinit var errBuf: ByteArrayOutputStream
    private lateinit var outBuf: ByteArrayOutputStream

    @Before
    fun captureStreams() {
        errBuf = ByteArrayOutputStream()
        outBuf = ByteArrayOutputStream()
        System.setErr(PrintStream(errBuf))
        System.setOut(PrintStream(outBuf))
        DiagnosticContext.reset()
    }

    @After
    fun restoreStreams() {
        System.setErr(originalErr)
        System.setOut(originalOut)
        DiagnosticContext.reset()
    }

    @Test
    fun printsStackTraceWhenDiagnosticContextVerboseTrue() {
        DiagnosticContext.verbose = true
        val cause = RuntimeException("inner reason")
        val error = CliktError("outer message", cause = cause)

        renderCliktErrorForTest(error)

        val errOut = errBuf.toString()
        assertTrue("Expected outer message", errOut.contains("outer message"))
        // Use a regex that matches an actual JVM stack-frame line.
        val stackFrameRegex = Regex("""at .+\(.+\.kt:\d+\)""")
        assertTrue(
            "Expected JVM stack-frame line, got: $errOut",
            stackFrameRegex.containsMatchIn(errOut),
        )
        assertTrue("Expected inner class name", errOut.contains("RuntimeException"))
    }

    @Test
    fun omitsStackTraceWhenDiagnosticContextVerboseFalse() {
        DiagnosticContext.verbose = false
        val cause = RuntimeException("inner reason")
        val error = CliktError("outer message", cause = cause)

        renderCliktErrorForTest(error)

        val errOut = errBuf.toString()
        assertTrue("Expected outer message printed", errOut.contains("outer message"))
        assertFalse("Did not expect stack-trace frames", errOut.contains("\tat "))
    }
}
