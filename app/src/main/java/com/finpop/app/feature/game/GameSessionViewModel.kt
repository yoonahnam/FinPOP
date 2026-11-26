package com.finpop.app.feature.game

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.finpop.app.BuildConfig
import com.finpop.app.core.network.SupabaseProvider
import com.finpop.app.data.game.remote.*
import io.github.jan.supabase.auth.auth

/** 로그인 SDK의 세션을 사용하며 Activity 재생성 중에도 요청 키와 상태를 보존합니다. */
class GameSessionViewModel(application: Application) : AndroidViewModel(application) {
    val store = GameSessionStore(
        RemoteGameRepository(
            BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY,
            token = {
                val auth = SupabaseProvider.client.auth
                auth.awaitInitialization()
                auth.currentSessionOrNull()?.accessToken
                    ?: throw GameApiException("AUTH_REQUIRED", "Google 계정으로 다시 로그인해 주세요.", definitive = false)
            },
            refreshToken = {
                val auth = SupabaseProvider.client.auth
                auth.refreshCurrentSession()
                auth.currentSessionOrNull()?.accessToken
                    ?: throw GameApiException("AUTH_REQUIRED", "Google 계정으로 다시 로그인해 주세요.", definitive = false)
            },
        ), viewModelScope, journal = PreferencesGameRecoveryJournal(application),
    )
}
