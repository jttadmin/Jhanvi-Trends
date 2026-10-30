@file:OptIn(ExperimentalMaterial3Api::class)

package com.jhanvitrends.shop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jhanvitrends.shop.data.ApiException
import com.jhanvitrends.shop.data.Cart
import kotlinx.coroutines.launch

@Composable
fun CartScreen(ctx: AppContext) {
    var cart by remember { mutableStateOf<Cart?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(ctx.session.token, reload) {
        if (!ctx.session.signedIn) return@LaunchedEffect
        error = null
        try {
            cart = ctx.api.cart().also { ctx.session.cartCount = it.itemCount }
        } catch (e: ApiException) {
            error = e.message
        }
    }

    fun change(block: suspend () -> Cart) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                cart = block().also { ctx.session.cartCount = it.itemCount }
            } catch (e: ApiException) {
                ctx.toast(e.message ?: "Could not update the cart")
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Shopping cart") })
        val c = cart
        when {
            !ctx.session.signedIn -> EmptyState("Sign in to see your cart", action = "Sign in") { ctx.nav.navigate("login?next=cart") }
            error != null && c == null -> ErrorBox(error!!) { reload++ }
            c == null -> Loading()
            c.items.isEmpty() -> EmptyState("Your cart is empty", "Find something you love.", "Start shopping") { ctx.nav.navigate("home") }
            else -> {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(c.items, key = { it.productId }) { line ->
                        SectionCard {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ProductImage(
                                    ctx.api.imageUrl(line.product.imageUrl),
                                    Modifier.size(88.dp).clickable { ctx.nav.navigate("product/${line.productId}") },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(line.product.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(inr(line.lineTotal), fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.padding(vertical = 4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedButton(
                                            onClick = { change { if (line.quantity > 1) ctx.api.setQuantity(line.productId, line.quantity - 1) else ctx.api.removeFromCart(line.productId) } },
                                            contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp),
                                        ) { Text("−") }
                                        Text("${line.quantity}", Modifier.width(36.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                        OutlinedButton(
                                            onClick = { change { ctx.api.setQuantity(line.productId, line.quantity + 1) } },
                                            enabled = line.quantity < 50,
                                            contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp),
                                        ) { Text("+") }
                                        Spacer(Modifier.weight(1f))
                                        IconButton(onClick = { change { ctx.api.removeFromCart(line.productId) } }) { Icon(Icons.Filled.Delete, "Remove") }
                                    }
                                }
                            }
                        }
                    }
                }
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Subtotal (${c.itemCount} items)", color = Muted)
                            Text(inr(c.subtotal), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Button(onClick = { ctx.nav.navigate("checkout") }, shape = RoundedCornerShape(8.dp), modifier = Modifier.height(48.dp)) {
                            Text("Checkout")
                        }
                    }
                }
            }
        }
    }
}
