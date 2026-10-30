@file:OptIn(ExperimentalMaterial3Api::class)

package com.jhanvitrends.shop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jhanvitrends.shop.data.ApiException
import com.jhanvitrends.shop.data.Order
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale("en", "IN"))

private fun formatDate(iso: String): String = try {
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(dateFormat)
} catch (e: Exception) {
    iso.take(10)
}

@Composable
private fun StatusText(status: String) {
    val (label, color) = when (status) {
        "CONFIRMED" -> "Confirmed — arriving in 2 days" to Ok
        "PAYMENT_FAILED" -> "Payment failed" to Deal
        else -> status.replace('_', ' ').lowercase() to Muted
    }
    Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
}

@Composable
fun OrdersScreen(ctx: AppContext) {
    var orders by remember { mutableStateOf<List<Order>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(ctx.session.token, reload) {
        if (!ctx.session.signedIn) return@LaunchedEffect
        error = null
        try { orders = ctx.api.orders() } catch (e: ApiException) { error = e.message }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Your orders") })
        val list = orders
        when {
            !ctx.session.signedIn -> EmptyState("Sign in to see your orders", action = "Sign in") { ctx.nav.navigate("login?next=orders") }
            error != null && list == null -> ErrorBox(error!!) { reload++ }
            list == null -> Loading()
            list.isEmpty() -> EmptyState("No orders yet", action = "Start shopping") { ctx.nav.navigate("home") }
            else -> LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it.id }) { o ->
                    SectionCard(onClick = { ctx.nav.navigate("order/${o.id}") }) {
                        Row {
                            Column(Modifier.weight(1f)) {
                                Text("Order ${o.shortId}", fontWeight = FontWeight.SemiBold)
                                Text(formatDate(o.createdAt), color = Muted, fontSize = 12.sp)
                            }
                            Text(inr(o.totalAmount), fontWeight = FontWeight.SemiBold)
                        }
                        StatusText(o.status)
                        Text(o.items.joinToString { "${it.quantity} × ${it.name}" }, color = Muted, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
fun OrderScreen(ctx: AppContext, id: String, placed: Boolean) {
    var order by remember { mutableStateOf<Order?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(id) {
        try { order = ctx.api.order(id) } catch (e: ApiException) { error = e.message }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Order details") },
            navigationIcon = { IconButton(onClick = { ctx.nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "Back") } },
        )
        val o = order
        when {
            error != null -> ErrorBox(error!!)
            o == null -> Loading()
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (placed) {
                    Column(
                        Modifier.fillMaxWidth().background(Color(0xFFF0FDF9), RoundedCornerShape(8.dp))
                            .border(2.dp, Ok, RoundedCornerShape(8.dp)).padding(16.dp),
                    ) {
                        Text("✓ Order placed, thank you!", color = Ok, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        Text("A confirmation will be sent to ${ctx.session.user?.email.orEmpty()}.")
                    }
                }
                SectionCard {
                    Text("Order ${o.shortId}", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Text("Placed ${formatDate(o.createdAt)}", color = Muted)
                    StatusText(o.status)
                }
                SectionCard {
                    Text("Items", fontWeight = FontWeight.SemiBold)
                    o.items.forEach { i ->
                        Row(Modifier.padding(top = 6.dp)) {
                            Text("${i.quantity} × ${i.name}", Modifier.weight(1f))
                            Text(inr(i.unitPrice * i.quantity))
                        }
                    }
                    Row(Modifier.padding(top = 10.dp)) {
                        Text("Total", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text(inr(o.totalAmount), fontWeight = FontWeight.SemiBold)
                    }
                }
                o.shippingAddress?.let { a ->
                    SectionCard {
                        Text("Delivering to", fontWeight = FontWeight.SemiBold)
                        Text(listOf(a.fullName, a.line1, "${a.city} ${a.pincode}", a.state).filter { it.isNotBlank() }.joinToString("\n"))
                        if (a.phone.isNotBlank()) Text("📞 ${a.phone}", color = Muted)
                    }
                }
                if (placed) TextButton(onClick = { ctx.nav.navigate("home") }) { Text("Continue shopping") }
            }
        }
    }
}
