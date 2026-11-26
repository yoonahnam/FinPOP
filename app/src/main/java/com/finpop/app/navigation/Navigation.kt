package com.finpop.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.finpop.app.data.auth.AuthRepository
import com.finpop.app.data.user.UserRepository
import com.finpop.app.feature.auth.LoginAction
import com.finpop.app.feature.auth.LoginScreen
import com.finpop.app.feature.game.GameSessionStore
import com.finpop.app.feature.game.ServerGameBoardRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 최종 보드는 실제 로그인 세션과 서버 확정 상태를 사용합니다. */
@Composable
fun Navigation(onMessage: (String) -> Unit, gameStore: GameSessionStore) {
    var showGameBoard by rememberSaveable { mutableStateOf(false) }
    var loadingAction by remember { mutableStateOf<LoginAction?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    suspend fun openMyGame() {
        val me = UserRepository.getMyProfile()
        gameStore.bindUser(me.id)
        gameStore.start(me.selectedCharacterId ?: "CHEESE")
        showGameBoard = true
    }
    BackHandler(enabled = showGameBoard) { showGameBoard = false }
    if (showGameBoard) {
        ServerGameBoardRoute(gameStore, onExit = { showGameBoard = false }, onMessage = onMessage)
    } else {
        LoginScreen(
            onGoogleLogin = {
                if (loadingAction == null) {
                    loadingAction = LoginAction.Google
                    errorMessage = null
                    scope.launch {
                        var authenticated = false
                        try {
                            AuthRepository.signInWithGoogle(context)
                            authenticated = true
                            openMyGame()
                            onMessage("환영합니다!")
                        } catch (_: GetCredentialCancellationException) {
                            // Google 재인증 실패도 취소 예외로 전달될 수 있어 재시도 방법을 안내합니다.
                            errorMessage = "Google 인증이 완료되지 않았습니다. 취소하지 않았다면 에뮬레이터 설정에서 Google 계정을 다시 인증한 뒤 시도해 주세요."
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            errorMessage = if (authenticated)
                                "Google 인증은 완료됐지만 사용자 정보를 불러오지 못했습니다. 연결 상태와 계정 DB 설정을 확인해 주세요."
                            else "Google 인증을 완료하지 못했습니다. 연결 상태와 로그인 설정을 확인해 주세요."
                        } finally { loadingAction = null }
                    }
                }
            },
            onGuestLogin = { errorMessage = "실제 AI 대결은 Google 계정으로 로그인한 뒤 이용할 수 있습니다." },
            onTermsClick = { onMessage("이용약관 화면 연결 예정") },
            onPrivacyClick = { onMessage("개인정보 처리방침 화면 연결 예정") },
            loadingAction = loadingAction,
            errorMessage = errorMessage,
        )
    }
}
