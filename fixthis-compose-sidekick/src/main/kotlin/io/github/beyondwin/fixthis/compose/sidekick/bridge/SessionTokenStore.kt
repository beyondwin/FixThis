package io.github.beyondwin.fixthis.compose.sidekick.bridge

import android.content.Context
import android.util.Base64
import kotlinx.serialization.Serializable
import java.io.File
import java.security.SecureRandom

@Serializable
data class SidekickSession(
    val schemaVersion: String = "1.0",
    val packageName: String,
    val socketName: String,
    val socketAddress: String,
    val token: String,
    val sidekickVersion: String,
    val bridgeProtocolVersion: String,
    val createdAtEpochMillis: Long,
    val processStartEpochMillis: Long,
)

class SessionTokenStore(
    private val context: Context,
    private val tokenGenerator: () -> String = { generateToken() },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sidekickVersion: String = FixThisSidekickVersion,
) {
    fun create(packageName: String): SidekickSession {
        val socketName = socketNameForPackage(packageName)
        val now = clock()
        return SidekickSession(
            packageName = packageName,
            socketName = socketName,
            socketAddress = "localabstract:$socketName",
            token = tokenGenerator(),
            sidekickVersion = sidekickVersion,
            bridgeProtocolVersion = BridgeProtocol.VERSION,
            createdAtEpochMillis = now,
            processStartEpochMillis = now,
        )
    }

    fun createAndWrite(packageName: String): SidekickSession = create(packageName).also(::write)

    fun write(session: SidekickSession) {
        val directory = File(context.filesDir, "fixthis")
        check(directory.exists() || directory.mkdirs()) {
            "Could not create FixThis session directory: ${directory.absolutePath}"
        }
        File(directory, "session.json").writeText(
            BridgeProtocol.json.encodeToString(SidekickSession.serializer(), session),
        )
    }

    companion object {
        fun socketNameForPackage(packageName: String): String = "fixthis_$packageName"

        fun generateToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }
}

const val FixThisSidekickVersion: String = "0.1.0"
