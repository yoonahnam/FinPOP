package com.finpop.app.data.game.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class GameCommand(
    val operation: GameOperation,
    val gameId: String? = null,
    val body: JsonObject = buildJsonObject {},
    val requestId: String = UUID.randomUUID().toString(),
    val finishAfterPurchase: Boolean = false,
)

class GameApiException(
    val code: String, message: String, val details: JsonObject = buildJsonObject {},
    val definitive: Boolean = true,
) : IllegalStateException(message)

data class GameHttpResult(val status: Int, val body: String)
fun interface GameHttpTransport {
    suspend fun request(url: String, headers: Map<String, String>, body: String?): GameHttpResult
}

object UrlConnectionGameTransport : GameHttpTransport {
    override suspend fun request(url: String, headers: Map<String, String>, body: String?) =
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = if (body == null) "GET" else "POST"
                connection.connectTimeout = 10000
                connection.readTimeout = 25000
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                if (body != null) {
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                GameHttpResult(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
            } finally { connection.disconnect() }
        }
}

/** 토큰·비밀키를 저장/출력하지 않습니다. 재전송에도 명령의 본문과 요청 키를 유지합니다. */
fun interface GameGateway { suspend fun execute(command: GameCommand): ServerGameReply }

class RemoteGameRepository(
    private val projectUrl: String,
    private val publicKey: String,
    private val token: suspend () -> String,
    private val refreshToken: suspend () -> String,
    private val transport: GameHttpTransport = UrlConnectionGameTransport,
) : GameGateway {
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun execute(command: GameCommand): ServerGameReply {
        if (!projectUrl.startsWith("https://") || publicKey.isBlank() || publicKey.startsWith("sb_secret_"))
            throw GameApiException("CONFIG_REQUIRED", "프로젝트 연결 설정을 확인해 주세요.")
        val base = projectUrl.trimEnd('/') + "/functions/v1/finpop-api/v1/games"
        val endpoint = if (command.operation == GameOperation.CREATE) base
            else base + "/" + UUID.fromString(requireNotNull(command.gameId)) + command.operation.suffix
        val body = if (command.operation == GameOperation.GET) null else command.body.toString()
        suspend fun call(jwt: String): GameHttpResult {
            if (jwt.isBlank()) throw GameApiException("AUTH_REQUIRED", "다시 로그인해 주세요.", definitive = false)
            val headers = buildMap {
                put("apikey", publicKey)
                put("Authorization", "Bearer " + jwt)
                if (body != null) {
                    put("Content-Type", "application/json")
                    put("Idempotency-Key", command.requestId)
                }
            }
            return transport.request(endpoint, headers, body)
        }
        var response = call(token())
        if (response.status == 401) {
            // 갱신 이후에도 같은 요청을 보내 서버의 중복 처리 방지를 유지합니다.
            response = call(refreshToken())
        }
        val envelope = try { json.parseToJsonElement(response.body).jsonObject }
        catch (_: Exception) {
            throw GameApiException("INVALID_SERVER_RESPONSE", "서버 응답을 확인하지 못했습니다. 같은 요청으로 재시도해 주세요.", definitive = false)
        }
        if (response.status !in 200..299) {
            val error = envelope["error"] as? JsonObject ?: envelope
            val code = error["code"]?.jsonPrimitive?.contentOrNull ?: "GAME_SERVICE_UNAVAILABLE"
            val message = if (response.status == 401) "인증 세션을 확인해 주세요. 필요하면 다시 로그인하세요."
                else error["message"]?.jsonPrimitive?.contentOrNull ?: "게임 요청을 처리하지 못했습니다."
            val uncertain = response.status == 401 || code in setOf(
                "GAME_SERVICE_UNAVAILABLE", "AUTH_UNAVAILABLE", "INVALID_SERVER_RESPONSE")
            throw GameApiException(code, message, error["details"] as? JsonObject ?: buildJsonObject {}, !uncertain)
        }
        try {
            val data = envelope.getValue("data").jsonObject
            val snapshot = data["game"] as? JsonObject ?: data
            return ServerGameReply(
                game = json.decodeFromJsonElement<ServerGame>(snapshot),
                diceValue = data["diceValue"]?.jsonPrimitive?.intOrNull,
                movementPath = data["movementPath"]?.let { json.decodeFromJsonElement<List<Int>>(it) } ?: emptyList(),
                purchaseEvent = (data["purchaseEvent"] as? JsonObject)?.let { json.decodeFromJsonElement<ServerPurchaseEvent>(it) },
                serverTime = (envelope["meta"] as? JsonObject)?.get("serverTime")?.jsonPrimitive?.contentOrNull,
            )
        } catch (_: Exception) {
            throw GameApiException("INVALID_SERVER_RESPONSE", "게임 상태를 확인하지 못했습니다. 같은 요청으로 재시도해 주세요.", definitive = false)
        }
    }
}
