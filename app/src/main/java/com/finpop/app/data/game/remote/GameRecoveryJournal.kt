package com.finpop.app.data.game.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.UUID

/** 서버 상태 사본이 아니라 재시도와 화면 중복 방지에 필요한 최소 기록입니다. */
@Serializable
data class GameRecoveryRecord(
    val schemaVersion: Int = 1,
    val gameId: String? = null,
    val pending: GameCommand? = null,
    val acknowledgedAiResult: String? = null,
    val presentedPurchaseEvents: List<String> = emptyList(),
    val finishTurnId: String? = null,
)

interface GameRecoveryJournal {
    suspend fun read(userId: String): GameRecoveryRecord
    suspend fun write(userId: String, record: GameRecoveryRecord)
}

class MemoryGameRecoveryJournal : GameRecoveryJournal {
    private val records = mutableMapOf<String, GameRecoveryRecord>()
    override suspend fun read(userId: String) = records[userId] ?: GameRecoveryRecord()
    override suspend fun write(userId: String, record: GameRecoveryRecord) { records[userId] = record }
}

/** 손상된 기록의 요청 키를 새로 만들지 않고 복원을 중단합니다. */
internal object GameRecoveryCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    fun encode(record: GameRecoveryRecord): String = json.encodeToString(record)
    fun decode(text: String): GameRecoveryRecord {
        val raw = json.parseToJsonElement(text).jsonObject
        val pending = raw["pending"] as? JsonObject
        if (pending != null) require(pending["requestId"]?.jsonPrimitive?.contentOrNull != null)
        return json.decodeFromJsonElement<GameRecoveryRecord>(raw).also {
            require(it.schemaVersion == 1)
            it.gameId?.let(UUID::fromString)
            it.pending?.let { command ->
                UUID.fromString(command.requestId)
                require(command.operation != GameOperation.GET)
                command.gameId?.let(UUID::fromString)
                require(command.operation == GameOperation.CREATE || command.gameId == it.gameId)
            }
        }
    }
}
