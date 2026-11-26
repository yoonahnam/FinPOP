package com.finpop.app.data.game.remote

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** 앱 전용 저장소에 계정별로 보관합니다. 인증 토큰·비밀번호·문항은 저장하지 않습니다. */
class PreferencesGameRecoveryJournal(context: Context) : GameRecoveryJournal {
    private val preferences = context.applicationContext.getSharedPreferences("game_recovery_v1", Context.MODE_PRIVATE)
    override suspend fun read(userId: String): GameRecoveryRecord = withContext(Dispatchers.IO) {
        preferences.getString(userId, null)?.let(GameRecoveryCodec::decode) ?: GameRecoveryRecord()
    }
    override suspend fun write(userId: String, record: GameRecoveryRecord): Unit = withContext(Dispatchers.IO) {
        if (!preferences.edit().putString(userId, GameRecoveryCodec.encode(record)).commit())
            throw IOException("게임 복원 정보를 저장하지 못했습니다.")
    }
}
