@file:OptIn(ExperimentalMaterial3Api::class)

package com.jhanvitrends.shop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jhanvitrends.shop.data.ApiException
import com.jhanvitrends.shop.data.Product

@Composable
fun ProductScreen(ctx: AppContext, id: Int) {
    var product by remember { mutableStateOf<Product?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var qty by remember { mutableIntStateOf(1) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(id, reload) {
        error = null
        try {
            product = ctx.api.product(id)
        } catch (e: ApiException) {
            error = if (e.status == 404) "This product is no longer available." else e.message
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(product?.category ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = { ctx.nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "Back") } },
        )
        val p = product
        when {
            error != null -> ErrorBox(error!!) { reload++ }
            p == null -> Loading()
            else -> {
                val stock = p.stock
                val maxQty = (stock ?: 10).coerceIn(1, 10)
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ProductImage(ctx.api.imageUrl(p.imageUrl), Modifier.fillMaxWidth().aspectRatio(1f))
                    Text(p.name, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                    Text("by ${p.brand}", color = BrandTeal)
                    Rating(p)
                    HorizontalDivider()
                    PriceRow(p, big = true)
                    Text("Inclusive of all taxes", color = Muted, fontSize = 12.sp)
                    when {
                        stock == null -> Text("Stock information unavailable", color = Muted)
                        stock == 0 -> Text("Currently unavailable", color = Deal, fontSize = 17.sp)
                        stock < 10 -> Text("Only $stock left in stock", color = Deal, fontSize = 17.sp)
                        else -> Text("In stock", color = Ok, fontSize = 17.sp)
                    }
                    Text("✓ Free delivery in 2 days", color = Ok, fontWeight = FontWeight.SemiBold)

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Quantity", Modifier.weight(1f))
                        OutlinedButton(onClick = { if (qty > 1) qty-- }, enabled = qty > 1) { Text("−") }
                        Text("$qty", Modifier.width(40.dp), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        OutlinedButton(onClick = { if (qty < maxQty) qty++ }, enabled = qty < maxQty) { Text("+") }
                    }
                    Button(
                        onClick = { ctx.addToCart(p.id, qty) },
                        enabled = stock != 0,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) { Text("Add to cart") }
                    Button(
                        onClick = { ctx.addToCart(p.id, qty) { ctx.nav.navigate("cart") } },
                        enabled = stock != 0,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandMint, contentColor = BrandDark),
                    ) { Text("Buy now") }

                    if (p.features.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("About this item", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        p.features.forEach { Text("•  $it") }
                    }
                    if (p.description.isNotBlank()) Text(p.description, color = Muted)
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}
