FROM node:22-alpine AS deps
WORKDIR /app
COPY package*.json ./
RUN npm install --omit=dev --no-audit --no-fund

FROM node:22-alpine
ENV NODE_ENV=production \
    NODE_OPTIONS="--require @opentelemetry/auto-instrumentations-node/register"
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
USER 1000
EXPOSE 8080
CMD ["node", "src/server.js"]
