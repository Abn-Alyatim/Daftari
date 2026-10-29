package com.daftari.app.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Fingerprint/face confirmation. The password fallback is the app's own screen (see LockScreen / ConfirmDialog). */
object Biometric {
    private const val AUTH = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK

    /** READY / NOT_ENROLLED / NO_HARDWARE for the log's double lock. */
    fun state(activity: FragmentActivity): com.daftari.app.ui.log.Bio = when (BiometricManager.from(activity).canAuthenticate(AUTH)) {
        BiometricManager.BIOMETRIC_SUCCESS -> com.daftari.app.ui.log.Bio.READY
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> com.daftari.app.ui.log.Bio.NOT_ENROLLED
        else -> com.daftari.app.ui.log.Bio.NO_HARDWARE
    }

    /** Fingerprint only (no password fallback button): used as the first half of the log's double lock. */
    fun promptStrict(activity: FragmentActivity, title: String, onResult: (Boolean) -> Unit) {
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle("الخطوة ١ من ٢: البصمة").setNegativeButtonText("إلغاء").setAllowedAuthenticators(AUTH).build())
    }

    fun available(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(activity: FragmentActivity, title: String, subtitle: String, onResult: (Boolean) -> Unit) {
        if (!available(activity)) { onResult(false); return }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title).setSubtitle(subtitle).setNegativeButtonText("كلمة السر").setAllowedAuthenticators(AUTH).build()
        prompt.authenticate(info)
    }
}
