FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
# Optional "cacert" build secret: extra CA bundle for builds behind a TLS-inspecting proxy.
RUN --mount=type=secret,id=cacert,required=false \
    if [ -f /run/secrets/cacert ]; then export NODE_EXTRA_CA_CERTS=/run/secrets/cacert; fi; \
    npm ci --ignore-scripts
COPY . .
RUN npx nuxt prepare && npm run build

FROM node:22-alpine
WORKDIR /app
COPY --from=build /app/.output ./.output
# The password-hash tool, for creating NUXT_AUTH_USERS entries without Node installed locally.
COPY --from=build /app/scripts/hash-password.ts ./tools/scripts/
COPY --from=build /app/server/utils/auth.ts ./tools/server/utils/
ENV HOST=0.0.0.0 PORT=3000 NODE_ENV=production
# Push subscriptions live here; a volume mounted on it inherits this ownership.
RUN mkdir -p /app/.data && chown node:node /app/.data
USER node
EXPOSE 3000
CMD ["node", ".output/server/index.mjs"]
