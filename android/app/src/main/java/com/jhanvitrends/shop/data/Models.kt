package com.jhanvitrends.shop.data

import kotlinx.serialization.Serializable

// Shapes of the JSON returned by the storefront API (https://shop.bhumaji.com/api/...).

@Serializable
data class Product(
    val id: Int,
    val name: String,
    val category: String,
    val brand: String,
    val price: Double,
    val mrp: Double,
    val rating: Double = 0.0,
    val reviewCount: Int = 0,
    val features: List<String> = emptyList(),
    val description: String = "",
    val imageUrl: String,
    val stock: Int? = null,
) {
    val discountPercent: Int
        get() = if (mrp > price) Math.round((1 - price / mrp) * 100).toInt() else 0
}

@Serializable
data class ProductPage(val items: List<Product>, val total: Int)

@Serializable
data class Category(val name: String, val count: Int)

@Serializable
data class User(val name: String, val email: String, val role: String = "customer")

@Serializable
data class AuthResponse(val token: String, val user: User)

@Serializable
data class CartLine(val productId: Int, val quantity: Int, val product: Product, val lineTotal: Double)

@Serializable
data class Cart(val items: List<CartLine> = emptyList(), val itemCount: Int = 0, val subtotal: Double = 0.0)

@Serializable
data class ShippingAddress(
    val fullName: String = "",
    val phone: String = "",
    val line1: String = "",
    val city: String = "",
    val pincode: String = "",
    val state: String = "",
)

@Serializable
data class OrderItem(val productId: Int, val name: String, val unitPrice: Double, val quantity: Int)

@Serializable
data class Order(
    val id: String,
    val status: String,
    val totalAmount: Double,
    val currency: String = "INR",
    val shippingAddress: ShippingAddress? = null,
    val paymentId: String? = null,
    val createdAt: String,
    val items: List<OrderItem> = emptyList(),
) {
    val shortId: String get() = id.take(8).uppercase()
}
