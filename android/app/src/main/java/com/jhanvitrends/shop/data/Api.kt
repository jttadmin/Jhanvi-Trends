package com.jhanvitrends.shop.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** An error the API returned, with the server's own message ("your cart is empty", ...). */
class ApiException(val status: Int, message: String) : Exception(message)

/** Thin client for the storefront BFF. Every call runs on the IO dispatcher. */
class Api(private val baseUrl: String, private val session: Session) {

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** Absolute URL for an image path such as /api/images/3.svg. */
    fun imageUrl(path: String): String = if (path.startsWith("http")) path else baseUrl + path

    private suspend fun send(method: String, path: String, body: String? = null, query: Map<String, String?> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val url = (baseUrl + "/api" + path).toHttpUrl().newBuilder().apply {
                query.forEach { (k, v) -> if (!v.isNullOrBlank()) addQueryParameter(k, v) }
            }.build()
            val builder = Request.Builder().url(url).header("Accept", "application/json")
            session.token?.let { builder.header("Authorization", "Bearer $it") }
            val requestBody = body?.toRequestBody(jsonType)
            when (method) {
                "GET" -> builder.get()
                "DELETE" -> if (requestBody != null) builder.delete(requestBody) else builder.delete()
                else -> builder.method(method, requestBody ?: "{}".toRequestBody(jsonType))
            }
            try {
                http.newCall(builder.build()).execute().use { res ->
                    val text = res.body?.string().orEmpty()
                    if (res.code == 401 && session.token != null) session.signOut()
                    if (!res.isSuccessful) throw ApiException(res.code, errorMessage(text, res.code))
                    text
                }
            } catch (e: IOException) {
                throw ApiException(0, "Can't reach the store. Check your internet connection.")
            }
        }

    private fun errorMessage(text: String, code: Int): String = try {
        json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content ?: "Request failed ($code)"
    } catch (e: Exception) {
        "Request failed ($code)"
    }

    private suspend inline fun <reified T> get(path: String, query: Map<String, String?> = emptyMap()): T =
        json.decodeFromString(send("GET", path, query = query))

    // ---------- catalog ----------
    suspend fun products(q: String? = null, category: String? = null, sort: String? = null, offset: Int = 0, limit: Int = 20): ProductPage =
        get("/products", mapOf("q" to q, "category" to category, "sort" to sort, "offset" to offset.toString(), "limit" to limit.toString()))

    suspend fun product(id: Int): Product = get("/products/$id")

    suspend fun categories(): List<Category> = get("/categories")

    // ---------- account ----------
    @Serializable private data class LoginBody(val email: String, val password: String)
    @Serializable private data class RegisterBody(val name: String, val email: String, val password: String)

    suspend fun login(email: String, password: String): AuthResponse =
        json.decodeFromString<AuthResponse>(send("POST", "/auth/login", json.encodeToString(LoginBody(email.trim(), password))))
            .also { session.signIn(it) }

    suspend fun register(name: String, email: String, password: String): AuthResponse =
        json.decodeFromString<AuthResponse>(send("POST", "/auth/register", json.encodeToString(RegisterBody(name.trim(), email.trim(), password))))
            .also { session.signIn(it) }

    // ---------- cart ----------
    @Serializable private data class AddBody(val productId: Int, val quantity: Int)
    @Serializable private data class QtyBody(val quantity: Int)

    suspend fun cart(): Cart = get("/cart")

    suspend fun addToCart(productId: Int, quantity: Int = 1): Cart =
        json.decodeFromString(send("POST", "/cart/items", json.encodeToString(AddBody(productId, quantity))))

    suspend fun setQuantity(productId: Int, quantity: Int): Cart =
        json.decodeFromString(send("PUT", "/cart/items/$productId", json.encodeToString(QtyBody(quantity))))

    suspend fun removeFromCart(productId: Int): Cart = json.decodeFromString(send("DELETE", "/cart/items/$productId"))

    // ---------- orders ----------
    @Serializable private data class CheckoutBody(val shippingAddress: ShippingAddress)

    suspend fun checkout(address: ShippingAddress): Order =
        json.decodeFromString(send("POST", "/checkout", json.encodeToString(CheckoutBody(address))))

    suspend fun orders(): List<Order> = get("/orders")

    suspend fun order(id: String): Order = get("/orders/$id")
}
