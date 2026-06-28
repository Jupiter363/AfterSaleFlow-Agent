FROM public.ecr.aws/docker/library/node:22-alpine
WORKDIR /app
ENV COREPACK_HOME=/opt/corepack

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN corepack enable \
  && corepack prepare pnpm@11.7.0 --activate \
  && pnpm install --frozen-lockfile \
  && chown -R node:node "$COREPACK_HOME"

COPY --chown=node:node . .
RUN chown -R node:node /app
USER node
EXPOSE 5173
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=10 \
  CMD wget -qO- http://127.0.0.1:5173/ >/dev/null || exit 1
CMD ["pnpm", "run", "dev"]
