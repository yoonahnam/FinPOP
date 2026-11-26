package com.finpop.app.feature.game

import com.finpop.app.data.game.remote.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GameRecoveryTest {
    private val user = "11111111-1111-4111-8111-111111111111"
    private fun store(journal: GameRecoveryJournal, api: GameGateway) = GameSessionStore(api,
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), pause = {}, journal = journal).also { it.bindUser(user) }

    @Test fun coldStartGetsTheSavedGameWithoutCreatingAnother() {
        val journal=MemoryGameRecoveryJournal();val calls=mutableListOf<GameCommand>()
        val api=GameGateway { calls+=it;ServerGameReply(fixtureGame("QUIZ_OPEN",quiz=fixtureQuiz())) }
        store(journal,api).start();calls.clear()
        val restored=store(journal,api);restored.start()
        assertEquals(listOf(GameOperation.GET),calls.map { it.operation })
        assertEquals(fixtureAttemptId,restored.state.value.game!!.pendingQuiz!!.attemptId)
    }

    @Test fun pausedGameStaysPausedAfterProcessRestart() {
        val journal=MemoryGameRecoveryJournal()
        val api=GameGateway { ServerGameReply(fixtureGame("QUIZ_OPEN",quiz=fixtureQuiz()).copy(status="PAUSED",remainingQuizMs=11000)) }
        store(journal,api).start();val s=store(journal,api);s.start();s.advance();s.timeout()
        assertEquals("PAUSED",s.state.value.game!!.status);assertEquals(11000,s.state.value.game!!.remainingQuizMs)
        assertFalse(s.state.value.busy)
    }

    @Test fun unresolvedCreateUsesOriginalKeyAfterProcessRestart() {
        val journal=MemoryGameRecoveryJournal();val calls=mutableListOf<GameCommand>()
        val first=store(journal) { c -> calls+=c;throw IOException("응답 유실") };first.start()
        val pending=first.state.value.pending!!
        calls.clear()
        val restored=store(journal) { c -> calls+=c;ServerGameReply(fixtureGame()) };restored.start()
        assertEquals(pending,calls.first());assertEquals(GameOperation.GET,calls.last().operation)
        assertNull(restored.state.value.pending)
    }

    @Test fun uncertainRollIsRecoveredWithoutMovementAnimationOrOldQuizSnapshot() {
        val journal=MemoryGameRecoveryJournal();var moved=false
        val original=fixtureGame();val fresh=fixtureGame("TURN_REVIEW",version=5,result=fixtureResult())
        val first=store(journal) { c ->
            if(c.operation==GameOperation.ROLL){moved=true;throw IOException("응답 유실")}
            ServerGameReply(original)
        };first.start();first.roll();assertTrue(moved)
        val command=first.state.value.pending!!
        val calls=mutableListOf<GameCommand>();var pauses=0
        val restored=GameSessionStore(GameGateway { c ->
            calls+=c
            if(c.operation==GameOperation.GET) ServerGameReply(fresh)
            else ServerGameReply(fixtureGame("QUIZ_OPEN",version=3,quiz=fixtureQuiz()),diceValue=4,movementPath=listOf(1,2,3,4))
        },CoroutineScope(SupervisorJob()+Dispatchers.Unconfined),pause={pauses++},journal=journal)
        restored.bindUser(user);restored.start()
        assertEquals(command,calls.first());assertEquals(GameOperation.GET,calls.last().operation)
        assertEquals(5,restored.state.value.game!!.stateVersion);assertEquals(0,pauses)
        assertNull(restored.state.value.game!!.pendingQuiz);assertNull(restored.state.value.animatedPosition)
    }

    @Test fun confirmedAiResultIsNotShownAgainAfterProcessRestart() {
        val journal=MemoryGameRecoveryJournal();val calls=mutableListOf<GameCommand>()
        val game=fixtureGame("TURN_REVIEW","P2",5,result=fixtureResult("P2"))
        val api=GameGateway { c -> calls+=c;ServerGameReply(game) }
        val first=store(journal,api);first.start();first.acknowledgeAiResult()
        calls.clear();val restored=store(journal,api);restored.start()
        assertEquals(fixtureAttemptId,restored.state.value.acknowledgedAiResult)
        restored.advance();assertEquals(GameOperation.AI_COMPLETE,calls.last().operation)
    }

    @Test fun unconfirmedAiResultWaitsForConfirmationAfterRestore() {
        val journal=MemoryGameRecoveryJournal();val calls=mutableListOf<GameCommand>()
        val api=GameGateway { c ->calls+=c;ServerGameReply(fixtureGame("TURN_REVIEW","P2",5,result=fixtureResult("P2"))) }
        store(journal,api).start();calls.clear();val restored=store(journal,api);restored.start();restored.advance()
        assertEquals(listOf(GameOperation.GET),calls.map { it.operation });assertNull(restored.state.value.acknowledgedAiResult)
    }

    @Test fun interruptedPurchaseUsesSameKeyAndFinishesTurnWithoutReplayingStamp() {
        val journal=MemoryGameRecoveryJournal();val calls=mutableListOf<GameCommand>()
        val purchase=fixtureGame("PURCHASE_PENDING",version=4,purchase=ServerPurchase(2,160),result=fixtureResult())
        val first=store(journal) { c -> if(c.operation==GameOperation.PURCHASE) throw IOException();ServerGameReply(purchase) }
        first.start();first.buy();val pending=first.state.value.pending!!
        val reviewed=fixtureGame("TURN_REVIEW",version=5,result=fixtureResult())
        val restored=store(journal) { c ->
            calls+=c
            ServerGameReply(if(c.operation==GameOperation.COMPLETE)fixtureGame("TURN_READY","P2",6) else reviewed,
                purchaseEvent=if(c.operation==GameOperation.PURCHASE)ServerPurchaseEvent("stamp","P1",2,"BUY") else null)
        };restored.start()
        assertEquals(pending,calls.first());assertTrue(restored.state.value.purchaseEvents.isEmpty())
        assertTrue(restored.state.value.finishAfterPurchase);restored.advance()
        assertEquals(GameOperation.COMPLETE,calls.last().operation)
        assertFalse(restored.state.value.finishAfterPurchase)
    }

    @Test fun failedFreshReadDoesNotResubmitAnAlreadyResolvedMutation() {
        val journal=MemoryGameRecoveryJournal();val calls=mutableListOf<GameCommand>()
        val first=store(journal) { c ->if(c.operation==GameOperation.ROLL)throw IOException();ServerGameReply(fixtureGame()) }
        first.start();first.roll()
        var failGet=true
        val restored=store(journal) { c ->
            calls+=c;if(c.operation==GameOperation.GET && failGet)throw IOException()
            ServerGameReply(fixtureGame("QUIZ_OPEN",version=3,quiz=fixtureQuiz()))
        };restored.start();assertNull(restored.state.value.pending);assertNotNull(restored.state.value.error)
        failGet=false;restored.retry()
        assertEquals(1,calls.count { it.operation==GameOperation.ROLL });assertEquals(GameOperation.GET,calls.last().operation)
    }

    @Test fun journalWriteFailurePreventsServerMutation() {
        var calls=0
        val journal=object:GameRecoveryJournal {
            override suspend fun read(userId:String)=GameRecoveryRecord()
            override suspend fun write(userId:String,record:GameRecoveryRecord){throw IOException("저장 실패")}
        }
        val s=store(journal) { calls++;ServerGameReply(fixtureGame()) };s.start()
        assertEquals(0,calls);assertNotNull(s.state.value.error)
    }

    @Test fun accountSwitchNeverReusesOtherUsersPendingCommand() {
        val journal=MemoryGameRecoveryJournal();val first=store(journal) { throw IOException() };first.start()
        val calls=mutableListOf<GameCommand>();val next=store(journal) { c ->calls+=c;ServerGameReply(fixtureGame()) }
        next.bindUser("22222222-2222-4222-8222-222222222222");next.start()
        assertEquals(GameOperation.CREATE,calls.first().operation)
        assertNotEquals(first.state.value.pending!!.requestId,calls.first().requestId)
    }

    @Test fun foregroundReturnFetchesServerBeforeAiCanAdvance() = runBlocking {
        val journal=MemoryGameRecoveryJournal();val calls=mutableListOf<GameCommand>()
        val gate=CompletableDeferred<ServerGameReply>();var waitForGet=false
        val s=store(journal) { c ->calls+=c;if(c.operation==GameOperation.GET && waitForGet)gate.await() else ServerGameReply(fixtureGame(actor="P2")) }
        s.start();s.setForeground(false);s.advance();assertEquals(1,calls.size)
        waitForGet=true;s.setForeground(true);s.advance();assertEquals(GameOperation.GET,calls.last().operation);assertTrue(s.state.value.busy)
        gate.complete(ServerGameReply(fixtureGame(actor="P1",version=3)));yield();s.advance()
        assertEquals(GameOperation.GET,calls.last().operation)
    }

    @Test fun staleSnapshotCannotRollBackCurrentBoard() {
        var stale=false
        val s=store(MemoryGameRecoveryJournal()) { ServerGameReply(fixtureGame(version=if(stale)2 else 7)) }
        s.start();stale=true;s.refresh()
        assertEquals(7,s.state.value.game!!.stateVersion);assertNotNull(s.state.value.error)
    }

    @Test fun duplicatePurchaseEventDoesNotAnimateAfterItsCallback() {
        val event=ServerPurchaseEvent("same-event","P1",2,"BUY")
        val s=store(MemoryGameRecoveryJournal()) { c ->ServerGameReply(fixtureGame("PURCHASE_PENDING",purchase=ServerPurchase(2,160),result=fixtureResult()),
            purchaseEvent=event.takeIf { c.operation==GameOperation.PURCHASE }) }
        s.start();s.buy();assertEquals(1,s.state.value.purchaseEvents.size)
        s.purchaseAnimationFinished(event.eventId);s.buy();assertTrue(s.state.value.purchaseEvents.isEmpty())
    }

    @Test fun codecRoundTripPreservesPendingCommandAndContainsNoSessionToken() {
        val command=GameCommand(GameOperation.ANSWER,fixtureGameId,
            buildJsonObject {put("expectedVersion",3);put("attemptId",fixtureAttemptId);put("choiceId",2)})
        val record=GameRecoveryRecord(gameId=fixtureGameId,pending=command,acknowledgedAiResult="confirmed")
        val encoded=GameRecoveryCodec.encode(record)
        assertEquals(record,GameRecoveryCodec.decode(encoded));assertFalse(encoded.contains("accessToken"));assertFalse(encoded.contains("Authorization"))
        val withoutKey=Json.parseToJsonElement(encoded).jsonObject.toMutableMap()
        withoutKey["pending"]=JsonObject(withoutKey["pending"]!!.jsonObject.filterKeys {it!="requestId"})
        assertThrows(IllegalArgumentException::class.java) {GameRecoveryCodec.decode(JsonObject(withoutKey).toString())}
    }

    @Test fun reenteringSameStoreFetchesGameWithoutDuplicateCreation() {
        val calls=mutableListOf<GameCommand>()
        val s=store(MemoryGameRecoveryJournal()) {c->calls+=c;ServerGameReply(fixtureGame())}
        s.start();s.start()
        assertEquals(listOf(GameOperation.CREATE,GameOperation.GET),calls.map {it.operation})
    }

    @Test fun pendingPurchaseAndUserReviewAreRestoredFromServer() {
        for(phase in listOf("PURCHASE_PENDING","TURN_REVIEW")) {
            val journal=MemoryGameRecoveryJournal()
            val game=fixtureGame(phase,version=5,result=fixtureResult(),purchase=if(phase=="PURCHASE_PENDING")ServerPurchase(2,160) else null)
            val api=GameGateway {ServerGameReply(game)}
            store(journal,api).start();val restored=store(journal,api);restored.start()
            assertEquals(game,restored.state.value.game);assertEquals(phase,restored.state.value.game!!.phase)
        }
    }

    @Test fun deletedGameCanBeDiscoveredAgainOnExplicitRetry() {
        val journal=MemoryGameRecoveryJournal();store(journal) {ServerGameReply(fixtureGame())}.start()
        val calls=mutableListOf<GameCommand>()
        val s=store(journal) {c->
            calls+=c;if(c.operation==GameOperation.GET)throw GameApiException("RESOURCE_NOT_FOUND","대결 없음")
            ServerGameReply(fixtureGame())
        };s.start();assertNull(s.state.value.game);s.retry()
        assertEquals(GameOperation.CREATE,calls.last().operation)
    }

    @Test fun processRestartRequiresUserBindingBeforeBoardEntry() {
        val s=GameSessionStore(GameGateway {ServerGameReply(fixtureGame())},CoroutineScope(SupervisorJob()+Dispatchers.Unconfined),pause={})
        assertNull(s.state.value.boundUserId)
        s.bindUser(user);assertEquals(user,s.state.value.boundUserId)
        s.bindUser("other");assertEquals("other",s.state.value.boundUserId);assertNull(s.state.value.game)
    }

    @Test fun corruptJournalDoesNotStartAnotherGameOrGenerateANewRequestKey() {
        var calls=0
        val journal=object:GameRecoveryJournal {
            override suspend fun read(userId:String):GameRecoveryRecord=throw IllegalArgumentException("손상")
            override suspend fun write(userId:String,record:GameRecoveryRecord)=Unit
        }
        val s=store(journal) {calls++;ServerGameReply(fixtureGame())};s.start();s.retry()
        assertEquals(0,calls);assertNotNull(s.state.value.error)
    }

    @Test fun failedAiConfirmationSaveCannotAdvanceOrHideResult() {
        var fail=false
        val memory=MemoryGameRecoveryJournal()
        val journal=object:GameRecoveryJournal {
            override suspend fun read(userId:String)=memory.read(userId)
            override suspend fun write(userId:String,record:GameRecoveryRecord) {
                if(fail)throw IOException();memory.write(userId,record)
            }
        }
        val calls=mutableListOf<GameCommand>()
        val s=store(journal) {c->calls+=c;ServerGameReply(fixtureGame("TURN_REVIEW","P2",5,result=fixtureResult("P2")))}
        s.start();fail=true;s.acknowledgeAiResult();s.advance()
        assertNull(s.state.value.acknowledgedAiResult);assertNotNull(s.state.value.error);assertEquals(1,calls.size)
    }

    @Test fun accountSwitchWhileRequestIsRunningCannotApplyPreviousGame() = runBlocking {
        val gate=CompletableDeferred<ServerGameReply>()
        val s=store(MemoryGameRecoveryJournal()) {gate.await()}
        s.start();s.bindUser("other-account");gate.complete(ServerGameReply(fixtureGame()));yield()
        assertEquals("other-account",s.state.value.boundUserId);assertNull(s.state.value.game);assertFalse(s.state.value.busy)
    }
}
