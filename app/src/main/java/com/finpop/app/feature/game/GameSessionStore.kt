package com.finpop.app.feature.game

import com.finpop.app.data.game.remote.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.time.Instant

data class GameSessionUi(
    val game: ServerGame? = null,
    val boundUserId: String? = null,
    val busy: Boolean = false,
    val recovering: Boolean = false,
    val foreground: Boolean = true,
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
    private val journal: GameRecoveryJournal = MemoryGameRecoveryJournal(),
) {
    private val mutable = MutableStateFlow(GameSessionUi())
    val state = mutable.asStateFlow()
    private var generation = 0
    private var owner: String? = null
    private var character = "CHEESE"
    private var activeJob: Job? = null
    private var record = GameRecoveryRecord()
    private var loaded = false
    private var refreshAfterRequest = false

    fun bindUser(userId: String) {
        if (owner == userId) return
        generation++
        activeJob?.cancel()
        owner = userId
        record = GameRecoveryRecord()
        loaded = false
        refreshAfterRequest = false
        mutable.value = GameSessionUi(boundUserId = userId)
    }

    fun start(characterId: String = character) {
        character = characterId
        if (state.value.busy) return
        if (state.value.pending != null) { retry(); return }
        if (state.value.game != null) { refresh(); return }
        perform(createCommand(), recovery = true, discover = true)
    }

    private fun createCommand() = GameCommand(GameOperation.CREATE, body = buildJsonObject {
        put("mode", "AI"); put("characterId", character)
    })

    fun startNewGame() {
        if (state.value.busy || state.value.pending != null ||
            state.value.game?.status !in setOf("FINISHED", "ABANDONED")) return
        perform(createCommand(), reset = true)
    }

    /** 화면이 보이지 않는 동안 후속 AI 요청을 보내지 않습니다. 복귀 시 먼저 서버를 조회합니다. */
    fun setForeground(visible: Boolean) {
        if (state.value.foreground == visible) return
        mutable.value = state.value.copy(foreground = visible)
        if (!visible) return
        if (state.value.busy) {
            if (!state.value.recovering) refreshAfterRequest = true
        } else retry()
    }

    private fun perform(requested: GameCommand, recovery: Boolean = false,
        discover: Boolean = false, reset: Boolean = false) {
        if (state.value.busy) return
        val epoch = generation
        val user = owner
        mutable.value = state.value.copy(busy = true, recovering = recovery, error = null,
            pending = if (requested.operation == GameOperation.GET) null else requested)
        activeJob = scope.launch {
            var command = requested
            suspend fun save(next: GameRecoveryRecord) {
                user?.let { journal.write(it, next) }
                ensureActive()
                if (epoch != generation) throw CancellationException()
                record = next
            }
            try {
                if (!loaded) {
                    val saved = user?.let { journal.read(it) } ?: record
                    ensureActive()
                    if (epoch != generation) return@launch
                    record = saved
                    loaded = true
                }
                if (reset) save(GameRecoveryRecord())
                if (discover) command = record.pending ?: record.gameId?.let { GameCommand(GameOperation.GET, it) } ?: requested
                val replay = recovery && command.operation != GameOperation.GET &&
                    (record.pending != null || !discover)
                val moving = !recovery && command.operation in setOf(GameOperation.ROLL, GameOperation.AI_ROLL)
                val actor = if (command.operation == GameOperation.AI_ROLL) "P2" else "P1"
                if (command.operation != GameOperation.GET) {
                    // 서버 호출 전 디스크에 기록합니다. 응답 직후 종료돼도 동일한 요청 키로 복구합니다.
                    save(record.copy(pending = command, gameId = command.gameId ?: record.gameId))
                }
                mutable.value = state.value.copy(pending = command.takeUnless { it.operation == GameOperation.GET },
                    rolling = moving, roller = if (moving) actor else state.value.roller)
                val started = System.currentTimeMillis()
                var reply = try { api.execute(command) }
                catch (error: GameApiException) {
                    if (command.operation == GameOperation.CREATE && error.code == "ACTIVE_GAME_EXISTS") {
                        val id = error.details["activeGameId"]?.jsonPrimitive?.contentOrNull ?: throw error
                        // 생성 충돌은 처리 결과가 확정됐습니다. 이후 실패는 조회만 재시도합니다.
                        save(record.copy(gameId = id, pending = null))
                        command = GameCommand(GameOperation.GET, id)
                        api.execute(command)
                    } else throw error
                }
                ensureActive()
                if (epoch != generation) return@launch
                val finishedTurn = if (command.finishAfterPurchase) reply.game.currentTurnId else record.finishTurnId
                if (replay && command.operation != GameOperation.GET) {
                    // 중복 요청 응답은 과거 스냅샷일 수 있으므로 최신 조회 전에는 화면·AI에 전달하지 않습니다.
                    save(record.copy(gameId = reply.game.gameId, pending = null, finishTurnId = finishedTurn))
                    command = GameCommand(GameOperation.GET, reply.game.gameId)
                    mutable.value = state.value.copy(pending = null)
                    reply = api.execute(command)
                }
                ensureActive()
                if (epoch != generation) return@launch
                val old = state.value
                if (old.game?.gameId == reply.game.gameId && reply.game.stateVersion < old.game.stateVersion)
                    throw GameApiException("STALE_GAME_STATE", "이전 상태가 응답되었습니다. 최신 상태를 다시 확인해 주세요.")
                val sameGame = record.gameId == reply.game.gameId
                val event = reply.purchaseEvent?.takeIf { !recovery && sameGame && it.action == "BUY" &&
                    it.eventId !in record.presentedPurchaseEvents }
                val offset = reply.serverTime?.let {
                    runCatching { Instant.parse(it).toEpochMilli() - System.currentTimeMillis() }.getOrNull()
                } ?: old.clockOffsetMillis
                val next = GameRecoveryRecord(gameId = reply.game.gameId,
                    acknowledgedAiResult = record.acknowledgedAiResult.takeIf { sameGame },
                    presentedPurchaseEvents = ((if (sameGame) record.presentedPurchaseEvents else emptyList()) +
                        listOfNotNull(event?.eventId)).takeLast(64),
                    finishTurnId = finishedTurn?.takeIf { it == reply.game.currentTurnId && reply.game.currentPlayerId == "P1" })
                save(next)
                if (moving && state.value.foreground) pause((900 - (System.currentTimeMillis() - started)).coerceAtLeast(0))
                ensureActive()
                if (epoch != generation) return@launch
                val animate = moving && state.value.foreground
                mutable.value = state.value.copy(game = reply.game, pending = null, rolling = false,
                    dice = reply.diceValue ?: old.dice, animatedPosition = if (animate) old.game?.player(actor)?.position else null,
                    clockOffsetMillis = offset, acknowledgedAiResult = next.acknowledgedAiResult,
                    finishAfterPurchase = next.finishTurnId != null,
                    purchaseEvents = if (!sameGame || recovery || !state.value.foreground) emptyList()
                        else old.purchaseEvents + listOfNotNull(event))
                if (animate) for (position in reply.movementPath) {
                    ensureActive()
                    if (epoch != generation || !state.value.foreground) break
                    mutable.value = state.value.copy(animatedPosition = position)
                    pause(180)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: GameApiException) {
                if (epoch == generation) {
                    if (error.definitive) {
                        try { save(record.copy(pending = null)) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* 저장 실패 시 기존 요청을 보존합니다. */ }
                    }
                    if (command.operation == GameOperation.GET && error.code == "RESOURCE_NOT_FOUND") {
                        try {
                            save(GameRecoveryRecord())
                            mutable.value = state.value.copy(game = null, purchaseEvents = emptyList())
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* 기록 정리가 실패하면 기존 게임 ID로 다시 조회합니다. */ }
                    }
                    mutable.value = state.value.copy(error = error.message ?: "요청을 처리하지 못했습니다.",
                        pending = record.pending)
                }
            } catch (_: Exception) {
                if (epoch == generation) mutable.value = state.value.copy(
                    error = "진행 상태를 복원하지 못했습니다. 연결과 앱 저장 공간을 확인한 뒤 다시 시도해 주세요.",
                    pending = record.pending)
            } finally {
                finishRequest(epoch)
            }
        }
    }

    private fun finishRequest(epoch: Int) {
        if (epoch != generation) return
        mutable.value = state.value.copy(busy = false, recovering = false, rolling = false, animatedPosition = null)
        if (refreshAfterRequest && state.value.foreground) {
            refreshAfterRequest = false
            if (state.value.error == null) retry()
        }
    }

    private fun action(operation: GameOperation, extra: JsonObject = buildJsonObject {}, finish: Boolean = false) {
        val current = state.value
        val game = current.game ?: return
        if (current.busy || current.pending != null || current.error != null || !current.foreground) return
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
        state.value.pending?.let { perform(it, recovery = true); return }
        val game = state.value.game
        if (game == null) {
            mutable.value = state.value.copy(error = null)
            start()
        } else perform(GameCommand(GameOperation.GET, game.gameId), recovery = true)
    }

    fun refresh() {
        if (state.value.pending != null || state.value.busy) return
        state.value.game?.let { perform(GameCommand(GameOperation.GET, it.gameId), recovery = true) }
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
        val current = state.value
        val result = current.game?.lastQuizResult ?: return
        if (result.playerId != "P2" || current.busy || current.pending != null || current.error != null || !current.foreground) return
        val epoch = generation
        val user = owner
        mutable.value = current.copy(busy = true)
        activeJob = scope.launch {
            try {
                val next = record.copy(acknowledgedAiResult = result.attemptId)
                user?.let { journal.write(it, next) }
                ensureActive()
                if (epoch == generation) {
                    record = next
                    mutable.value = state.value.copy(acknowledgedAiResult = result.attemptId)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (epoch == generation) mutable.value = state.value.copy(error = "확인 기록을 저장하지 못했습니다. 저장 공간을 확인해 주세요.")
            } finally {
                finishRequest(epoch)
            }
        }
    }

    fun purchaseAnimationFinished(eventId: String) {
        mutable.value = state.value.copy(purchaseEvents = state.value.purchaseEvents.filterNot { it.eventId == eventId })
    }

    /** AI 결과는 사용자 확인 뒤 구매·종료합니다. 미확인 팝업을 자동으로 닫지 않습니다. */
    fun advance() {
        val current = state.value
        val game = current.game ?: return
        if (current.busy || current.pending != null || current.error != null || !current.foreground || game.status != "ACTIVE") return
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
