# syntax=docker/dockerfile:1
# Daftari server — hostless / Northflank / Render / Koyeb / any Docker host. Build context = this folder (server/).
#   docker build -t daftari-server .   →   docker run -p 8080:8080 -e DATABASE_URL=… daftari-server
# 1.2.1: package-lock.json + npm ci (same as the Madyan Panel image that deploys on hostless) — the exact same
# dependency tree every build, no resolving of ^ ranges on the build machine.
FROM node:22-bookworm-slim AS build
WORKDIR /app
ENV NPM_CONFIG_UPDATE_NOTIFIER=false
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY tsconfig.json ./
COPY src ./src
RUN npm run build \
 && npm prune --omit=dev --no-audit --no-fund \
 && npm cache clean --force

FROM node:22-bookworm-slim
# HOST 0.0.0.0 + PORT from the platform (an empty or bad PORT falls back to 8080).
# State: DATABASE_URL (Postgres, e.g. free Neon) — the disk is wiped on every redeploy.
ENV NODE_ENV=production \
    HOST=0.0.0.0 \
    PORT=8080 \
    NPM_CONFIG_UPDATE_NOTIFIER=false
WORKDIR /app
COPY --from=build --chown=node:node /app/node_modules ./node_modules
COPY --from=build --chown=node:node /app/dist ./dist
COPY --chown=node:node package.json ./
USER node:node
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:'+(process.env.PORT||8080)+'/health').then(r=>process.exit(r.ok?0:1),()=>process.exit(1))"
CMD ["node", "dist/src/main.js"]
