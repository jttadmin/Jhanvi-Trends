// user-service: registration, login (JWT) and profile lookup. Postgres-backed.
const express = require('express');
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const { Pool } = require('pg');
const { logger, client, register, metricsMiddleware, mountOps, errorHandler } = require('./obs');

const PORT = Number(process.env.PORT || 8080);
const JWT_SECRET = process.env.JWT_SECRET || 'dev-secret-change-me';
// Accounts whose email is listed here get the "admin" role (comma-separated, case-insensitive).
const ADMIN_EMAILS = new Set((process.env.ADMIN_EMAILS || '').split(',').map((e) => e.trim().toLowerCase()).filter(Boolean));
const roleOf = (u) => (ADMIN_EMAILS.has(String(u.email).toLowerCase()) ? 'admin' : 'customer');
const pool = new Pool({
  host: process.env.PGHOST || 'localhost',
  port: Number(process.env.PGPORT || 5432),
  user: process.env.PGUSER || 'jhanvitrends',
  password: process.env.PGPASSWORD || 'jhanvitrends',
  database: process.env.PGDATABASE || 'users',
  max: 10,
});

const signups = new client.Counter({ name: 'user_signups_total', help: 'Registered users', registers: [register] });
const logins = new client.Counter({
  name: 'user_logins_total', help: 'Login attempts', labelNames: ['result'], registers: [register],
});

async function migrate() {
  for (let attempt = 1; ; attempt++) {
    try {
      await pool.query(`CREATE TABLE IF NOT EXISTS users (
        id SERIAL PRIMARY KEY,
        name TEXT NOT NULL,
        email TEXT NOT NULL UNIQUE,
        password_hash TEXT NOT NULL,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now())`);
      logger.info('database ready');
      return;
    } catch (err) {
      logger.warn({ attempt, err: err.message }, 'database not ready, retrying');
      await new Promise((r) => setTimeout(r, Math.min(attempt, 10) * 1000));
    }
  }
}

const issueToken = (u) => jwt.sign({ sub: String(u.id), name: u.name, email: u.email, role: roleOf(u) }, JWT_SECRET, { expiresIn: '24h' });
const publicUser = (u) => ({ id: u.id, name: u.name, email: u.email, role: roleOf(u), createdAt: u.created_at });

const app = express();
app.use(express.json());
app.use(metricsMiddleware);
mountOps(app, () => pool.query('SELECT 1'));

app.post('/register', async (req, res, next) => {
  try {
    const { name, email, password } = req.body || {};
    if (!name || !email || !password || password.length < 6) {
      return res.status(400).json({ error: 'name, email and a password of 6+ characters are required' });
    }
    const hash = await bcrypt.hash(password, 10);
    const { rows } = await pool.query(
      'INSERT INTO users (name, email, password_hash) VALUES ($1, lower($2), $3) RETURNING *',
      [name.trim(), email.trim(), hash],
    );
    signups.inc();
    logger.info({ userId: rows[0].id }, 'user registered');
    res.status(201).json({ token: issueToken(rows[0]), user: publicUser(rows[0]) });
  } catch (err) {
    if (err.code === '23505') return res.status(409).json({ error: 'email already registered' });
    next(err);
  }
});

app.post('/login', async (req, res, next) => {
  try {
    const { email, password } = req.body || {};
    const { rows } = await pool.query('SELECT * FROM users WHERE email = lower($1)', [email || '']);
    const user = rows[0];
    if (!user || !(await bcrypt.compare(password || '', user.password_hash))) {
      logins.inc({ result: 'failure' });
      return res.status(401).json({ error: 'invalid email or password' });
    }
    logins.inc({ result: 'success' });
    res.json({ token: issueToken(user), user: publicUser(user) });
  } catch (err) {
    next(err);
  }
});

// Customer list for the admin panel. Cluster-internal: the frontend BFF checks the admin role.
app.get('/users', async (req, res, next) => {
  try {
    const limit = Math.min(Math.max(Number(req.query.limit) || 100, 1), 500);
    const offset = Math.max(Number(req.query.offset) || 0, 0);
    const q = (req.query.q || '').trim();
    const where = q ? 'WHERE name ILIKE $3 OR email ILIKE $3' : '';
    const args = q ? [limit, offset, `%${q}%`] : [limit, offset];
    const { rows } = await pool.query(`SELECT * FROM users ${where} ORDER BY created_at DESC LIMIT $1 OFFSET $2`, args);
    const total = await pool.query(`SELECT count(*)::int AS n FROM users ${q ? 'WHERE name ILIKE $1 OR email ILIKE $1' : ''}`, q ? [`%${q}%`] : []);
    res.json({ items: rows.map(publicUser), total: total.rows[0].n });
  } catch (err) {
    next(err);
  }
});

app.get('/users/:id', async (req, res, next) => {
  try {
    const { rows } = await pool.query('SELECT * FROM users WHERE id = $1', [Number(req.params.id) || 0]);
    if (!rows[0]) return res.status(404).json({ error: 'user not found' });
    res.json(publicUser(rows[0]));
  } catch (err) {
    next(err);
  }
});

app.use(errorHandler);

migrate().then(() => {
  const server = app.listen(PORT, () => logger.info({ port: PORT }, 'user-service listening'));
  process.on('SIGTERM', () => server.close(() => pool.end().then(() => process.exit(0))));
});
