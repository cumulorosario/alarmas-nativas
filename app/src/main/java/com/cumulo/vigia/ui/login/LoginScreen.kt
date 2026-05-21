package com.cumulo.vigia.ui.login

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.cumulo.vigia.ui.VigiaViewModel
import com.cumulo.vigia.ui.theme.*

@Composable
fun LoginScreen(viewModel: VigiaViewModel) {
    val state by viewModel.loginState.collectAsState()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var passwordVisible by remember { mutableStateOf(false) }

    val canUseBiometrics = remember {
        val bm = BiometricManager.from(context)
        bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
    }

    val hasSavedCredentials = state.username.isNotEmpty() && state.rememberMe

    fun launchBiometric() {
        if (context !is FragmentActivity) return
        val executor = ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt(context, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    viewModel.login()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Silently fail - user can use password instead
                }
            })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Vigia Industrial")
            .setSubtitle("Verificá tu identidad para continuar")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ZincBg)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Spacer(Modifier.height(32.dp))

            // Logo
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(RedPrimary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(Modifier.height(20.dp))

            Text(
                "VIGIA INDUSTRIAL",
                color = ZincText,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp
            )
            Text(
                "Monitoreo Industrial",
                color = ZincMuted,
                fontSize = 14.sp
            )

            Spacer(Modifier.height(40.dp))

            // Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = ZincSurface,
                border = BorderStroke(1.dp, ZincBorder)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Error banner
                    AnimatedVisibility(
                        visible = state.error != null,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = RedPrimary.copy(alpha = 0.1f),
                            border = BorderStroke(1.dp, RedPrimary.copy(alpha = 0.3f))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Warning, null, tint = RedLight, modifier = Modifier.size(16.dp))
                                Text(state.error ?: "", color = RedLight, fontSize = 13.sp)
                            }
                        }
                    }

                    // Username
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("USUARIO", color = ZincMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        OutlinedTextField(
                            value = state.username,
                            onValueChange = viewModel::onUsernameChange,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("ej: tenant@thingsboard.org", color = ZincMuted, fontSize = 14.sp) },
                            leadingIcon = { Icon(Icons.Default.Person, null, tint = ZincMuted) },
                            shape = RoundedCornerShape(16.dp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Down) }
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = ZincText,
                                unfocusedTextColor = ZincText,
                                focusedBorderColor = RedPrimary,
                                unfocusedBorderColor = ZincBorder,
                                cursorColor = RedPrimary,
                                focusedContainerColor = ZincCard,
                                unfocusedContainerColor = ZincCard
                            )
                        )
                    }

                    // Password
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("CONTRASEÑA", color = ZincMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        OutlinedTextField(
                            value = state.password,
                            onValueChange = viewModel::onPasswordChange,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("••••••••", color = ZincMuted) },
                            leadingIcon = { Icon(Icons.Default.Lock, null, tint = ZincMuted) },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        null, tint = ZincMuted
                                    )
                                }
                            },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            shape = RoundedCornerShape(16.dp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { viewModel.login() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = ZincText,
                                unfocusedTextColor = ZincText,
                                focusedBorderColor = RedPrimary,
                                unfocusedBorderColor = ZincBorder,
                                cursorColor = RedPrimary,
                                focusedContainerColor = ZincCard,
                                unfocusedContainerColor = ZincCard
                            )
                        )
                    }

                    // Remember me
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { viewModel.onRememberMeChange(!state.rememberMe) }
                    ) {
                        Checkbox(
                            checked = state.rememberMe,
                            onCheckedChange = viewModel::onRememberMeChange,
                            colors = CheckboxDefaults.colors(
                                checkedColor = RedPrimary,
                                uncheckedColor = ZincBorder
                            )
                        )
                        Text("Recordar credenciales", color = ZincTextMuted, fontSize = 14.sp)
                    }

                    Spacer(Modifier.height(4.dp))

                    // Login button
                    Button(
                        onClick = { viewModel.login() },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        enabled = !state.isLoading,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RedPrimary,
                            disabledContainerColor = RedDark
                        )
                    ) {
                        if (state.isLoading) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Text("ENTRAR", fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontSize = 14.sp)
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Default.ChevronRight, null)
                        }
                    }

                    // Biometric button
                    if (canUseBiometrics && hasSavedCredentials) {
                        OutlinedButton(
                            onClick = { launchBiometric() },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, ZincBorder),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ZincText)
                        ) {
                            Icon(Icons.Default.Fingerprint, null, tint = RedLight, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("DESBLOQUEO BIOMÉTRICO", fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            Text("www.cumuloingenieria.com.ar", color = ZincMuted, fontSize = 11.sp, textAlign = TextAlign.Center)
            Text("ventas@cumuloingenieria.com.ar", color = ZincMuted, fontSize = 11.sp, textAlign = TextAlign.Center)

            Spacer(Modifier.height(24.dp))
        }
    }
}
