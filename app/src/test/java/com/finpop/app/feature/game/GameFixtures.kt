package com.finpop.app.feature.game

import com.finpop.app.data.game.remote.*

internal const val fixtureGameId = "11111111-1111-4111-8111-111111111111"
internal const val fixtureAttemptId = "22222222-2222-4222-8222-222222222222"
internal const val fixtureTurnId = "33333333-3333-4333-8333-333333333333"

internal fun fixtureGame(
    phase: String = "TURN_READY", actor: String = "P1", version: Int = 2,
    quiz: ServerQuiz? = null, purchase: ServerPurchase? = null, result: ServerQuizResult? = null,
) = ServerGame(fixtureGameId, phase, version, currentPlayerId = actor, currentTurnId = fixtureTurnId,
    players = listOf(ServerPlayer("P1",700,7,0),ServerPlayer("P2",730,7,2)),
    tiles = (0..13).map { ServerTile(it) }, pendingQuiz = quiz, pendingPurchase = purchase,
    lastQuizResult = result)

internal fun fixtureQuiz() = ServerQuiz(fixtureAttemptId, 2, "서버에서 발급한 채권 문제",
    listOf(ServerChoice(4,"보기 4"),ServerChoice(2,"보기 2"),ServerChoice(1,"보기 1"),ServerChoice(3,"보기 3")),
    "2026-11-24T06:00:20Z", hint = "서버 힌트")
internal fun fixtureResult(actor: String = "P1", resolution: String = "CORRECT") =
    ServerQuizResult(fixtureAttemptId, actor, resolution, "서버에서 발급한 채권 문제",
        fixtureQuiz().choices, correctChoiceId = 4, selectedChoiceId = 2, explanation = "서버 판정 해설")
