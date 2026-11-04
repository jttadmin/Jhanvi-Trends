# Stages: test (docker build --target test .) | deps -> runtime (default target)
FROM node:22-alpine AS deps
WORKDIR /app
COPY package*.json ./
# npm ci installs exactly what package-lock.json pins (npm install could drift).
RUN npm ci --omit=dev --no-audit --no-fund

# Syntax check of every source file, then the "test" script once package.json has one.
# CI runs this before building the image.
FROM node:22-alpine AS test
WORKDIR /app
COPY package*.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN find src -name '*.js' -print0 | xargs -0 -n1 node --check && \
    npm test --if-present

FROM node:22-alpine
ENV NODE_ENV=production \
    NODE_OPTIONS="--require @opentelemetry/auto-instrumentations-node/register"
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
USER 1000
EXPOSE 8080
CMD ["node", "src/server.js"]
