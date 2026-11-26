package com.finpop.app.feature.game

import com.finpop.app.data.game.remote.ServerGame
import java.time.Instant
import kotlin.math.ceil

internal fun toBoardTileId(serverId: Int): Int {
    require(serverId in 0..13)
    return (serverId + 7) % 14
}

/** 서버 마감 시각을 기준으로 표시하며 최종 시간 초과 판정은 서버에 맡깁니다. */
internal fun quizSecondsRemaining(deadline: String, clockOffsetMillis: Long, nowMillis: Long): Int =
    ceil((Instant.parse(deadline).toEpochMilli() - nowMillis - clockOffsetMillis)
        .coerceAtLeast(0) / 1000.0).toInt().coerceAtMost(20)

internal fun ServerGame.toFinalBoardState(ui: GameSessionUi): GameBoardUiState {
    val p1 = player("P1")
    val p2 = player("P2")
    fun position(id: String, actual: Int) = toBoardTileId(
        if (ui.roller == id) ui.animatedPosition ?: actual else actual)
    fun owns(id: String) = tiles.filter { it.ownerPlayerId == id }.map { toBoardTileId(it.tileId) }.toSet()
    return GameBoardUiState(
        playerGold = p1.gameGold, aiGold = p2.gameGold,
        playerTurnsRemaining = p1.remainingTurns, aiTurnsRemaining = p2.remainingTurns,
        currentPlayerId = if (currentPlayerId == "P2") GameBoardPlayerId.P2 else GameBoardPlayerId.P1,
        dicePlayerId = if (ui.roller == "P2") GameBoardPlayerId.P2 else GameBoardPlayerId.P1,
        diceValue = ui.dice, isRolling = ui.rolling,
        canRollDice = status == "ACTIVE" && phase == "TURN_READY" && currentPlayerId == "P1" &&
            p1.remainingTurns > 0 && p1.islandWaitTurns == 0 &&
            !ui.busy && ui.pending == null && ui.error == null,
        playerPosition = position("P1", p1.position), aiPosition = position("P2", p2.position),
        playerOwnedTiles = owns("P1"), aiOwnedTiles = owns("P2"),
        pendingPurchases = ui.purchaseEvents.map { event ->
            TilePurchaseUiEvent(event.eventId, toBoardTileId(event.tileId),
                if (event.playerId == "P2") GameBoardPlayerId.P2 else GameBoardPlayerId.P1)
        },
    )
}
