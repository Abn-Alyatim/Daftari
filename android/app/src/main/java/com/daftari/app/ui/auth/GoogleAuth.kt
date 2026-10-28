package com.daftari.app.ui.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

sealed interface GoogleResult {
    data class Ok(val idToken: String, val email: String, val name: String) : GoogleResult
    data object Cancelled : GoogleResult
    data class Failed(val message: String) : GoogleResult
}

/** «المتابعة باستخدام Google» through Credential Manager (same flow as ALYATIM 1.5.3). */
object GoogleAuth {
    suspend fun signIn(context: Context, webClientId: String): GoogleResult {
        if (webClientId.isBlank()) return GoogleResult.Failed("الدخول بـ Google قيد التجهيز")
        val option = GetSignInWithGoogleOption.Builder(webClientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val cred = CredentialManager.create(context).getCredential(context, request).credential
            if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val g = GoogleIdTokenCredential.createFrom(cred.data)
                GoogleResult.Ok(g.idToken, g.id, g.displayName.orEmpty())
            } else GoogleResult.Failed("ما وصلنا حساب Google")
        } catch (e: GetCredentialCancellationException) {
            GoogleResult.Cancelled
        } catch (e: GetCredentialException) {
            GoogleResult.Failed("تعذّر الدخول بـ Google")
        }
    }
}
