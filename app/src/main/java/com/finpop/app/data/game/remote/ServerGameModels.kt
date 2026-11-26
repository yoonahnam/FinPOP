package com.finpop.app.data.game.remote

import kotlinx.serialization.Serializable

/** 원격 API 전용 모델입니다. 로컬 메모리 게임 모델과 별도로 관리합니다. */
@Serializable
data class ServerGame(
    val gameId: String,
    val phase: String,
    val stateVersion: Int,
    val status: String = "ACTIVE",
    val pausedAt: String? = null,
    val abandonedAt: String? = null,
    val remainingQuizMs: Int? = null,
    val quizStage: Int = 1,
    val currentPlayerId: String? = null,
    val currentTurnId: String? = null,
    val players: List<ServerPlayer>,
    val tiles: List<ServerTile>,
    val pendingQuiz: ServerQuiz? = null,
    val pendingPurchase: ServerPurchase? = null,
    val lastQuizResult: ServerQuizResult? = null,
) {
    fun player(id: String) = players.single { it.playerId == id }
}

@Serializable
data class ServerPlayer(
    val playerId: String,
    val gameGold: Int,
    val remainingTurns: Int,
    val position: Int,
    val islandWaitTurns: Int = 0,
)

@Serializable
data class ServerTile(val tileId: Int, val ownerPlayerId: String? = null)

@Serializable
data class ServerChoice(val id: Int, val text: String)

@Serializable
data class ServerQuiz(
    val attemptId: String,
    val tileId: Int,
    val question: String,
    val choices: List<ServerChoice>,
    val deadlineAt: String,
    val hint: String? = null,
    val stage: Int = 1,
)

@Serializable
data class ServerQuizResult(
    val attemptId: String,
    val playerId: String,
    val resolution: String,
    val question: String,
    val choices: List<ServerChoice>,
    val correctChoiceId: Int,
    val selectedChoiceId: Int? = null,
    val explanation: String,
)

@Serializable
data class ServerPurchase(val tileId: Int, val price: Int)

@Serializable
data class ServerPurchaseEvent(
    val eventId: String, val playerId: String, val tileId: Int, val action: String,
)

data class ServerGameReply(
    val game: ServerGame,
    val diceValue: Int? = null,
    val movementPath: List<Int> = emptyList(),
    val purchaseEvent: ServerPurchaseEvent? = null,
    val serverTime: String? = null,
)

enum class GameOperation(val suffix: String) {
    PAUSE("/pause"), RESUME("/resume"), ABANDON("/abandon"),
    CREATE(""), GET(""), INITIATIVE("/initiative"), ROLL("/rolls"),
    ANSWER("/quiz-answers"), TIMEOUT("/quiz-timeouts"), PURCHASE("/purchases"),
    COMPLETE("/turn-completions"), SKIP_ISLAND("/island-skips"),
    AI_ROLL("/ai/rolls"), AI_ANSWER("/ai/quiz-answers"), AI_PURCHASE("/ai/purchases"),
    AI_COMPLETE("/ai/turn-completions"), AI_SKIP_ISLAND("/ai/island-skips"),
}
