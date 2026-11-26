package com.finpop.app.feature.game

import com.finpop.app.data.game.remote.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RemoteGameRepositoryTest {
    private fun envelope(game: ServerGame) = buildJsonObject {
        put("data", Json.encodeToJsonElement(game))
    }.toString()

    @Test fun decodesIssuedQuestionWithoutInventingAnswer() = runBlocking {
        val expected = fixtureGame("QUIZ_OPEN", quiz = fixtureQuiz())
        val api = RemoteGameRepository("https://example.supabase.co", "public-key", { "user-token" }, { "refreshed" },
            GameHttpTransport { _, _, _ -> GameHttpResult(200, envelope(expected)) })
        val actual = api.execute(GameCommand(GameOperation.GET, fixtureGameId)).game
        assertEquals(expected.pendingQuiz, actual.pendingQuiz)
        assertNull(actual.lastQuizResult)
        assertEquals(listOf(4,2,1,3), actual.pendingQuiz!!.choices.map { it.id })
    }

    @Test fun unauthorizedRefreshReusesBodyAndIdempotencyKey() = runBlocking {
        val bodies = mutableListOf<String?>()
        val keys = mutableListOf<String?>()
        val authorization = mutableListOf<String?>()
        var count = 0
        val api = RemoteGameRepository("https://example.supabase.co/", "public-key", { "old-token" }, { "new-token" },
            GameHttpTransport { url, headers, body ->
                assertTrue(url.endsWith("/v1/games/" + fixtureGameId + "/quiz-answers"))
                keys += headers["Idempotency-Key"]; bodies += body; authorization += headers["Authorization"]
                count++
                if (count == 1) GameHttpResult(401, "{}") else GameHttpResult(200, envelope(fixtureGame("TURN_REVIEW")))
            })
        val command = GameCommand(GameOperation.ANSWER, fixtureGameId,
            buildJsonObject { put("expectedVersion",3);put("attemptId",fixtureAttemptId);put("choiceId",3) })
        api.execute(command)
        assertEquals(2,count)
        assertEquals(keys[0],keys[1]); assertEquals(command.requestId,keys[1])
        assertEquals(bodies[0],bodies[1])
        assertEquals(listOf("Bearer old-token","Bearer new-token"),authorization)
    }

    @Test fun activeGameConflictKeepsServerGameId() = runBlocking {
        val api = RemoteGameRepository("https://example.supabase.co", "public-key", { "user" }, { "new" },
            GameHttpTransport { _, _, _ ->
                GameHttpResult(409, """{"error":{"code":"ACTIVE_GAME_EXISTS","message":"이어하기","details":{"activeGameId":"$fixtureGameId"}}}""")
            })
        try {
            api.execute(GameCommand(GameOperation.CREATE)); fail("예외가 필요합니다.")
        } catch (error: GameApiException) {
            assertEquals("ACTIVE_GAME_EXISTS",error.code)
            assertEquals(fixtureGameId,error.details["activeGameId"]!!.jsonPrimitive.content)
            assertTrue(error.definitive)
        }
    }

    @Test fun malformedSuccessIsUncertainSoMutationCanBeRetried() = runBlocking {
        val api = RemoteGameRepository("https://example.supabase.co", "public-key", { "user" }, { "new" },
            GameHttpTransport { _, _, _ -> GameHttpResult(200, """{"data":{"phase":"QUIZ_OPEN"}}""") })
        try { api.execute(GameCommand(GameOperation.ROLL,fixtureGameId));fail("예외가 필요합니다.") }
        catch(error:GameApiException){assertFalse(error.definitive)}
    }

    @Test fun getDoesNotSendBodyOrMutationKey(): Unit = runBlocking {
        val api = RemoteGameRepository("https://example.supabase.co", "public-key", { "user" }, { "new" },
            GameHttpTransport { _, headers, body ->
                assertNull(body);assertFalse(headers.containsKey("Idempotency-Key"))
                GameHttpResult(200,envelope(fixtureGame()))
            })
        api.execute(GameCommand(GameOperation.GET,fixtureGameId))
    }
}
