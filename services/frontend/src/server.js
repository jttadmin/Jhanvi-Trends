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
    req.user = { id: claims.sub, name: claims.name, email: claims.email };
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
