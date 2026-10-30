// frontend: serves the storefront SPA and acts as the Backend-For-Frontend.
// It is the only service exposed through the ingress. It verifies the JWT
// and calls the internal services, passing the user id along.
const path = require('path');
const express = require('express');
const jwt = require('jsonwebtoken');
const { logger, client, register, metricsMiddleware, mountOps, errorHandler } = require('./obs');

const PORT = Number(process.env.PORT || 8080);
const JWT_SECRET = process.env.JWT_SECRET || 'dev-secret-change-me';
const svc = {
  user: process.env.USER_SERVICE_URL || 'http://user-service:8080',
  catalog: process.env.CATALOG_SERVICE_URL || 'http://catalog-service:8080',
  cart: process.env.CART_SERVICE_URL || 'http://cart-service:8080',
  inventory: process.env.INVENTORY_SERVICE_URL || 'http://inventory-service:8080',
  order: process.env.ORDER_SERVICE_URL || 'http://order-service:8080',
};

const checkouts = new client.Counter({
  name: 'checkouts_total', help: 'Checkout attempts by result', labelNames: ['result'], registers: [register],
});

class HttpError extends Error {
  constructor(status, message, body) {
    super(message);
    this.status = status;
    this.expose = status < 500;
    this.body = body;
  }
}

// Small fetch wrapper: JSON in/out, timeout, upstream errors mapped through.
// Outgoing fetch calls are traced by the OTel undici instrumentation.
async function call(base, urlPath, { method = 'GET', body, userId, timeoutMs = 5000 } = {}) {
  const headers = { 'content-type': 'application/json' };
  if (userId) headers['x-user-id'] = String(userId);
  let res;
  try {
    res = await fetch(base + urlPath, {
      method, headers, body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(timeoutMs),
    });
  } catch (err) {
    throw new HttpError(503, `upstream ${base} unavailable: ${err.message}`);
  }
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) throw new HttpError(res.status, (data && (data.error || data.detail)) || `upstream error ${res.status}`, data);
  return data;
}

const wrap = (fn) => (req, res, next) => fn(req, res, next).catch(next);

function auth(req, _res, next) {
  const header = req.get('authorization') || '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : null;
  try {
    if (!token) throw new Error('missing token');
    const claims = jwt.verify(token, JWT_SECRET);
    req.user = { id: claims.sub, name: claims.name, email: claims.email, role: claims.role || 'customer' };
    next();
  } catch {
    next(new HttpError(401, 'please sign in'));
  }
}

async function productsByIds(ids) {
  if (!ids.length) return {};
  const { items } = await call(svc.catalog, `/products?ids=${ids.join(',')}&limit=${ids.length}`);
  return Object.fromEntries(items.map((p) => [p.id, p]));
}

async function enrichedCart(userId) {
  const cart = await call(svc.cart, `/cart/${userId}`);
  const products = await productsByIds(cart.items.map((i) => i.productId));
  const items = cart.items
    .filter((i) => products[i.productId])
    .map((i) => ({ ...i, product: products[i.productId], lineTotal: +(products[i.productId].price * i.quantity).toFixed(2) }));
  const subtotal = +items.reduce((s, i) => s + i.lineTotal, 0).toFixed(2);
  return { items, itemCount: items.reduce((s, i) => s + i.quantity, 0), subtotal };
}

function adminOnly(req, _res, next) {
  if (req.user && req.user.role === 'admin') return next();
  return next(new HttpError(403, 'admin access required'));
}

// Forward a raw (binary) body, used for product photo uploads.
async function callRaw(base, urlPath, { method, body, contentType, timeoutMs = 15000 }) {
  let res;
  try {
    res = await fetch(base + urlPath, { method, body, headers: { 'content-type': contentType }, signal: AbortSignal.timeout(timeoutMs) });
  } catch (err) {
    throw new HttpError(503, `upstream ${base} unavailable: ${err.message}`);
  }
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) throw new HttpError(res.status, (data && (data.error || data.detail)) || `upstream error ${res.status}`, data);
  return data;
}

const adminChanges = new client.Counter({
  name: 'admin_actions_total', help: 'Admin panel actions', labelNames: ['action'], registers: [register],
});

const app = express();
app.use(express.json());
app.use(metricsMiddleware);
mountOps(app);

const api = express.Router();
// Remember the mount path so error responses are labelled /api/... in metrics too.
api.use((req, res, next) => { res.locals.baseUrl = req.baseUrl; next(); });

api.post('/auth/register', wrap(async (req, res) => res.status(201).json(await call(svc.user, '/register', { method: 'POST', body: req.body }))));
api.post('/auth/login', wrap(async (req, res) => res.json(await call(svc.user, '/login', { method: 'POST', body: req.body }))));
api.get('/me', auth, (req, res) => res.json(req.user));

api.get('/categories', wrap(async (_req, res) => res.json(await call(svc.catalog, '/categories'))));
api.get('/products', wrap(async (req, res) => {
  const qs = new URLSearchParams();
  for (const k of ['q', 'category', 'limit', 'offset', 'sort']) if (req.query[k]) qs.set(k, req.query[k]);
  res.json(await call(svc.catalog, `/products?${qs}`));
}));
api.get('/images/:file', wrap(async (req, res) => {
  const upstream = await fetch(`${svc.catalog}/images/${encodeURIComponent(req.params.file)}`, { signal: AbortSignal.timeout(5000) })
    .catch((err) => { throw new HttpError(503, `catalog unavailable: ${err.message}`); });
  if (!upstream.ok) throw new HttpError(upstream.status, 'image not found');
  res.set('content-type', upstream.headers.get('content-type') || 'image/svg+xml');
  res.set('cache-control', upstream.headers.get('cache-control') || 'public, max-age=3600');
  res.send(Buffer.from(await upstream.arrayBuffer()));
}));
api.get('/products/:id', wrap(async (req, res) => {
  const id = Number(req.params.id);
  const [product, stock] = await Promise.all([
    call(svc.catalog, `/products/${id}`),
    call(svc.inventory, `/stock/${id}`).catch((err) => {
      logger.warn({ err: err.message, productId: id }, 'stock lookup failed, showing product without stock');
      return null;
    }),
  ]);
  res.json({ ...product, stock: stock ? stock.available : null });
}));

api.get('/cart', auth, wrap(async (req, res) => res.json(await enrichedCart(req.user.id))));
api.post('/cart/items', auth, wrap(async (req, res) => {
  const { productId, quantity = 1 } = req.body || {};
  await call(svc.catalog, `/products/${Number(productId)}`); // 404s for unknown products
  await call(svc.cart, `/cart/${req.user.id}/items`, { method: 'POST', body: { productId: Number(productId), quantity: Number(quantity) } });
  res.status(201).json(await enrichedCart(req.user.id));
}));
api.put('/cart/items/:productId', auth, wrap(async (req, res) => {
  await call(svc.cart, `/cart/${req.user.id}/items/${Number(req.params.productId)}`, {
    method: 'PUT', body: { quantity: Number(req.body.quantity) },
  });
  res.json(await enrichedCart(req.user.id));
}));
api.delete('/cart/items/:productId', auth, wrap(async (req, res) => {
  await call(svc.cart, `/cart/${req.user.id}/items/${Number(req.params.productId)}`, { method: 'DELETE' });
  res.json(await enrichedCart(req.user.id));
}));

api.post('/checkout', auth, wrap(async (req, res) => {
  const address = req.body && req.body.shippingAddress;
  if (!address || !address.fullName || !address.line1 || !address.city || !address.pincode) {
    throw new HttpError(400, 'full name, address line, city and PIN code are required');
  }
  const cart = await enrichedCart(req.user.id);
  if (!cart.items.length) throw new HttpError(400, 'your cart is empty');
  try {
    // Prices always come from the catalog, never from the browser.
    const order = await call(svc.order, '/orders', {
      method: 'POST',
      userId: req.user.id,
      timeoutMs: 15000,
      body: {
        userId: req.user.id,
        userEmail: req.user.email,
        shippingAddress: address,
        items: cart.items.map((i) => ({
          productId: i.productId, name: i.product.name, unitPrice: i.product.price, quantity: i.quantity,
        })),
      },
    });
    await call(svc.cart, `/cart/${req.user.id}`, { method: 'DELETE' });
    checkouts.inc({ result: 'success' });
    logger.info({ orderId: order.id, userId: req.user.id, total: order.totalAmount }, 'checkout completed');
    res.status(201).json(order);
  } catch (err) {
    checkouts.inc({ result: err.status === 402 ? 'payment_declined' : err.status === 409 ? 'out_of_stock' : 'error' });
    throw err;
  }
}));

api.get('/orders', auth, wrap(async (req, res) => res.json(await call(svc.order, `/orders?userId=${req.user.id}`, { userId: req.user.id }))));
api.get('/orders/:id', auth, wrap(async (req, res) => {
  const order = await call(svc.order, `/orders/${encodeURIComponent(req.params.id)}`, { userId: req.user.id });
  if (String(order.userId) !== String(req.user.id)) throw new HttpError(404, 'order not found');
  res.json(order);
}));

// ---------- admin panel API (role "admin" only) ----------
const admin = express.Router();
admin.use(auth, adminOnly);

const PRODUCT_FIELDS = ['name', 'category', 'brand', 'price', 'mrp', 'description', 'features', 'active', 'icon'];
const productBody = (b = {}) => Object.fromEntries(PRODUCT_FIELDS.filter((k) => b[k] !== undefined).map((k) => [k, b[k]]));

async function stockMap() {
  const rows = await call(svc.inventory, '/stock').catch((err) => {
    logger.warn({ err: err.message }, 'stock lookup failed for admin view');
    return null;
  });
  return rows ? Object.fromEntries(rows.map((r) => [r.productId, r.available])) : null;
}

admin.get('/summary', wrap(async (_req, res) => {
  const [products, stock, orders, customers] = await Promise.all([
    call(svc.catalog, '/products?includeInactive=true&limit=200'),
    stockMap(),
    call(svc.order, '/admin/orders').catch(() => []),
    call(svc.user, '/users?limit=1').catch(() => ({ total: null })),
  ]);
  const today = new Date().toISOString().slice(0, 10);
  const confirmed = orders.filter((o) => o.status === 'CONFIRMED');
  res.json({
    products: products.total,
    hiddenProducts: products.items.filter((p) => !p.active).length,
    lowStock: stock ? products.items.filter((p) => p.active && (stock[p.id] ?? 0) < 10).length : null,
    outOfStock: stock ? products.items.filter((p) => p.active && (stock[p.id] ?? 0) === 0).length : null,
    ordersToday: orders.filter((o) => String(o.createdAt).startsWith(today)).length,
    revenueToday: confirmed.filter((o) => String(o.createdAt).startsWith(today)).reduce((s, o) => s + Number(o.totalAmount), 0),
    recentOrders: orders.length,
    customers: customers.total,
  });
}));

admin.get('/categories', wrap(async (_req, res) => res.json(await call(svc.catalog, '/categories?includeInactive=true'))));

admin.get('/products', wrap(async (req, res) => {
  const qs = new URLSearchParams({ includeInactive: 'true', limit: '200', sort: 'newest' });
  if (req.query.q) qs.set('q', req.query.q);
  const [{ items, total }, stock] = await Promise.all([call(svc.catalog, `/products?${qs}`), stockMap()]);
  res.json({ items: items.map((p) => ({ ...p, stock: stock ? stock[p.id] ?? 0 : null })), total });
}));

admin.post('/products', wrap(async (req, res) => {
  const product = await call(svc.catalog, '/products', { method: 'POST', body: productBody(req.body) });
  const initial = Number(req.body.initialStock ?? 0);
  let stock = null;
  try {
    stock = (await call(svc.inventory, `/stock/${product.id}`, { method: 'PUT', body: { available: Math.max(initial, 0) } })).available;
  } catch (err) {
    logger.error({ err: err.message, productId: product.id }, 'product created but stock could not be set');
  }
  adminChanges.inc({ action: 'product_create' });
  logger.info({ productId: product.id, by: req.user.email }, 'admin created product');
  res.status(201).json({ ...product, stock });
}));

admin.put('/products/:id', wrap(async (req, res) => {
  const product = await call(svc.catalog, `/products/${Number(req.params.id)}`, { method: 'PUT', body: productBody(req.body) });
  adminChanges.inc({ action: 'product_update' });
  logger.info({ productId: product.id, by: req.user.email }, 'admin updated product');
  res.json(product);
}));

admin.delete('/products/:id', wrap(async (req, res) => {
  const id = Number(req.params.id);
  await call(svc.catalog, `/products/${id}`, { method: 'DELETE' });
  await call(svc.inventory, `/stock/${id}`, { method: 'DELETE' }).catch((err) =>
    logger.warn({ err: err.message, productId: id }, 'product deleted but stock row remains'));
  adminChanges.inc({ action: 'product_delete' });
  logger.info({ productId: id, by: req.user.email }, 'admin deleted product');
  res.status(204).end();
}));

admin.put('/products/:id/image', express.raw({ type: 'image/*', limit: '3mb' }), wrap(async (req, res) => {
  if (!Buffer.isBuffer(req.body) || !req.body.length) throw new HttpError(400, 'choose a JPEG, PNG, WebP or GIF photo');
  const product = await callRaw(svc.catalog, `/products/${Number(req.params.id)}/image`, {
    method: 'PUT', body: req.body, contentType: req.get('content-type'),
  });
  adminChanges.inc({ action: 'photo_upload' });
  res.json(product);
}));

admin.delete('/products/:id/image', wrap(async (req, res) => {
  res.json(await call(svc.catalog, `/products/${Number(req.params.id)}/image`, { method: 'DELETE' }));
}));

admin.put('/stock/:id', wrap(async (req, res) => {
  const out = await call(svc.inventory, `/stock/${Number(req.params.id)}`, { method: 'PUT', body: { available: Number(req.body.available) } });
  adminChanges.inc({ action: 'stock_set' });
  res.json(out);
}));

admin.post('/stock/:id/adjust', wrap(async (req, res) => {
  const out = await call(svc.inventory, `/stock/${Number(req.params.id)}/adjust`, { method: 'POST', body: { delta: Number(req.body.delta) } });
  adminChanges.inc({ action: 'stock_adjust' });
  res.json(out);
}));

admin.get('/orders', wrap(async (_req, res) => res.json(await call(svc.order, '/admin/orders', { timeoutMs: 10000 }))));

admin.get('/customers', wrap(async (req, res) => {
  const qs = new URLSearchParams({ limit: String(Math.min(Number(req.query.limit) || 200, 500)) });
  if (req.query.q) qs.set('q', req.query.q);
  res.json(await call(svc.user, `/users?${qs}`));
}));

app.use('/api/admin', admin);
app.use('/api', api);
app.use('/api', (_req, res) => res.status(404).json({ error: 'not found' }));
app.use(express.static(path.join(__dirname, '..', 'public'), { maxAge: '5m' }));
app.get('/{*splat}', (_req, res) => res.sendFile(path.join(__dirname, '..', 'public', 'index.html')));

app.use((err, req, res, next) => {
  if (err instanceof HttpError) {
    if (err.status >= 500) logger.error({ err: err.message, uri: req.originalUrl }, 'upstream failure');
    return res.status(err.status).json({ error: err.message, ...(err.body && err.body.reason ? { reason: err.body.reason } : {}) });
  }
  return errorHandler(err, req, res, next);
});

const server = app.listen(PORT, () => logger.info({ port: PORT, upstreams: svc }, 'frontend listening'));
process.on('SIGTERM', () => server.close(() => process.exit(0)));
