// payment-service: mock payment gateway with chaos knobs (failure rate and
// latency) so you can watch errors and slow spans light up in Grafana/Tempo.
const express = require('express');
const crypto = require('crypto');
const { logger, client, register, metricsMiddleware, mountOps, errorHandler } = require('./obs');

const PORT = Number(process.env.PORT || 8080);
const chaos = {
  failureRate: Number(process.env.PAYMENT_FAILURE_RATE || 0.05), // 0..1
  latencyMs: Number(process.env.PAYMENT_LATENCY_MS || 150),       // mean added latency
};

const payments = new client.Counter({
  name: 'payments_total', help: 'Payment attempts by result', labelNames: ['status'], registers: [register],
});
const amount = new client.Counter({
  name: 'payments_amount_total', help: 'Captured payment amount', labelNames: ['currency'], registers: [register],
});

const app = express();
app.use(express.json());
app.use(metricsMiddleware);
mountOps(app);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

app.post('/payments', async (req, res) => {
  const { orderRef, userId, amount: value, currency = 'INR' } = req.body || {};
  if (!orderRef || !userId || !(Number(value) > 0)) {
    return res.status(400).json({ error: 'orderRef, userId and a positive amount are required' });
  }
  // Jittered latency: 50%..150% of the configured mean.
  await sleep(chaos.latencyMs * (0.5 + Math.random()));
  const paymentId = `pay_${crypto.randomUUID().replace(/-/g, '').slice(0, 20)}`;
  if (Math.random() < chaos.failureRate) {
    payments.inc({ status: 'declined' });
    logger.warn({ orderRef, userId, amount: value }, 'payment declined by issuer');
    return res.status(402).json({ paymentId, status: 'declined', reason: 'card_declined' });
  }
  payments.inc({ status: 'captured' });
  amount.inc({ currency }, Number(value));
  logger.info({ orderRef, userId, amount: value, paymentId }, 'payment captured');
  res.status(201).json({ paymentId, status: 'captured', amount: Number(value), currency });
});

// Runtime chaos control (cluster-internal only; not exposed by the ingress).
app.get('/chaos', (_req, res) => res.json(chaos));
app.post('/chaos', (req, res) => {
  const { failureRate, latencyMs } = req.body || {};
  if (failureRate !== undefined) chaos.failureRate = Math.min(Math.max(Number(failureRate), 0), 1);
  if (latencyMs !== undefined) chaos.latencyMs = Math.max(Number(latencyMs), 0);
  logger.warn({ chaos }, 'chaos settings changed');
  res.json(chaos);
});

app.use(errorHandler);
const server = app.listen(PORT, () => logger.info({ port: PORT, chaos }, 'payment-service listening'));
process.on('SIGTERM', () => server.close(() => process.exit(0)));
