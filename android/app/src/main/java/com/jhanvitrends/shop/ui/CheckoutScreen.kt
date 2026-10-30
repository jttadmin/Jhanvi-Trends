@file:OptIn(ExperimentalMaterial3Api::class)

package com.jhanvitrends.shop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jhanvitrends.shop.data.ApiException
import com.jhanvitrends.shop.data.Cart
import com.jhanvitrends.shop.data.ShippingAddress
import kotlinx.coroutines.launch

@Composable
fun CheckoutScreen(ctx: AppContext) {
    val saved = remember { ctx.session.savedAddress() }
    var fullName by remember { mutableStateOf(saved.fullName) }
    var phone by remember { mutableStateOf(saved.phone) }
    var line1 by remember { mutableStateOf(saved.line1) }
    var city by remember { mutableStateOf(saved.city) }
    var pincode by remember { mutableStateOf(saved.pincode) }
    var state by remember { mutableStateOf(saved.state) }
    var cart by remember { mutableStateOf<Cart?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var placing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (!ctx.requireSignIn("checkout")) return@LaunchedEffect
        try { cart = ctx.api.cart() } catch (e: ApiException) { error = e.message }
    }

    val pinOk = pincode.length == 6 && pincode.all { it.isDigit() }
    val valid = fullName.isNotBlank() && line1.isNotBlank() && city.isNotBlank() && pinOk

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Checkout") },
            navigationIcon = { IconButton(onClick = { ctx.nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "Back") } },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("1. Delivery address", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            OutlinedTextField(fullName, { fullName = it }, label = { Text("Full name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                phone, { phone = it }, label = { Text("Mobile number") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(line1, { line1 = it }, label = { Text("Flat, house no., building, street") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(city, { city = it }, label = { Text("Town/City") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(
                    pincode, { if (it.length <= 6) pincode = it.filter { ch -> ch.isDigit() } }, label = { Text("PIN code") }, singleLine = true,
                    isError = pincode.isNotEmpty() && !pinOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(state, { state = it }, label = { Text("State") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Text("2. Payment", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text("Demo card ending in 4242 (the store declines a small share of payments on purpose).", color = Muted)

            Text("3. Review items", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            val c = cart
            if (c == null) {
                if (error == null) Loading()
            } else {
                c.items.forEach { Text("${it.quantity} × ${it.product.name} — ${inr(it.lineTotal)}") }
                Text("Order total: ${inr(c.subtotal)}", fontWeight = FontWeight.SemiBold, fontSize = 19.sp, modifier = Modifier.padding(top = 6.dp))
            }
            if (error != null) Text(error!!, color = Deal)

            Button(
                onClick = {
                    val address = ShippingAddress(fullName.trim(), phone.trim(), line1.trim(), city.trim(), pincode, state.trim())
                    placing = true
                    error = null
                    scope.launch {
                        try {
                            ctx.session.saveAddress(address)
                            val order = ctx.api.checkout(address)
                            ctx.session.cartCount = 0
                            ctx.nav.navigate("order/${order.id}?placed=true") { popUpTo("cart") { inclusive = true } }
                        } catch (e: ApiException) {
                            error = if (e.status == 402) "Your payment was declined. Please try again." else e.message
                        } finally {
                            placing = false
                        }
                    }
                },
                enabled = valid && !placing && (cart?.items?.isNotEmpty() == true),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(8.dp),
            ) { Text(if (placing) "Placing order…" else "Place your order") }
        }
    }
}
