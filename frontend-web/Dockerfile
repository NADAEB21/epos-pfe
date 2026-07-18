# syntax=docker/dockerfile:1
# Builds the Angular dashboard and bakes it into the Caddy edge image.
# Build context is the repo root (compose sets `context: ..`) so this can pull
# in infrastructure/caddy/Caddyfile alongside the frontend sources.

# ---- build stage ----
FROM node:20-alpine AS build
WORKDIR /app

# Dependencies first so a source-only change reuses the npm layer.
COPY frontend-web/package.json frontend-web/package-lock.json ./
RUN npm ci

COPY frontend-web/ ./

# The default production config enables the ngsw service worker and applies the
# bundle budgets from angular.json. Node's default heap is not enough for this
# build on a 4 GiB instance.
ENV NODE_OPTIONS=--max-old-space-size=2048
RUN npx ng build --configuration production

# ---- runtime stage ----
FROM caddy:2-alpine
COPY infrastructure/caddy/Caddyfile /etc/caddy/Caddyfile
# Angular 18's `application` builder emits browser assets under dist/<project>/browser.
COPY --from=build /app/dist/frontend-web/browser /srv
