package com.jslee1972.vlinkerobd.obd

import kotlinx.coroutines.flow.Flow

/** Byte-level transport a command queue sends/receives over. Implemented by BLE in production. */
interface ObdTransport {
    suspend fun write(bytes: ByteArray): Result<Unit>
    val incoming: Flow<ByteArray>
}

enum class ObdCommandKind { AT, OBD }

data class ObdCommand(
    val text: String,
    val kind: ObdCommandKind,
    val timeoutMs: Long = 2000,
    val maxAttempts: Int = 2,
)

sealed interface ObdCommandResult {
    data class Success(val raw: String) : ObdCommandResult
    data object Timeout : ObdCommandResult
    data object Cancelled : ObdCommandResult
}
