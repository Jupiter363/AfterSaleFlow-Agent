FROM public.ecr.aws/docker/library/node:22-alpine AS build
WORKDIR /app
ENV COREPACK_HOME=/opt/corepack

ARG VITE_API_BASE_URL=/api
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN corepack enable \
    && corepack prepare pnpm@11.7.0 --activate \
    && pnpm install --frozen-lockfile

COPY . .
RUN pnpm run build

FROM public.ecr.aws/docker/library/node:22-alpine
WORKDIR /app
ENV NODE_ENV=production

COPY --from=build --chown=node:node /app/dist ./dist
COPY --chown=node:node server.mjs ./

USER node
EXPOSE 5173
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=10 \
  CMD wget -qO- http://127.0.0.1:5173/healthz | grep ok
CMD ["node", "server.mjs"]
