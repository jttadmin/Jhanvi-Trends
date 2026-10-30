// Shared observability helpers: JSON logs (pino, trace_id injected by OTel),
// Prometheus RED metrics, and health endpoints.
// Traces come from @opentelemetry/auto-instrumentations-node, preloaded with
// `node --require @opentelemetry/auto-instrumentations-node/register`.
const pino = require('pino');
const client = require('prom-client');

const serviceName = process.env.OTEL_SERVICE_NAME || 'unknown-service';

const logger = pino({
  level: process.env.LOG_LEVEL || 'info',
  base: { service: serviceName },
  timestamp: pino.stdTimeFunctions.isoTime,
  formatters: { level: (label) => ({ level: label }) },
});

const register = new client.Registry();
client.collectDefaultMetrics({ register });

// Same metric name and labels in every Jhanvi Trends service, so one Grafana
// dashboard covers all languages.
const httpDuration = new client.Histogram({
  name: 'http_server_requests_seconds',
  help: 'HTTP server request duration in seconds',
  labelNames: ['method', 'uri', 'status'],
  buckets: [0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5],
  registers: [register],
});

function metricsMiddleware(req, res, next) {
  if (req.path === '/metrics' || req.path === '/healthz' || req.path === '/readyz') return next();
  const end = httpDuration.startTimer();
  res.on('finish', () => {
    const uri = req.route ? (res.locals.baseUrl ?? req.baseUrl ?? '') + req.route.path : 'UNMATCHED';
    end({ method: req.method, uri, status: String(res.statusCode) });
    const lvl = res.statusCode >= 500 ? 'error' : 'info';
    logger[lvl]({ method: req.method, uri, status: res.statusCode }, 'request completed');
  });
  next();
}

function mountOps(app, readyCheck = async () => true) {
  app.get('/metrics', async (_req, res) => {
    res.set('Content-Type', register.contentType);
    res.end(await register.metrics());
  });
  app.get('/healthz', (_req, res) => res.json({ status: 'ok' }));
  app.get('/readyz', async (_req, res) => {
    try {
      await readyCheck();
      res.json({ status: 'ready' });
    } catch (err) {
      res.status(503).json({ status: 'not ready', error: err.message });
    }
  });
}

function errorHandler(err, req, res, _next) {
  logger.error({ err, uri: req.originalUrl }, 'unhandled error');
  res.status(err.status || 500).json({ error: err.expose ? err.message : 'internal error' });
}

module.exports = { logger, register, client, metricsMiddleware, mountOps, errorHandler };
