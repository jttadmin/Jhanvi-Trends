// Jhanvi Trends storefront: a tiny hash-routed SPA talking to the BFF at /api.
(() => {
  const $ = (sel, el = document) => el.querySelector(sel);
  const app = $('#app');
  const state = {
    token: localStorage.getItem('sv_token'),
    user: JSON.parse(localStorage.getItem('sv_user') || 'null'),
    categories: [],
    lastOrder: null,
  };

  // ---------- helpers ----------
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const inr = (n) => Number(n).toLocaleString('en-IN', { maximumFractionDigits: 0 });
  const priceHtml = (p) => {
    const off = p.mrp > p.price ? Math.round((1 - p.price / p.mrp) * 100) : 0;
    return `<div><span class="price"><sup>₹</sup>${inr(p.price)}</span>${off ? `<span class="mrp">M.R.P. ₹${inr(p.mrp)}</span><span class="off">(${off}% off)</span>` : ''}</div>`;
  };
  const stars = (r) => {
    const full = Math.round(r);
    return `<span class="stars" title="${r} out of 5">${'★'.repeat(full)}${'☆'.repeat(5 - full)}</span>`;
  };
  const img = (p, w = 400) => `<img loading="lazy" alt="${esc(p.name)}" src="${esc(p.imageUrl)}"
      onerror="this.onerror=null;this.src='data:image/svg+xml,'+encodeURIComponent('<svg xmlns=&quot;http://www.w3.org/2000/svg&quot; viewBox=&quot;0 0 ${w} ${w}&quot;><rect width=&quot;100%&quot; height=&quot;100%&quot; fill=&quot;#eef2f6&quot;/><text x=&quot;50%&quot; y=&quot;50%&quot; font-size=&quot;28&quot; text-anchor=&quot;middle&quot; fill=&quot;#667085&quot; font-family=&quot;Arial&quot;>${esc(p.category)}</text></svg>')" />`;

  function toast(msg, bad = false) {
    const t = $('#toast');
    t.textContent = msg;
    t.className = `toast show${bad ? ' bad' : ''}`;
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => (t.className = 'toast'), 3000);
  }

  async function api(path, { method = 'GET', body } = {}) {
    const headers = { 'content-type': 'application/json' };
    if (state.token) headers.authorization = `Bearer ${state.token}`;
    const res = await fetch(`/api${path}`, { method, headers, body: body ? JSON.stringify(body) : undefined });
    const data = await res.json().catch(() => ({}));
    if (res.status === 401 && state.token) {
      signOut(false);
      location.hash = `#/login?next=${encodeURIComponent(location.hash.slice(1))}`;
    }
    if (!res.ok) {
      const err = new Error(data.error || `Request failed (${res.status})`);
      err.status = res.status;
      throw err;
    }
    return data;
  }

  function setSession(token, user) {
    state.token = token;
    state.user = user;
    localStorage.setItem('sv_token', token);
    localStorage.setItem('sv_user', JSON.stringify(user));
    renderHeader();
  }
  function signOut(notify = true) {
    state.token = null;
    state.user = null;
    localStorage.removeItem('sv_token');
    localStorage.removeItem('sv_user');
    $('#cart-count').textContent = '0';
    renderHeader();
    if (notify) toast('You are signed out');
  }

  function renderHeader() {
    const link = $('#account-link');
    if (state.user) {
      $('#hello').textContent = `Hi, ${state.user.name.split(' ')[0]}`;
      $('#account-label').textContent = 'My account';
      link.href = '#/account';
      $('#admin-link').hidden = state.user.role !== 'admin';
    } else {
      $('#admin-link').hidden = true;
      $('#hello').textContent = 'Welcome';
      $('#account-label').textContent = 'Sign in';
      link.href = '#/login';
    }
  }

  async function refreshCartCount() {
    if (!state.token) return;
    try {
      const cart = await api('/cart');
      $('#cart-count').textContent = cart.itemCount;
    } catch { /* header badge is best effort */ }
  }

  function requireLogin() {
    if (state.token) return true;
    location.hash = `#/login?next=${encodeURIComponent(location.hash.slice(1))}`;
    return false;
  }

  async function addToCart(productId, quantity = 1, goToCart = false) {
    if (!requireLogin()) return;
    try {
      const cart = await api('/cart/items', { method: 'POST', body: { productId, quantity } });
      $('#cart-count').textContent = cart.itemCount;
      if (goToCart) location.hash = '#/cart';
      else toast('Added to cart');
    } catch (e) {
      toast(e.message, true);
    }
  }
  window.sv = { addToCart, api, esc, inr, toast, state, requireLogin, img };

  const loading = (n = 8) => `<div class="grid">${'<div class="card"><div class="thumb skeleton"></div><div class="skeleton" style="height:16px"></div><div class="skeleton" style="height:16px;width:60%"></div></div>'.repeat(n)}</div>`;

  const productCard = (p) => `
    <div class="card">
      <a class="thumb" href="#/product/${p.id}">${img(p)}</a>
      <span class="brand">${esc(p.brand)}</span>
      <a class="title" href="#/product/${p.id}">${esc(p.name)}</a>
      <div>${p.reviewCount ? `${stars(p.rating)} <span class="reviews">${inr(p.reviewCount)}</span>` : '<span class="new-badge">New</span>'}</div>
      ${priceHtml(p)}
      <span class="perk">✓ Free delivery</span>
      <button class="btn" onclick="sv.addToCart(${p.id})">Add to cart</button>
    </div>`;

  // ---------- pages ----------
  const PAGE = 20;

  async function homePage(params) {
    const q = params.get('q') || '';
    const category = params.get('category') || '';
    const sort = params.get('sort') || '';
    const page = Math.max(Number(params.get('page') || 1), 1);
    $('#search-q').value = q;
    $('#search-cat').value = category;
    document.querySelectorAll('#subnav a').forEach((a) => a.classList.toggle('active', a.dataset.cat === category || (!category && !a.dataset.cat)));

    const heading = q ? `Results for “${esc(q)}”` : category || 'Popular right now';
    app.innerHTML = `
      ${!q && !category ? `<section class="hero"><h1>Great deals, delivered fast</h1><p>Electronics, books, home, fashion and more — powered by 8 microservices.</p></section>` : ''}
      <div class="toolbar"><h2 class="section-title">${heading}</h2>
        <select id="sort" aria-label="Sort by">
          <option value="">Sort by: Featured</option>
          <option value="price_asc">Price: Low to High</option>
          <option value="price_desc">Price: High to Low</option>
          <option value="rating">Avg. Customer Review</option>
        </select>
      </div>
      <div id="results">${loading()}</div>`;
    $('#sort').value = sort;
    $('#sort').onchange = (e) => navigate({ q, category, sort: e.target.value });

    const qs = new URLSearchParams({ limit: PAGE, offset: (page - 1) * PAGE });
    if (q) qs.set('q', q);
    if (category) qs.set('category', category);
    if (sort) qs.set('sort', sort);
    try {
      const { items, total } = await api(`/products?${qs}`);
      if (!items.length) {
        $('#results').innerHTML = `<div class="panel empty"><h3>No results</h3><p class="muted">Try checking your spelling or use more general terms.</p></div>`;
        return;
      }
      const pages = Math.ceil(total / PAGE);
      $('#results').innerHTML = `<p class="muted">${(page - 1) * PAGE + 1}-${Math.min(page * PAGE, total)} of ${total} results</p>
        <div class="grid">${items.map(productCard).join('')}</div>
        ${pages > 1 ? `<div class="pager">${page > 1 ? `<button class="btn ghost" data-p="${page - 1}">‹ Previous</button>` : ''}<span class="muted" style="align-self:center">Page ${page} of ${pages}</span>${page < pages ? `<button class="btn ghost" data-p="${page + 1}">Next ›</button>` : ''}</div>` : ''}`;
      document.querySelectorAll('.pager button').forEach((b) => (b.onclick = () => navigate({ q, category, sort, page: b.dataset.p })));
    } catch (e) {
      $('#results').innerHTML = `<div class="error">Could not load products: ${esc(e.message)}</div>`;
    }
  }

  function navigate(p) {
    const qs = new URLSearchParams();
    for (const [k, v] of Object.entries(p)) {
      if (!v || (k === 'page' && String(v) === '1')) continue;
      qs.set(k, v);
    }
    location.hash = `#/?${qs}`;
  }

  async function productPage(id) {
    app.innerHTML = `<div class="pdp"><div class="gallery skeleton" style="aspect-ratio:1"></div><div><div class="skeleton" style="height:28px"></div></div><div></div></div>`;
    try {
      const p = await api(`/products/${id}`);
      const stock = p.stock;
      const stockHtml = stock === null ? '<span class="muted">Stock information unavailable</span>'
        : stock === 0 ? '<span class="low-stock in-stock">Currently unavailable.</span>'
        : stock < 10 ? `<span class="in-stock low-stock">Only ${stock} left in stock.</span>` : '<span class="in-stock">In stock</span>';
      const eta = new Date(Date.now() + 2 * 864e5).toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'long' });
      app.innerHTML = `
        <p><a href="#/?category=${encodeURIComponent(p.category)}">‹ ${esc(p.category)}</a></p>
        <div class="pdp">
          <div class="gallery">${img(p, 600)}</div>
          <div>
            <h1>${esc(p.name)}</h1>
            <a href="#/?q=${encodeURIComponent(p.brand)}">More from ${esc(p.brand)}</a>
            <div>${p.reviewCount ? `${p.rating} ${stars(p.rating)} <span class="reviews">${inr(p.reviewCount)} ratings</span>` : '<span class="new-badge">New arrival</span>'}</div>
            <hr />
            ${priceHtml(p)}
            <p class="muted">Inclusive of all taxes</p>
            <hr />
            <h3>About this item</h3>
            <ul>${(p.features || []).map((f) => `<li>${esc(f)}</li>`).join('')}</ul>
            <p>${esc(p.description)}</p>
          </div>
          <aside class="buybox">
            ${priceHtml(p)}
            <span>FREE delivery <b>${eta}</b></span>
            ${stockHtml}
            <label>Quantity: <select class="qty" id="qty">${Array.from({ length: Math.max(Math.min(stock ?? 10, 10), 1) }, (_, i) => `<option>${i + 1}</option>`).join('')}</select></label>
            <button class="btn block" id="add" ${stock === 0 ? 'disabled' : ''}>Add to Cart</button>
            <button class="btn alt block" id="buy" ${stock === 0 ? 'disabled' : ''}>Buy Now</button>
            <small class="muted">Sold by ${esc(p.brand)} · 7-day returns</small>
          </aside>
        </div>`;
      $('#add').onclick = () => addToCart(p.id, Number($('#qty').value));
      $('#buy').onclick = () => addToCart(p.id, Number($('#qty').value), true);
    } catch (e) {
      app.innerHTML = `<div class="panel empty"><h2>Sorry, we couldn't find that page</h2><p class="muted">${esc(e.message)}</p><a href="#/">Go to the home page</a></div>`;
    }
  }

  async function cartPage() {
    if (!requireLogin()) return;
    app.innerHTML = `<div class="panel">${loading(2)}</div>`;
    let cart;
    try {
      cart = await api('/cart');
    } catch (e) {
      app.innerHTML = `<div class="error">Could not load your cart: ${esc(e.message)}</div>`;
      return;
    }
    $('#cart-count').textContent = cart.itemCount;
    if (!cart.items.length) {
      app.innerHTML = `<div class="panel empty"><h2>Your cart is empty</h2><p><a href="#/">Start shopping</a></p></div>`;
      return;
    }
    app.innerHTML = `
      <div class="cart-layout">
        <section class="panel">
          <h1 style="font-weight:400;margin:0">Shopping Cart</h1>
          <p class="muted" style="text-align:right;margin:0">Price</p>
          ${cart.items.map((i) => `
            <div class="cart-row">
              <a href="#/product/${i.productId}">${img(i.product, 140)}</a>
              <div>
                <a href="#/product/${i.productId}" style="color:var(--ink);font-size:17px">${esc(i.product.name)}</a>
                <div class="in-stock" style="font-size:13px">In stock</div>
                <div class="actions">
                  <select class="qty" data-id="${i.productId}" aria-label="Quantity">${Array.from({ length: Math.max(10, i.quantity) }, (_, n) => `<option ${n + 1 === i.quantity ? 'selected' : ''}>${n + 1}</option>`).join('')}</select>
                  <button class="linkish" data-del="${i.productId}">Delete</button>
                </div>
              </div>
              <b class="price" style="font-size:18px">₹${inr(i.lineTotal)}</b>
            </div>`).join('')}
          <p style="text-align:right;font-size:18px">Subtotal (${cart.itemCount} items): <b>₹${inr(cart.subtotal)}</b></p>
        </section>
        <aside class="panel summary">
          <p class="perk">✓ Free delivery on this order</p>
          <div class="total">Subtotal (${cart.itemCount} items): <b>₹${inr(cart.subtotal)}</b></div>
          <a class="btn block" style="display:block;text-align:center" href="#/checkout">Checkout</a>
        </aside>
      </div>`;
    document.querySelectorAll('select[data-id]').forEach((s) => (s.onchange = async () => {
      try { await api(`/cart/items/${s.dataset.id}`, { method: 'PUT', body: { quantity: Number(s.value) } }); cartPage(); } catch (e) { toast(e.message, true); }
    }));
    document.querySelectorAll('[data-del]').forEach((b) => (b.onclick = async () => {
      try { await api(`/cart/items/${b.dataset.del}`, { method: 'DELETE' }); cartPage(); } catch (e) { toast(e.message, true); }
    }));
  }

  async function checkoutPage() {
    if (!requireLogin()) return;
    const cart = await api('/cart').catch(() => null);
    if (!cart || !cart.items.length) { location.hash = '#/cart'; return; }
    const saved = JSON.parse(localStorage.getItem('sv_addr') || '{}');
    app.innerHTML = `
      <div class="cart-layout">
        <form class="panel" id="co">
          <h1 style="font-weight:400;margin-top:0">Checkout</h1>
          <h3>1. Delivery address</h3>
          <div class="form" style="max-width:none;margin:0">
            <label>Full name</label><input name="fullName" required value="${esc(saved.fullName || state.user.name)}" />
            <label>Mobile number</label><input name="phone" inputmode="tel" value="${esc(saved.phone || '')}" />
            <label>Flat, house no., building, street</label><input name="line1" required value="${esc(saved.line1 || '')}" />
            <div class="row2">
              <div><label>Town/City</label><input name="city" required value="${esc(saved.city || 'Bengaluru')}" /></div>
              <div><label>PIN code</label><input name="pincode" required pattern="[0-9]{6}" value="${esc(saved.pincode || '')}" /></div>
            </div>
            <label>State</label><input name="state" value="${esc(saved.state || 'Karnataka')}" />
          </div>
          <h3>2. Payment method</h3>
          <p class="muted">Demo card ending in 4242 (the payment service declines a configurable % of payments).</p>
          <h3>3. Review items</h3>
          ${cart.items.map((i) => `<div>${i.quantity} × ${esc(i.product.name)} — ₹${inr(i.lineTotal)}</div>`).join('')}
          <div id="co-err"></div>
        </form>
        <aside class="panel summary">
          <button class="btn block" form="co" id="place">Place your order</button>
          <hr />
          <div class="total">Order Total: <b>₹${inr(cart.subtotal)}</b></div>
          <small class="muted">By placing your order you agree this is a demo store and nothing will ship.</small>
        </aside>
      </div>`;
    $('#co').onsubmit = async (e) => {
      e.preventDefault();
      const shippingAddress = Object.fromEntries(new FormData(e.target));
      localStorage.setItem('sv_addr', JSON.stringify(shippingAddress));
      $('#place').disabled = true;
      $('#place').textContent = 'Placing order…';
      try {
        state.lastOrder = await api('/checkout', { method: 'POST', body: { shippingAddress } });
        $('#cart-count').textContent = '0';
        location.hash = `#/orders/${state.lastOrder.id}?placed=1`;
      } catch (err) {
        $('#co-err').innerHTML = `<div class="error">${esc(err.status === 402 ? 'Your payment was declined. Please try again.' : err.message)}</div>`;
        $('#place').disabled = false;
        $('#place').textContent = 'Place your order';
      }
    };
  }

  const orderHtml = (o) => `
    <div class="order">
      <div class="order-head">
        <b><a href="#/orders/${o.id}">Order ${esc(o.id.slice(0, 8).toUpperCase())}</a></b>
        <span class="meta">${new Date(o.createdAt).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' })} · ₹${inr(o.totalAmount)} · to ${esc(o.shippingAddress && o.shippingAddress.fullName)}</span>
      </div>
      <div class="order-body">
        <div class="status ${o.status}">${o.status === 'CONFIRMED' ? 'Confirmed — arriving in 2 days' : esc(o.status.replace('_', ' ').toLowerCase())}</div>
        ${o.items.map((i) => `<div style="margin-top:8px"><a href="#/product/${i.productId}">${esc(i.name)}</a> <span class="muted">× ${i.quantity} · ₹${inr(i.unitPrice)}</span></div>`).join('')}
        ${o.paymentId ? `<p class="muted" style="margin-bottom:0">Payment ${esc(o.paymentId)}</p>` : ''}
      </div>
    </div>`;

  async function ordersPage() {
    if (!requireLogin()) return;
    app.innerHTML = `<div class="panel">${loading(2)}</div>`;
    try {
      const orders = await api('/orders');
      app.innerHTML = `<h1 style="font-weight:400">Your Orders</h1>${orders.length ? orders.map(orderHtml).join('') : '<div class="panel empty"><p>You have not placed any orders yet.</p><a href="#/">Start shopping</a></div>'}`;
    } catch (e) {
      app.innerHTML = `<div class="error">Could not load orders: ${esc(e.message)}</div>`;
    }
  }

  async function orderPage(id, params) {
    if (!requireLogin()) return;
    try {
      const o = await api(`/orders/${id}`);
      app.innerHTML = `${params.get('placed') ? `<div class="success-banner"><h2 style="margin:0;color:var(--ok)">✓ Order placed, thank you!</h2><p style="margin:4px 0 0">A confirmation will be sent to ${esc(state.user.email)}.</p></div>` : ''}
        ${orderHtml(o)}<a href="#/orders">‹ All orders</a>`;
    } catch (e) {
      app.innerHTML = `<div class="error">${esc(e.message)}</div>`;
    }
  }

  function authPage(mode, params) {
    const next = params.get('next') || '/';
    const isLogin = mode === 'login';
    app.innerHTML = `
      <form class="panel form" id="auth">
        <h1>${isLogin ? 'Sign in' : 'Create account'}</h1>
        ${isLogin ? '' : '<label>Your name</label><input name="name" required autocomplete="name" />'}
        <label>Email</label><input name="email" type="email" required autocomplete="email" />
        <label>Password</label><input name="password" type="password" required minlength="6" autocomplete="${isLogin ? 'current-password' : 'new-password'}" />
        <button class="btn block" style="margin-top:16px">${isLogin ? 'Sign in' : 'Create your Jhanvi Trends account'}</button>
        <div id="auth-err"></div>
        <hr style="margin:20px 0;border:0;border-top:1px solid var(--line)" />
        <p class="muted">${isLogin ? 'New to Jhanvi Trends?' : 'Already have an account?'}
          <a href="#/${isLogin ? 'register' : 'login'}?next=${encodeURIComponent(next)}">${isLogin ? 'Create your account' : 'Sign in'}</a></p>
      </form>`;
    $('#auth').onsubmit = async (e) => {
      e.preventDefault();
      try {
        const { token, user } = await api(`/auth/${mode}`, { method: 'POST', body: Object.fromEntries(new FormData(e.target)) });
        setSession(token, user);
        refreshCartCount();
        location.hash = `#${next}`;
      } catch (err) {
        $('#auth-err').innerHTML = `<div class="error">${esc(err.message)}</div>`;
      }
    };
  }

  function accountPage() {
    if (!requireLogin()) return;
    app.innerHTML = `<div class="panel form"><h1>Your Account</h1><p><b>${esc(state.user.name)}</b><br/><span class="muted">${esc(state.user.email)}</span></p>
      <p><a href="#/orders">Your orders</a></p><button class="btn ghost" id="out">Sign out</button></div>`;
    $('#out').onclick = () => { signOut(); location.hash = '#/'; };
  }

  // ---------- router ----------
  async function route() {
    const [path, query = ''] = location.hash.slice(1).split('?');
    const params = new URLSearchParams(query);
    const parts = (path || '/').split('/').filter(Boolean);
    window.scrollTo(0, 0);
    if (!parts.length) return homePage(params);
    switch (parts[0]) {
      case 'product': return productPage(Number(parts[1]));
      case 'cart': return cartPage();
      case 'checkout': return checkoutPage();
      case 'orders': return parts[1] ? orderPage(parts[1], params) : ordersPage();
      case 'login': return authPage('login', params);
      case 'register': return authPage('register', params);
      case 'account': return accountPage();
      case 'admin':
        if (!requireLogin()) return;
        if (state.user.role !== 'admin') {
          app.innerHTML = '<div class="panel empty"><h2>Admins only</h2><p class="muted">Your account does not have admin access.</p></div>';
          return;
        }
        return window.svAdmin.page(parts[1] || 'dashboard', params);
      default: return homePage(params);
    }
  }

  async function init() {
    renderHeader();
    $('#search-form').onsubmit = (e) => {
      e.preventDefault();
      navigate({ q: $('#search-q').value.trim(), category: $('#search-cat').value });
    };
    try {
      state.categories = await api('/categories');
      $('#search-cat').innerHTML = '<option value="">All</option>' + state.categories.map((c) => `<option>${esc(c.name)}</option>`).join('');
      $('#subnav').innerHTML = '<a href="#/" data-cat="">All</a>' + state.categories.map((c) => `<a href="#/?category=${encodeURIComponent(c.name)}" data-cat="${esc(c.name)}">${esc(c.name)}</a>`).join('');
    } catch { /* catalog down: page still renders an error */ }
    if (state.token) {
      try {
        const me = await api('/me');
        state.user = { ...state.user, ...me };
        localStorage.setItem('sv_user', JSON.stringify(state.user));
        renderHeader();
      } catch { /* token expired: api() already signed out */ }
    }
    window.addEventListener('hashchange', route);
    refreshCartCount();
    route();
  }
  init();
})();
