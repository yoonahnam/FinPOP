package com.finpop.app.feature.game

import com.finpop.app.data.game.remote.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.time.Instant

data class GameSessionUi(
    val game: ServerGame? = null,
    val busy: Boolean = false,
    val rolling: Boolean = false,
    val dice: Int = 1,
    val roller: String = "P1",
    val animatedPosition: Int? = null,
    val pending: GameCommand? = null,
    val error: String? = null,
    val clockOffsetMillis: Long = 0,
    val acknowledgedAiResult: String? = null,
    val finishAfterPurchase: Boolean = false,
    val purchaseEvents: List<ServerPurchaseEvent> = emptyList(),
)

/** 서버 응답만 상태로 반영합니다. 화면 재생성과 별도로 보존할 수 있는 게임 진행 상태입니다. */
class GameSessionStore(
    private val api: GameGateway,
    private val scope: CoroutineScope,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutable = MutableStateFlow(GameSessionUi())
    val state = mutable.asStateFlow()
    private var generation = 0
    private var owner: String? = null
    private var character = "CHEESE"
    private var activeJob: Job? = null

    fun bindUser(userId: String) {
        if (owner == userId) return
        generation++
        activeJob?.cancel()
        owner = userId
        mutable.value = GameSessionUi()
    }

    fun start(characterId: String = character) {
        character = characterId
        if (state.value.game != null || state.value.busy) return
        perform(GameCommand(GameOperation.CREATE, body = buildJsonObject {
            put("mode", "AI"); put("characterId", character)
        }))
    }

    fun startNewGame() {
        if (state.value.busy || state.value.game?.status !in setOf("FINISHED", "ABANDONED")) return
        mutable.value = GameSessionUi()
        start()
    }

    private fun perform(command: GameCommand, retry: Boolean = false) {
        if (state.value.busy) return
        val epoch = generation
        val moving = command.operation in setOf(GameOperation.ROLL, GameOperation.AI_ROLL)
        val actor = if (command.operation == GameOperation.AI_ROLL) "P2" else "P1"
        mutable.value = state.value.copy(busy = true, error = null,
            pending = if (command.operation == GameOperation.GET) null else command,
            rolling = moving, roller = if (moving) actor else state.value.roller)
        activeJob = scope.launch {
            try {
                val started = System.currentTimeMillis()
                val reply = try { api.execute(command) }
                catch (error: GameApiException) {
                    if (command.operation == GameOperation.CREATE && error.code == "ACTIVE_GAME_EXISTS") {
                        val id = error.details["activeGameId"]?.jsonPrimitive?.contentOrNull ?: throw error
                        api.execute(GameCommand(GameOperation.GET, id))
                    } else throw error
                }
                ensureActive()
                if (epoch != generation) return@launch
                if (moving) pause((900 - (System.currentTimeMillis() - started)).coerceAtLeast(0))
                val old = state.value
                val offset = if (!retry) reply.serverTime?.let {
                    runCatching { Instant.parse(it).toEpochMilli() - System.currentTimeMillis() }.getOrNull()
                } ?: old.clockOffsetMillis else old.clockOffsetMillis
                val oldPosition = if (moving) old.game?.player(actor)?.position else null
                val event = reply.purchaseEvent?.takeIf { it.action == "BUY" }
                val queue = if (event != null && old.purchaseEvents.none { it.eventId == event.eventId })
                    old.purchaseEvents + event else old.purchaseEvents
                mutable.value = old.copy(game = reply.game, pending = null, rolling = false,
                    dice = reply.diceValue ?: old.dice, animatedPosition = oldPosition,
                    clockOffsetMillis = offset, finishAfterPurchase = command.finishAfterPurchase,
                    purchaseEvents = queue)
                for (position in reply.movementPath) {
                    if (epoch != generation) return@launch
                    mutable.value = state.value.copy(animatedPosition = position)
                    pause(180)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: GameApiException) {
                if (epoch == generation) mutable.value = state.value.copy(
                    error = error.message ?: "요청을 처리하지 못했습니다.",
                    pending = if (error.definitive) null else command.takeUnless { it.operation == GameOperation.GET })
            } catch (_: Exception) {
                if (epoch == generation) mutable.value = state.value.copy(
                    error = "서버 응답을 확인하지 못했습니다. 같은 요청으로 다시 시도해 주세요.",
                    pending = command.takeUnless { it.operation == GameOperation.GET })
            } finally {
                if (epoch == generation) mutable.value = state.value.copy(busy = false, rolling = false, animatedPosition = null)
            }
        }
    }

    private fun action(operation: GameOperation, extra: JsonObject = buildJsonObject {}, finish: Boolean = false) {
        val current = state.value
        val game = current.game ?: return
        if (current.busy || current.pending != null || current.error != null) return
        val allowed = when (operation) {
            GameOperation.PAUSE -> game.status == "ACTIVE"
            GameOperation.RESUME -> game.status == "PAUSED"
            GameOperation.ABANDON -> game.status in setOf("ACTIVE", "PAUSED")
            else -> game.status == "ACTIVE"
        }
        if (!allowed) return
        val body = buildJsonObject {
            put("expectedVersion", game.stateVersion)
            extra.forEach { (key, value) -> put(key, value) }
        }
        perform(GameCommand(operation, game.gameId, body, finishAfterPurchase = finish))
    }

    fun retry() {
        if (state.value.busy) return
        state.value.pending?.let { perform(it, retry = true); return }
        val game = state.value.game
        if (game == null) {
            mutable.value = state.value.copy(error = null)
            start()
        } else perform(GameCommand(GameOperation.GET, game.gameId))
    }

    fun refresh() {
        if (state.value.pending != null || state.value.busy) return
        state.value.game?.let { perform(GameCommand(GameOperation.GET, it.gameId)) }
    }

    fun pauseGame() = action(GameOperation.PAUSE)
    fun resumeGame() = action(GameOperation.RESUME)
    fun abandonGame() = action(GameOperation.ABANDON)

    fun initiative() {
        if (state.value.game?.phase == "INITIATIVE_PENDING") action(GameOperation.INITIATIVE)
    }

    fun roll() {
        val game = state.value.game ?: return
        if (game.phase == "TURN_READY" && game.currentPlayerId == "P1" &&
            game.player("P1").remainingTurns > 0 && game.player("P1").islandWaitTurns == 0) action(GameOperation.ROLL)
    }

    fun answer(choiceId: Int) {
        val game = state.value.game ?: return
        val quiz = game.pendingQuiz ?: return
        if (game.currentPlayerId != "P1" || game.phase != "QUIZ_OPEN" ||
            quiz.choices.none { it.id == choiceId }) return
        action(GameOperation.ANSWER, buildJsonObject { put("attemptId", quiz.attemptId); put("choiceId", choiceId) })
    }

    fun timeout() {
        val game = state.value.game ?: return
        val quiz = game.pendingQuiz ?: return
        if (game.currentPlayerId == "P1" && game.phase == "QUIZ_OPEN")
            action(GameOperation.TIMEOUT, buildJsonObject { put("attemptId", quiz.attemptId) })
    }

    fun buy() {
        val game = state.value.game ?: return
        val purchase = game.pendingPurchase ?: return
        if (game.currentPlayerId == "P1" && game.phase == "PURCHASE_PENDING" &&
            game.player("P1").gameGold >= purchase.price) action(GameOperation.PURCHASE,
            buildJsonObject { put("tileId", purchase.tileId); put("action", "BUY") }, finish = true)
    }

    fun finishUserResult() {
        val game = state.value.game ?: return
        if (game.currentPlayerId != "P1") return
        when (game.phase) {
            "PURCHASE_PENDING" -> game.pendingPurchase?.let { action(GameOperation.PURCHASE,
                buildJsonObject { put("tileId", it.tileId); put("action", "PASS") }, finish = true) }
            "TURN_REVIEW" -> game.currentTurnId?.let {
                action(GameOperation.COMPLETE, buildJsonObject { put("turnId", it) })
            }
        }
    }

    fun skipIsland() {
        val game = state.value.game ?: return
        if (game.phase == "TURN_READY" && game.currentPlayerId == "P1" &&
            game.player("P1").islandWaitTurns > 0)
            game.currentTurnId?.let { action(GameOperation.SKIP_ISLAND, buildJsonObject { put("turnId", it) }) }
    }

    fun acknowledgeAiResult() {
        val result = state.value.game?.lastQuizResult ?: return
        if (result.playerId == "P2") mutable.value = state.value.copy(acknowledgedAiResult = result.attemptId)
    }

    fun purchaseAnimationFinished(eventId: String) {
        mutable.value = state.value.copy(purchaseEvents = state.value.purchaseEvents.filterNot { it.eventId == eventId })
    }

    /** AI 결과는 사용자 확인 뒤 구매·종료합니다. 미확인 팝업을 자동으로 닫지 않습니다. */
    fun advance() {
        val current = state.value
        val game = current.game ?: return
        if (current.busy || current.pending != null || current.error != null || game.status != "ACTIVE") return
        if (game.currentPlayerId == "P1") {
            if (game.phase == "TURN_REVIEW" && current.finishAfterPurchase) finishUserResult()
            return
        }
        if (game.currentPlayerId != "P2") return
        val result = game.lastQuizResult
        if (game.phase in setOf("PURCHASE_PENDING", "TURN_REVIEW") &&
            result != null && result.attemptId != current.acknowledgedAiResult) return
        when (game.phase) {
            "TURN_READY" -> if (game.player("P2").islandWaitTurns > 0)
                game.currentTurnId?.let { action(GameOperation.AI_SKIP_ISLAND, buildJsonObject { put("turnId", it) }) }
                else action(GameOperation.AI_ROLL)
            "QUIZ_OPEN" -> game.pendingQuiz?.let {
                action(GameOperation.AI_ANSWER, buildJsonObject { put("attemptId", it.attemptId) })
            }
            "PURCHASE_PENDING" -> game.pendingPurchase?.let {
                action(GameOperation.AI_PURCHASE, buildJsonObject { put("tileId", it.tileId) })
            }
            "TURN_REVIEW" -> game.currentTurnId?.let {
                action(GameOperation.AI_COMPLETE, buildJsonObject { put("turnId", it) })
            }
        }
    }
}
