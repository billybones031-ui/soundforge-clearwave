package com.isl.soundforge.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.isl.soundforge.ui.EngineViewModel
import com.isl.soundforge.ui.theme.IslColors
import com.isl.soundforge.ui.theme.SoundForgeTheme

@Composable
fun AuthScreen(
    vm: EngineViewModel,
    onAuthenticated: () -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var isSignUp by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val colors = SoundForgeTheme.colors

    val googleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        GoogleSignIn.getSignedInAccountFromIntent(result.data)
            .addOnSuccessListener { account ->
                account.idToken?.let { vm.signInWithGoogle(it) }
            }
    }

    LaunchedEffect(state.isAuthenticated) {
        if (state.isAuthenticated) onAuthenticated()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Brand header
        Text(
            text = "SOUNDFORGE",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = colors.cyan,
            letterSpacing = 4.sp
        )
        Text(
            text = "CLEARWAVE",
            fontSize = 22.sp,
            fontWeight = FontWeight.Light,
            color = colors.magenta,
            letterSpacing = 6.sp
        )
        Text(
            text = "AI Audio Cleanup for Creators",
            fontSize = 12.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 48.dp)
        )

        val fieldColors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor    = colors.cyan,
            unfocusedBorderColor  = colors.cyanDim,
            focusedTextColor      = colors.textPrimary,
            unfocusedTextColor    = colors.textPrimary,
            cursorColor           = colors.cyan,
            focusedLabelColor     = colors.cyan,
            unfocusedLabelColor   = colors.textSecondary
        )

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) }
            ),
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    if (isSignUp) vm.createAccount(email, password)
                    else vm.signInWithEmail(email, password)
                }
            ),
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                focusManager.clearFocus()
                if (isSignUp) vm.createAccount(email, password)
                else vm.signInWithEmail(email, password)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.cyan)
        ) {
            Text(
                text = if (isSignUp) "CREATE ACCOUNT" else "SIGN IN",
                color = IslColors.Void,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
        }

        Spacer(Modifier.height(8.dp))

        TextButton(onClick = { isSignUp = !isSignUp }) {
            Text(
                text = if (isSignUp) "Already have an account? Sign In"
                       else "New here? Create Account",
                color = colors.textSecondary,
                fontSize = 13.sp
            )
        }

        Spacer(Modifier.height(24.dp))

        HorizontalDivider(color = colors.cyanDim, thickness = 0.5.dp)

        Spacer(Modifier.height(24.dp))

        OutlinedButton(
            onClick = { googleLauncher.launch(vm.googleSignInIntent()) },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(4.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.cyanDim)
        ) {
            Text(
                text = "Continue with Google",
                color = colors.textPrimary,
                fontWeight = FontWeight.Medium
            )
        }

        AnimatedVisibility(state.errorMessage != null) {
            Text(
                text = state.errorMessage ?: "",
                color = IslColors.Error,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}
