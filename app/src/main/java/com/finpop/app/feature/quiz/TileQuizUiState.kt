package com.finpop.app.feature.quiz

import com.finpop.app.data.game.remote.ServerChoice

/** 디자인 컴포넌트에 전달할 표시 상태입니다. Question 단계에는 정답을 넣지 않습니다. */
internal data class TileQuizUiState(
    val attemptId: String,
    val question: String,
    val choices: List<ServerChoice>,
    val stage: Int,
    val page: QuizPage,
    val secondsRemaining: Int = 20,
    val hint: String? = null,
    val selectedChoiceId: Int? = null,
    val correctChoiceId: Int? = null,
    val explanation: String? = null,
    val timedOut: Boolean = false,
    val purchaseAvailable: Boolean = false,
    val purchasePrice: Int? = null,
    val settlementText: String? = null,
)
