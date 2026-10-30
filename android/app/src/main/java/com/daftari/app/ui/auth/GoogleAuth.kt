package com.daftari.app.ui.auth

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialOption
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

sealed interface GoogleResult {
    data class Ok(val idToken: String, val email: String, val name: String) : GoogleResult
    data object Cancelled : GoogleResult
    data class Failed(val message: String, val kind: GoogleErrors.Kind = GoogleErrors.Kind.UNKNOWN) : GoogleResult
}

/**
 * «المتابعة باستخدام Google» through Credential Manager. [webClientId] is the OAuth client of type WEB in his Cloud project
 * (serverClientId — Google signs the ID token for it); the Android client (package + SHA-1) is what lets this app ask at all.
 * Order: an account that already approved دفتري (silent when there is one) → any Google account on the phone
 * (setFilterByAuthorizedAccounts(false), first sign-in) → the full «Sign in with Google» sheet, which can add an account.
 */
object GoogleAuth {
    fun options(webClientId: String): List<CredentialOption> = listOf(
        GetGoogleIdOption.Builder().setServerClientId(webClientId).setFilterByAuthorizedAccounts(true).setAutoSelectEnabled(true).build(),
        GetGoogleIdOption.Builder().setServerClientId(webClientId).setFilterByAuthorizedAccounts(false).build(),
        GetSignInWithGoogleOption.Builder(webClientId).build(),
    )

    suspend fun signIn(context: Context, webClientId: String, what: String = "تسجيل الدخول بـ Google"): GoogleResult {
        if (webClientId.isBlank()) return GoogleResult.Failed("الدخول بـ Google غير مُعَدّ في هذه النسخة", GoogleErrors.Kind.DEVELOPER)
        val cm = CredentialManager.create(context)
        var last: GetCredentialException? = null
        for (option in options(webClientId)) {
            try {
                return parse(cm.getCredential(context, GetCredentialRequest.Builder().addCredentialOption(option).build()).credential, what)
            } catch (e: NoCredentialException) {
                last = e
                // «10: Developer console is not set up correctly» rides a NoCredentialException too — trying further options will not help
                if (GoogleErrors.fromCredential(e.javaClass.simpleName, e.message) == GoogleErrors.Kind.DEVELOPER) break
            } catch (e: GetCredentialException) {
                last = e; break
            }
        }
        val e = last ?: return GoogleResult.Failed(GoogleErrors.text(GoogleErrors.Kind.UNKNOWN, what)!!)
        val kind = GoogleErrors.fromCredential(e.javaClass.simpleName, e.message)
        val text = GoogleErrors.text(kind, what, e.javaClass.simpleName.removePrefix("GetCredential").removeSuffix("Exception")) ?: return GoogleResult.Cancelled
        return GoogleResult.Failed(text, kind)
    }

    private fun parse(cred: Credential, what: String): GoogleResult =
        if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val g = GoogleIdTokenCredential.createFrom(cred.data)
            GoogleResult.Ok(g.idToken, g.id, g.displayName.orEmpty())
        } else GoogleResult.Failed(GoogleErrors.text(GoogleErrors.Kind.UNKNOWN, what, "نوع اعتماد غير متوقع")!!)

    /** Forgets the chosen account so the next sign-in shows the account chooser again. */
    suspend fun signOut(context: Context) {
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }
}
