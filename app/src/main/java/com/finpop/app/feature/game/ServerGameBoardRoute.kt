package com.finpop.app.feature.game

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.finpop.app.feature.quiz.*
import kotlinx.coroutines.delay

/** 팀원이 만든 보드와 티켓 팝업에 서버 상태·콜백만 연결합니다. */
@Composable
fun ServerGameBoardRoute(store: GameSessionStore, onExit: () -> Unit, onMessage: (String) -> Unit) {
    val ui by store.state.collectAsState()
    val game = ui.game
    var collapsed by remember(game?.pendingQuiz?.attemptId ?: game?.lastQuizResult?.attemptId) { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var confirmAbandon by remember { mutableStateOf(false) }
    var hideFinished by remember(game?.gameId) { mutableStateOf(false) }
    val quiz = game?.pendingQuiz
    var seconds by remember(quiz?.attemptId) { mutableIntStateOf(20) }
    var timeoutSent by remember(quiz?.attemptId) { mutableStateOf(false) }
    LaunchedEffect(quiz?.attemptId, quiz?.deadlineAt, game?.status, ui.clockOffsetMillis, ui.busy, ui.error) {
        if (ui.error != null) timeoutSent = false
        if (quiz != null && game.currentPlayerId == "P1" && game.status == "ACTIVE") {
            while (true) {
                seconds = quizSecondsRemaining(quiz.deadlineAt, ui.clockOffsetMillis, System.currentTimeMillis())
                if (seconds <= 0) {
                    if (!ui.busy && ui.error == null && ui.pending == null && !timeoutSent) {
                        timeoutSent = true
                        store.timeout()
                    }
                    break
                }
                delay(200)
            }
        }
    }
    LaunchedEffect(game?.gameId, game?.stateVersion, ui.busy, ui.error,
        ui.acknowledgedAiResult, ui.finishAfterPurchase) {
        store.advance()
    }
    BackHandler { showMenu = true }
    if (game == null) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                if (ui.busy) CircularProgressIndicator()
                Text("AI 대결을 불러오고 있습니다.")
                TextButton(onClick = onExit) { Text("로그인 화면으로") }
            }
        }
    } else {
        val result = game.lastQuizResult
        val waitingForMovement = ui.rolling || ui.animatedPosition != null || game.status != "ACTIVE"
        val userQuestion = quiz?.takeIf { game.currentPlayerId == "P1" && game.phase == "QUIZ_OPEN" && !waitingForMovement }
        val userResult = result?.takeIf { it.playerId == "P1" && game.currentPlayerId == "P1" &&
            game.phase in setOf("PURCHASE_PENDING", "TURN_REVIEW") && !waitingForMovement }
        val aiResult = result?.takeIf { it.playerId == "P2" &&
            game.phase in setOf("PURCHASE_PENDING", "TURN_REVIEW") &&
            it.attemptId != ui.acknowledgedAiResult && !waitingForMovement }
        val tile = when {
            userQuestion != null -> quizTile(toBoardTileId(userQuestion.tileId))
            userResult != null -> quizTile(toBoardTileId(game.player("P1").position))
            else -> null
        }
        val aiTile = if (aiResult != null) quizTile(toBoardTileId(game.player("P2").position)) else null
        val card = when {
            userQuestion != null -> TileQuizUiState(userQuestion.attemptId, userQuestion.question,
                userQuestion.choices, userQuestion.stage, QuizPage.Question, secondsRemaining = seconds,
                hint = userQuestion.hint)
            userResult != null -> TileQuizUiState(userResult.attemptId, userResult.question,
                userResult.choices, game.quizStage,
                if (userResult.resolution == "CORRECT") QuizPage.Correct else QuizPage.Incorrect,
                selectedChoiceId = userResult.selectedChoiceId, correctChoiceId = userResult.correctChoiceId,
                explanation = userResult.explanation, timedOut = userResult.resolution == "TIMEOUT",
                purchaseAvailable = game.phase == "PURCHASE_PENDING",
                purchasePrice = game.pendingPurchase?.price,
                settlementText = "서버 판정과 정산 결과를 잔액에 반영했습니다. 확인 후 차례를 마칩니다.")
            else -> null
        }
        val hasPopup = card != null || aiResult != null || ui.error != null || showMenu
        MainGameBoardScreen(
            state = game.toFinalBoardState(ui).copy(canRollDice = game.toFinalBoardState(ui).canRollDice && !hasPopup),
            onMenuClick = { showMenu = true },
            onTabSelected = { if (!hasPopup) onMessage("해당 화면은 연결 준비 중입니다.") },
            onRollDice = { store.roll() },
            onTileClick = { if (!hasPopup) onMessage("칸 상세 화면은 연결 준비 중입니다.") },
            onPurchaseAnimationFinished = store::purchaseAnimationFinished,
            blurBoard = (card != null || aiResult != null) && !collapsed,
            overlay = {
                when {
                    card != null && tile != null -> TileQuizPopup(
                        tile = tile, card = card, gold = game.player("P1").gameGold,
                        canAcquire = game.pendingPurchase != null, collapsed = collapsed,
                        onToggleCollapsed = { collapsed = !collapsed }, onSubmit = store::answer,
                        onAcquire = store::buy, onFinish = store::finishUserResult, busy = ui.busy || ui.pending != null || ui.error != null)
                    aiResult != null && aiTile != null -> AiQuizResultPopup(aiTile,
                        aiResult.resolution == "CORRECT", store::acknowledgeAiResult,
                        busy = ui.busy || ui.error != null, timedOut = aiResult.resolution == "TIMEOUT")
                }
                if (ui.busy && card == null && aiResult == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            },
        )
        if (game.phase == "INITIATIVE_PENDING" && ui.error == null && game.status == "ACTIVE") {
            AlertDialog(onDismissRequest = {}, title = { Text("AI 대결 준비") },
                text = { Text("양쪽 700G·7턴으로 시작합니다. 주사위로 선공을 결정하세요.") },
                confirmButton = { TextButton(enabled = !ui.busy, onClick = store::initiative) { Text("선공 결정") } })
        }
        if (game.phase == "TURN_READY" && game.currentPlayerId == "P1" &&
            game.player("P1").islandWaitTurns > 0 && ui.error == null && game.status == "ACTIVE") {
            AlertDialog(onDismissRequest = {}, title = { Text("무인도 대기") },
                text = { Text("이번 차례는 이동하지 않고 1턴을 소비합니다.") },
                confirmButton = { TextButton(enabled = !ui.busy, onClick = store::skipIsland) { Text("확인") } })
        }
        if (game.phase == "TURN_REVIEW" && game.currentPlayerId == "P1" && result == null && ui.error == null && game.status == "ACTIVE") {
            AlertDialog(onDismissRequest = {}, title = { Text("이동 완료") },
                text = { Text("도착 칸 처리를 마쳤습니다. 차례를 종료하세요.") },
                confirmButton = { TextButton(enabled = !ui.busy, onClick = store::finishUserResult) { Text("차례 종료") } })
        }
        if (game.phase == "FINISHED" && !hideFinished) {
            AlertDialog(onDismissRequest = { hideFinished = true }, title = { Text("대결 종료") },
                text = { Text("양쪽 턴이 모두 끝났습니다. 승패 계산·계정 보상 화면은 후속 연결 예정입니다.") },
                confirmButton = { TextButton(onClick = { hideFinished = true; store.startNewGame() }) { Text("새 대결") } },
                dismissButton = { TextButton(onClick = { hideFinished = true }) { Text("보드 확인") } })
        }
    }
    val canControl = !ui.busy && ui.pending == null && ui.error == null
    if (game?.status == "PAUSED" && !confirmAbandon && ui.error == null) {
        AlertDialog(onDismissRequest = {}, title = { Text("대결 일시정지") },
            text = { Column {
                Text("퀴즈 남은 시간과 진행 상태를 저장했습니다. 재개하면 이어서 진행합니다.")
                TextButton(enabled = canControl, onClick = { confirmAbandon = true }) { Text("대결 포기") }
                TextButton(enabled = canControl, onClick = onExit) { Text("로그인 화면으로") }
            } },
            confirmButton = { TextButton(enabled = canControl, onClick = { showMenu = false; store.resumeGame() }) { Text("대결 재개") } })
    }
    if (game?.status == "ABANDONED" && ui.error == null) {
        AlertDialog(onDismissRequest = {}, title = { Text("대결을 포기했습니다") },
            text = { Text("이 대결의 보상과 완료 횟수는 지급되지 않습니다.") },
            confirmButton = { TextButton(enabled = canControl, onClick = store::startNewGame) { Text("새 대결") } },
            dismissButton = { TextButton(enabled = canControl, onClick = onExit) { Text("로그인 화면으로") } })
    }
    if (showMenu && game?.status != "PAUSED" && !confirmAbandon) {
        AlertDialog(onDismissRequest = { showMenu = false }, title = { Text("대결 메뉴") },
            text = { Column {
                Text("일시정지하면 퀴즈 남은 시간을 보존합니다. 포기하면 보상 없이 대결이 종료됩니다.")
                TextButton(enabled = canControl && game?.status == "ACTIVE", onClick = {
                    showMenu = false; store.pauseGame()
                }) { Text("대결 일시정지") }
                TextButton(enabled = canControl && game?.status in setOf("ACTIVE", "PAUSED"), onClick = {
                    confirmAbandon = true
                }) { Text("대결 포기") }
            } },
            confirmButton = { TextButton(enabled = canControl, onClick = {
                showMenu = false; store.refresh()
            }) { Text("상태 새로고침") } },
            dismissButton = { TextButton(onClick = { showMenu = false }) { Text("계속하기") } })
    }
    if (confirmAbandon) {
        AlertDialog(onDismissRequest = { confirmAbandon = false }, title = { Text("대결을 포기할까요?") },
            text = { Text("포기하면 다시 이어할 수 없으며 보상과 완료 횟수도 지급되지 않습니다.") },
            confirmButton = { TextButton(enabled = canControl, onClick = {
                confirmAbandon = false; showMenu = false; store.abandonGame()
            }) { Text("포기하기") } },
            dismissButton = { TextButton(onClick = { confirmAbandon = false }) { Text("취소") } })
    }
    ui.error?.let { message ->
        AlertDialog(onDismissRequest = {}, title = { Text("요청을 확인해 주세요") }, text = { Text(message) },
            confirmButton = { TextButton(enabled = !ui.busy, onClick = store::retry) {
                Text(if (ui.pending != null) "같은 요청 재시도" else "상태 확인 후 재시도")
            } },
            dismissButton = { TextButton(onClick = onExit) { Text("로그인 화면으로") } })
    }
}
