package com.tomatoinsight.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.tomatoinsight.data.FarmAccountIsolation
import com.tomatoinsight.data.FarmerProfile
import com.tomatoinsight.data.FirestoreFarmRepository
import com.tomatoinsight.data.UserFarmRepository
import com.tomatoinsight.util.PASSWORD_REQUIREMENTS_HINT
import com.tomatoinsight.util.passwordValidationError
import kotlinx.coroutines.launch

@Composable
fun SignupScreen(
    onSignupSuccess: () -> Unit,
    onNavigateToLogin: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var firstName by remember { mutableStateOf("") }
    var middleName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmVisible by remember { mutableStateOf(false) }

    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val auth = FirebaseAuth.getInstance()

    AuthScreenScaffold(
        headerMessage = "Create your account and simplify your farm monitoring.",
        scrollContent = true
    ) {
        AuthTitleBlock(
            title = "Sign up",
            prompt = "Already Have An Account?",
            linkLabel = "Login",
            onLinkClick = onNavigateToLogin
        )

        AuthPersonField(
            value = firstName,
            onValueChange = {
                firstName = it
                if (error != null) error = null
            },
            placeholder = "First name"
        )
        Spacer(modifier = Modifier.height(12.dp))
        AuthPersonField(
            value = middleName,
            onValueChange = {
                middleName = it
                if (error != null) error = null
            },
            placeholder = "Middle name (optional)"
        )
        Spacer(modifier = Modifier.height(12.dp))
        AuthPersonField(
            value = lastName,
            onValueChange = {
                lastName = it
                if (error != null) error = null
            },
            placeholder = "Last name"
        )
        Spacer(modifier = Modifier.height(12.dp))

        AuthEmailField(value = email, onValueChange = {
            email = it
            if (error != null) error = null
        })

        Spacer(modifier = Modifier.height(12.dp))

        AuthPasswordField(
            value = password,
            onValueChange = {
                password = it
                if (error != null) error = null
            },
            visible = passwordVisible,
            onToggleVisible = { passwordVisible = !passwordVisible }
        )
        Text(
            text = PASSWORD_REQUIREMENTS_HINT,
            fontSize = 12.sp,
            color = Color(0xFF8A8A8E),
            modifier = Modifier.padding(top = 6.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        AuthPasswordField(
            value = confirmPassword,
            onValueChange = {
                confirmPassword = it
                if (error != null) error = null
            },
            placeholder = "Confirm password",
            visible = confirmVisible,
            onToggleVisible = { confirmVisible = !confirmVisible }
        )

        AuthErrorText(error)

        Spacer(modifier = Modifier.height(20.dp))

        AuthPrimaryButton(
            label = "Create Account",
            loading = isLoading,
            onClick = {
                when {
                    firstName.isBlank() -> error = "Please enter your first name"
                    lastName.isBlank() -> error = "Please enter your last name"
                    email.isBlank() -> error = "Please enter your email"
                    password.isBlank() -> error = "Please enter a password"
                    else -> passwordValidationError(password)?.let {
                        error = it
                    } ?: run {
                        if (password != confirmPassword) {
                            error = "Passwords do not match"
                        } else {
                            isLoading = true
                            error = null
                            auth.createUserWithEmailAndPassword(email.trim(), password)
                                .addOnCompleteListener { task ->
                                    if (task.isSuccessful) {
                                        val user = auth.currentUser
                                        val uid = user?.uid.orEmpty()
                                        val fullName = listOf(firstName, middleName, lastName)
                                            .filter { it.isNotBlank() }
                                            .joinToString(" ")
                                        val profileUpdates = UserProfileChangeRequest.Builder()
                                            .setDisplayName(fullName)
                                            .build()

                                        fun finishSignup() {
                                            if (uid.isBlank()) {
                                                isLoading = false
                                                onSignupSuccess()
                                                return
                                            }
                                            // Clear shared farm cache and start this uid empty
                                            // before dashboard hydrate/sync can upload leftovers.
                                            scope.launch {
                                                runCatching {
                                                    FarmAccountIsolation.prepareFreshSignup(
                                                        context = context,
                                                        uid = uid,
                                                        farmRepository = UserFarmRepository(context)
                                                    )
                                                }
                                                runCatching {
                                                    FirestoreFarmRepository().saveProfile(
                                                        uid = uid,
                                                        profile = FarmerProfile(
                                                            firstName = firstName.trim(),
                                                            middleInitial = middleName.trim()
                                                                .uppercase()
                                                                .take(1),
                                                            lastName = lastName.trim(),
                                                            email = email.trim()
                                                        ),
                                                        includePhoto = false
                                                    )
                                                }
                                                isLoading = false
                                                onSignupSuccess()
                                            }
                                        }

                                        if (user != null) {
                                            user.updateProfile(profileUpdates)
                                                .addOnCompleteListener { finishSignup() }
                                        } else {
                                            finishSignup()
                                        }
                                    } else {
                                        isLoading = false
                                        error = task.exception?.localizedMessage ?: "Signup failed"
                                    }
                                }
                        }
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}
