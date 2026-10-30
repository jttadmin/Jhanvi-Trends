@file:OptIn(ExperimentalMaterial3Api::class)

package com.jhanvitrends.shop.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jhanvitrends.shop.data.Api
import com.jhanvitrends.shop.data.ApiException
import com.jhanvitrends.shop.data.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Things every screen needs: the API, the session, navigation and a way to show short messages. */
class AppContext(
    val api: Api,
    val session: Session,
    val nav: NavHostController,
    private val snackbar: SnackbarHostState,
    private val scope: CoroutineScope,
) {
    fun toast(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    /** Go to sign-in, then come back to [returnTo]. */
    fun requireSignIn(returnTo: String): Boolean {
        if (session.signedIn) return true
        nav.navigate("login?next=$returnTo")
        return false
    }

    fun addToCart(productId: Int, quantity: Int = 1, then: () -> Unit = {}) {
        if (!requireSignIn("home")) return
        scope.launch {
            try {
                session.cartCount = api.addToCart(productId, quantity).itemCount
                toast("Added to cart")
                then()
            } catch (e: ApiException) {
                toast(e.message ?: "Could not add to cart")
            }
        }
    }

    fun refreshCartCount() {
        if (!session.signedIn) return
        scope.launch {
            try {
                session.cartCount = api.cart().itemCount
            } catch (_: ApiException) {
            }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val Tabs = listOf(
    Tab("home", "Shop", Icons.Filled.Home),
    Tab("cart", "Cart", Icons.Filled.ShoppingCart),
    Tab("orders", "Orders", Icons.Filled.List),
    Tab("account", "Account", Icons.Filled.Person),
)

@Composable
fun JhanviApp(api: Api, session: Session) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ctx = remember(nav) { AppContext(api, session, nav, snackbar, scope) }
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    LaunchedEffect(session.token) { ctx.refreshCartCount() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            if (tab.route == "cart" && session.cartCount > 0) {
                                BadgedBox(badge = { Badge { Text(session.cartCount.toString()) } }) { Icon(tab.icon, tab.label) }
                            } else {
                                Icon(tab.icon, tab.label)
                            }
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") { HomeScreen(ctx) }
            composable("product/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) {
                ProductScreen(ctx, it.arguments?.getInt("id") ?: 0)
            }
            composable("cart") { CartScreen(ctx) }
            composable("checkout") { CheckoutScreen(ctx) }
            composable("orders") { OrdersScreen(ctx) }
            composable(
                "order/{id}?placed={placed}",
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("placed") { type = NavType.BoolType; defaultValue = false },
                ),
            ) {
                OrderScreen(ctx, it.arguments?.getString("id").orEmpty(), it.arguments?.getBoolean("placed") ?: false)
            }
            composable(
                "login?next={next}",
                arguments = listOf(navArgument("next") { type = NavType.StringType; defaultValue = "account" }),
            ) {
                AuthScreen(ctx, it.arguments?.getString("next") ?: "account")
            }
            composable("account") { AccountScreen(ctx) }
        }
    }
}
