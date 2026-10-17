package jp.stackchan.localvoicepoc.ui

import android.content.Context
import androidx.core.content.edit

class AppPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "stackchan_ui",
        Context.MODE_PRIVATE,
    )

    fun markOnboardingComplete() {
        preferences.edit { putBoolean("onboarding_complete", true) }
    }

    fun isOnboardingComplete(): Boolean = preferences.getBoolean("onboarding_complete", false)

    fun audioMode(): jp.stackchan.localvoicepoc.audio.ConversationAudioMode = runCatching {
        jp.stackchan.localvoicepoc.audio.ConversationAudioMode.valueOf(preferences.getString("audio_mode", "DOCK")!!)
    }.getOrDefault(jp.stackchan.localvoicepoc.audio.ConversationAudioMode.DOCK)

    fun saveAudioMode(mode: jp.stackchan.localvoicepoc.audio.ConversationAudioMode) {
        preferences.edit { putString("audio_mode", mode.name) }
    }
}
