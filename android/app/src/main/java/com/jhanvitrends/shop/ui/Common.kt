package com.jhanvitrends.shop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.jhanvitrends.shop.data.Product
import java.text.NumberFormat
import java.util.Locale

private val inrFormat: NumberFormat = NumberFormat.getIntegerInstance(Locale("en", "IN"))

fun inr(amount: Double): String = "₹" + inrFormat.format(Math.round(amount))

@Composable
fun ProductImage(url: String, modifier: Modifier = Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFF2F4F7)),
    )
}

@Composable
fun PriceRow(product: Product, big: Boolean = false) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(inr(product.price), fontSize = if (big) 26.sp else 19.sp, fontWeight = FontWeight.Medium)
        if (product.discountPercent > 0) {
            Text(inr(product.mrp), color = Muted, fontSize = 13.sp, textDecoration = TextDecoration.LineThrough)
            Text("${product.discountPercent}% off", color = Deal, fontSize = 13.sp)
        }
    }
}

@Composable
fun Rating(product: Product) {
    if (product.reviewCount > 0) {
        val full = Math.round(product.rating).toInt().coerceIn(0, 5)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("★".repeat(full) + "☆".repeat(5 - full), color = Color(0xFFF59E0B), fontSize = 14.sp)
            Text("  ${inrFormat.format(product.reviewCount)}", color = BrandTeal, fontSize = 12.sp)
        }
    } else {
        Text(
            "New",
            color = Ok,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.background(Color(0xFFE6F4F1), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 1.dp),
        )
    }
}

@Composable
fun ProductCard(product: Product, imageUrl: String, onClick: () -> Unit, onAdd: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ProductImage(imageUrl, Modifier.fillMaxWidth().aspectRatio(1f))
            Text(product.brand.uppercase(), color = Muted, fontSize = 11.sp, maxLines = 1)
            Text(product.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, modifier = Modifier.height(40.dp))
            Rating(product)
            PriceRow(product)
            Text("✓ Free delivery", color = Ok, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp)) { Text("Add to cart") }
        }
    }
}

@Composable
fun Loading() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = Deal)
        if (onRetry != null) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

@Composable
fun EmptyState(title: String, subtitle: String? = null, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (subtitle != null) Text(subtitle, color = Muted, modifier = Modifier.padding(top = 6.dp))
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) { Column(Modifier.padding(16.dp)) { content() } }
}
