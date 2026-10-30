@file:OptIn(ExperimentalMaterial3Api::class)

package com.jhanvitrends.shop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jhanvitrends.shop.BuildConfig
import com.jhanvitrends.shop.data.ApiException
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(ctx: AppContext, next: String) {
    var registering by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(if (registering) "Create account" else "Sign in") })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (registering) {
                OutlinedTextField(name, { name = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(
                email, { email = it }, label = { Text("Email") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                password, { password = it }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = { if (registering) Text("At least 6 characters") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (error != null) Text(error!!, color = Deal)
            Button(
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            if (registering) ctx.api.register(name, email, password) else ctx.api.login(email, password)
                            ctx.nav.navigate(next) { popUpTo("login?next={next}") { inclusive = true } }
                        } catch (e: ApiException) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && email.isNotBlank() && password.length >= (if (registering) 6 else 1) && (!registering || name.isNotBlank()),
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(8.dp),
            ) { Text(if (busy) "Please wait…" else if (registering) "Create your account" else "Sign in") }
            TextButton(onClick = { registering = !registering; error = null }, modifier = Modifier.fillMaxWidth()) {
                Text(if (registering) "Already have an account? Sign in" else "New to Jhanvi Trends? Create an account")
            }
        }
    }
}

@Composable
fun AccountScreen(ctx: AppContext) {
    val user = ctx.session.user
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Account") })
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (user == null) {
                EmptyState("You're not signed in", "Sign in to shop, see your cart and track orders.", "Sign in") {
                    ctx.nav.navigate("login?next=account")
                }
            } else {
                SectionCard {
                    Text(user.name, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                    Text(user.email, color = Muted)
                }
                SectionCard(onClick = { ctx.nav.navigate("orders") }) { Text("Your orders") }
                if (user.role == "admin") {
                    SectionCard(onClick = { uri.openUri(BuildConfig.BASE_URL + "/#/admin") }) {
                        Text("Store admin", fontWeight = FontWeight.SemiBold)
                        Text("Opens the admin panel in your browser", color = Muted, fontSize = 13.sp)
                    }
                }
                OutlinedButton(onClick = { ctx.session.signOut(); ctx.toast("Signed out") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign out")
                }
            }
            Text(
                "Jhanvi Trends ${BuildConfig.VERSION_NAME} · © 2026 Bhumaji Systems",
                color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 24.dp),
            )
        }
    }
}
