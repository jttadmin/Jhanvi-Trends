@file:OptIn(ExperimentalMaterial3Api::class)

package com.jhanvitrends.shop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jhanvitrends.shop.data.ApiException
import com.jhanvitrends.shop.data.Category
import com.jhanvitrends.shop.data.Product
import kotlinx.coroutines.launch

private val Sorts = listOf("" to "Featured", "price_asc" to "Price: low to high", "price_desc" to "Price: high to low", "rating" to "Top rated")
private const val PAGE = 20

@Composable
fun HomeScreen(ctx: AppContext) {
    var query by rememberSaveable { mutableStateOf("") }
    var activeQuery by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("") }
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    val products = remember { mutableStateListOf<Product>() }
    var total by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val grid = rememberLazyGridState()

    suspend fun loadPage(offset: Int) {
        loading = true
        error = null
        try {
            val page = ctx.api.products(activeQuery.ifBlank { null }, category.ifBlank { null }, sort.ifBlank { null }, offset, PAGE)
            if (offset == 0) products.clear()
            products.addAll(page.items)
            total = page.total
        } catch (e: ApiException) {
            error = e.message
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        try { categories = ctx.api.categories() } catch (_: ApiException) {}
    }
    LaunchedEffect(activeQuery, category, sort, reload) {
        loadPage(0)
        grid.scrollToItem(0)
    }
    // Load the next page when the user scrolls near the end.
    val nearEnd by remember {
        derivedStateOf {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= grid.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, products.size, total) {
        if (nearEnd && !loading && error == null && products.size in 1 until total) loadPage(products.size)
    }

    Column(Modifier.fillMaxSize()) {
        // Header with search
        Column(Modifier.fillMaxWidth().background(BrandDark).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Jhanvi ", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                Text("Trends", color = BrandMint, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
            }
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search Jhanvi Trends") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = ""; activeQuery = "" }) { Icon(Icons.Filled.Clear, "Clear") }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { activeQuery = query.trim() }),
                shape = RoundedCornerShape(8.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        // Category chips
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.background(Color(0xFF17595B)),
        ) {
            item {
                FilterChip(
                    selected = category.isEmpty(), onClick = { category = "" }, label = { Text("All") },
                    colors = FilterChipDefaults.filterChipColors(labelColor = Color.White, selectedContainerColor = BrandMint),
                )
            }
            items(categories, key = { it.name }) { c ->
                FilterChip(
                    selected = category == c.name, onClick = { category = if (category == c.name) "" else c.name }, label = { Text(c.name) },
                    colors = FilterChipDefaults.filterChipColors(labelColor = Color.White, selectedContainerColor = BrandMint),
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            state = grid,
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val heading = when {
                        activeQuery.isNotBlank() -> "Results for “$activeQuery”"
                        category.isNotBlank() -> category
                        else -> "Popular right now"
                    }
                    Column(Modifier.weight(1f)) {
                        Text(heading, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        if (!loading || products.isNotEmpty()) Text("$total products", color = Muted, fontSize = 12.sp)
                    }
                    SortMenu(sort) { sort = it }
                }
            }
            if (error != null && products.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { ErrorBox(error!!) { reload++ } }
            } else if (!loading && products.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState("No results", "Try different words or another category.")
                }
            }
            items(products, key = { it.id }) { p ->
                ProductCard(
                    product = p,
                    imageUrl = ctx.api.imageUrl(p.imageUrl),
                    onClick = { ctx.nav.navigate("product/${p.id}") },
                    onAdd = { scope.launch { ctx.addToCart(p.id) } },
                )
            }
            if (loading) item(span = { GridItemSpan(maxLineSpan) }) { Loading() }
        }
    }
}

@Composable
private fun SortMenu(sort: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(Sorts.first { it.first == sort }.second) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        Sorts.forEach { (value, label) ->
            DropdownMenuItem(text = { Text(label) }, onClick = { onChange(value); open = false })
        }
    }
}
