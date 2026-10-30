package com.jhanvitrends.shop.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Signed-in user and their token, kept in SharedPreferences; observable from Compose. */
class Session(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    var token: String? by mutableStateOf(prefs.getString("token", null))
        private set
    var user: User? by mutableStateOf(
        prefs.getString("email", null)?.let { User(prefs.getString("name", "") ?: "", it, prefs.getString("role", "customer") ?: "customer") },
    )
        private set

    /** Shown on the cart icon in the bottom bar. */
    var cartCount by mutableIntStateOf(0)

    val signedIn: Boolean get() = token != null

    fun signIn(auth: AuthResponse) {
        token = auth.token
        user = auth.user
        prefs.edit()
            .putString("token", auth.token)
            .putString("name", auth.user.name)
            .putString("email", auth.user.email)
            .putString("role", auth.user.role)
            .apply()
    }

    fun signOut() {
        token = null
        user = null
        cartCount = 0
        prefs.edit().remove("token").remove("name").remove("email").remove("role").apply()
    }

    /** The last delivery address, so checkout is pre-filled next time. */
    fun savedAddress(): ShippingAddress = ShippingAddress(
        fullName = prefs.getString("addr_name", null) ?: user?.name.orEmpty(),
        phone = prefs.getString("addr_phone", "") ?: "",
        line1 = prefs.getString("addr_line1", "") ?: "",
        city = prefs.getString("addr_city", "Bengaluru") ?: "Bengaluru",
        pincode = prefs.getString("addr_pin", "") ?: "",
        state = prefs.getString("addr_state", "Karnataka") ?: "Karnataka",
    )

    fun saveAddress(a: ShippingAddress) {
        prefs.edit()
            .putString("addr_name", a.fullName).putString("addr_phone", a.phone).putString("addr_line1", a.line1)
            .putString("addr_city", a.city).putString("addr_pin", a.pincode).putString("addr_state", a.state)
            .apply()
    }
}
