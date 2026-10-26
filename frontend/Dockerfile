FROM node:22.22.1-alpine3.23@sha256:8094c002d08262dba12645a3b4a15cd6cd627d30bc782f53229a2ec13ee22a00 AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM node:22.22.1-alpine3.23@sha256:8094c002d08262dba12645a3b4a15cd6cd627d30bc782f53229a2ec13ee22a00
WORKDIR /app
ENV NODE_ENV=production API_BASE_URL=/api MOCK_API=false PORT=3000
COPY --from=build --chown=node:node /app/dist ./dist
COPY --chown=node:node server.mjs ./server.mjs
COPY --chown=node:node THIRD_PARTY_NOTICES ./THIRD_PARTY_NOTICES
USER node
EXPOSE 3000
CMD ["node", "server.mjs"]
