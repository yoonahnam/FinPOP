package com.finpop.app.feature.game

import com.finpop.app.data.game.remote.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GameSessionStoreTest {
    private fun store(api: GameGateway) = GameSessionStore(api,
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), pause = {})

    @Test fun existingGameIsResumedWithoutManualIdEntry() {
        val calls = mutableListOf<GameCommand>()
        val s = store { command ->
            calls += command
            if(command.operation == GameOperation.CREATE) throw GameApiException("ACTIVE_GAME_EXISTS","진행 중",
                buildJsonObject { put("activeGameId",fixtureGameId) })
            ServerGameReply(fixtureGame())
        }
        s.start()
        assertEquals(listOf(GameOperation.CREATE,GameOperation.GET),calls.map { it.operation })
        assertEquals(fixtureGameId,calls.last().gameId)
        assertEquals(fixtureGameId,s.state.value.game!!.gameId)
    }

    @Test fun uncertainRetryPreservesTheOriginalRequest() {
        val calls = mutableListOf<GameCommand>()
        var failOnce = true
        val s = store { command ->
            calls += command
            if(command.operation == GameOperation.ROLL && failOnce){
                failOnce = false;throw IOException("응답 유실")
            }
            ServerGameReply(fixtureGame())
        }
        s.start();s.roll()
        val pending=s.state.value.pending!!
        assertNotNull(s.state.value.error)
        s.roll()
        assertEquals(2,calls.size)
        s.retry()
        assertEquals(pending,calls.last());assertEquals(calls[1].requestId,calls[2].requestId)
        assertNull(s.state.value.pending)
    }

    @Test fun selectedChoiceUsesIdInsteadOfArrayIndex() {
        val calls=mutableListOf<GameCommand>()
        val s=store { command -> calls+=command;ServerGameReply(fixtureGame("QUIZ_OPEN",quiz=fixtureQuiz())) }
        s.start();s.answer(3)
        assertEquals(GameOperation.ANSWER,calls.last().operation)
        assertEquals(3,calls.last().body["choiceId"]!!.jsonPrimitive.int)
        assertEquals(fixtureAttemptId,calls.last().body["attemptId"]!!.jsonPrimitive.content)
        assertEquals("QUIZ_OPEN",s.state.value.game!!.phase)
    }

    @Test fun aiResultMustBeConfirmedBeforePurchaseAndCompletion() {
        val calls=mutableListOf<GameCommand>()
        val s=store { command ->
            calls+=command
            val result=fixtureResult("P2")
            ServerGameReply(when(command.operation){
                GameOperation.AI_PURCHASE -> fixtureGame("TURN_REVIEW","P2",5,result=result)
                GameOperation.AI_COMPLETE -> fixtureGame("TURN_READY","P1",6)
                else -> fixtureGame("PURCHASE_PENDING","P2",4,purchase=ServerPurchase(2,160),result=result)
            })
        }
        s.start();s.advance();assertEquals(1,calls.size)
        s.acknowledgeAiResult();s.advance()
        assertEquals(GameOperation.AI_PURCHASE,calls.last().operation)
        s.advance();assertEquals(GameOperation.AI_COMPLETE,calls.last().operation)
        assertEquals("P1",s.state.value.game!!.currentPlayerId)
    }

    @Test fun userPurchaseCompletesOnlyAfterServerPurchaseSuccess() {
        val calls=mutableListOf<GameCommand>()
        val s=store { command ->
            calls+=command
            ServerGameReply(when(command.operation){
                GameOperation.PURCHASE -> fixtureGame("TURN_REVIEW",version=5,result=fixtureResult())
                GameOperation.COMPLETE -> fixtureGame("TURN_READY","P2",6)
                else -> fixtureGame("PURCHASE_PENDING",version=4,purchase=ServerPurchase(2,160),result=fixtureResult())
            })
        }
        s.start();s.buy()
        assertEquals(listOf(GameOperation.CREATE,GameOperation.PURCHASE),calls.map { it.operation })
        assertEquals("BUY",calls.last().body["action"]!!.jsonPrimitive.content)
        s.advance();assertEquals(GameOperation.COMPLETE,calls.last().operation)
    }

    @Test fun purchaseFailureCannotCompleteTurn() {
        val calls=mutableListOf<GameCommand>()
        val s=store { command ->
            calls+=command
            if(command.operation==GameOperation.PURCHASE)throw GameApiException("INSUFFICIENT_GOLD","잔액 부족")
            ServerGameReply(fixtureGame("PURCHASE_PENDING",purchase=ServerPurchase(2,160),result=fixtureResult()))
        }
        s.start();s.buy();s.advance()
        assertEquals(GameOperation.PURCHASE,calls.last().operation)
        assertFalse(calls.any { it.operation==GameOperation.COMPLETE })
        assertEquals("PURCHASE_PENDING",s.state.value.game!!.phase)
    }

    @Test fun unansweredQuestionCannotBeSkippedByFinishAction() {
        val calls=mutableListOf<GameCommand>()
        val s=store { command -> calls+=command;ServerGameReply(fixtureGame("QUIZ_OPEN",quiz=fixtureQuiz())) }
        s.start();s.finishUserResult()
        assertEquals(1,calls.size)
    }

    @Test fun busyStoreRejectsSecondMutation() = runBlocking {
        val gate=CompletableDeferred<ServerGameReply>()
        var calls=0
        val s=store { calls++;gate.await() }
        s.start();s.start();s.initiative()
        assertEquals(1,calls)
        gate.complete(ServerGameReply(fixtureGame()))
        yield()
        assertFalse(s.state.value.busy)
    }

    @Test fun accountSwitchDiscardsPreviousUsersGame() {
        val s=store { ServerGameReply(fixtureGame()) }
        s.bindUser("first");s.start()
        assertNotNull(s.state.value.game)
        s.bindUser("second")
        assertNull(s.state.value.game);assertNull(s.state.value.pending)
    }

    @Test fun mappingAndDeadlineUseServerData() {
        val game=fixtureGame()
        val mapped=game.toFinalBoardState(GameSessionUi(game=game))
        assertEquals(700,mapped.playerGold);assertEquals(730,mapped.aiGold)
        assertEquals(7,mapped.playerPosition);assertEquals(9,mapped.aiPosition)
        assertEquals(20,quizSecondsRemaining("2026-11-24T06:00:20Z",0,
            java.time.Instant.parse("2026-11-24T06:00:00Z").toEpochMilli()))
        assertEquals(0,quizSecondsRemaining("2026-11-24T06:00:20Z",0,
            java.time.Instant.parse("2026-11-24T06:00:21Z").toEpochMilli()))
    }
}
