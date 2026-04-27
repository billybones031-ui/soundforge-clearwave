package com.isl.soundforge.firebase

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

/**
 * Wraps Firebase Auth with clean suspend functions so the rest of the app
 * never touches Firebase directly. All error handling is caller's responsibility —
 * we throw on failure so the ViewModel can decide how to surface the error.
 */
class AuthManager(private val context: Context) {

    private val auth = FirebaseAuth.getInstance()

    val currentUser: FirebaseUser? get() = auth.currentUser
    val isAuthenticated: Boolean get() = currentUser != null

    /** Email + password sign-in */
    suspend fun signInWithEmail(email: String, password: String): FirebaseUser {
        val result = auth.signInWithEmailAndPassword(email, password).await()
        return result.user ?: error("Sign-in returned null user")
    }

    /** Email + password account creation */
    suspend fun createAccountWithEmail(email: String, password: String): FirebaseUser {
        val result = auth.createUserWithEmailAndPassword(email, password).await()
        return result.user ?: error("Account creation returned null user")
    }

    /**
     * Exchanges a Google ID token (from the sign-in Intent result) for a
     * Firebase credential, then signs in.
     */
    suspend fun signInWithGoogle(idToken: String): FirebaseUser {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val result = auth.signInWithCredential(credential).await()
        return result.user ?: error("Google sign-in returned null user")
    }

    fun signOut() {
        auth.signOut()
    }

    fun sendPasswordReset(email: String) {
        auth.sendPasswordResetEmail(email)
    }

    /**
     * Returns the Intent to launch for Google Sign-In.
     * The result should be handled with ActivityResultContracts.StartActivityForResult
     * and the ID token extracted with [GoogleSignIn.getSignedInAccountFromIntent].
     */
    fun googleSignInIntent(): Intent {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(WEB_CLIENT_ID)
            .requestEmail()
            .build()
        return GoogleSignIn.getClient(context, gso).signInIntent
    }

    companion object {
        // Replace with your Firebase project's web client ID from google-services.json
        // Found at: Firebase Console → Project Settings → Your apps → Web client ID
        const val WEB_CLIENT_ID = "YOUR_WEB_CLIENT_ID_HERE"
    }
}
