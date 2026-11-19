FROM oven/bun:1-slim
WORKDIR /app
COPY package.json tsconfig.json ./
COPY src ./src
RUN mkdir -p /app/data && chown bun:bun /app/data
USER bun
ENV PORT=8787 DB_PATH=/app/data/sanefeed.sqlite
VOLUME /app/data
EXPOSE 8787
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD ["bun", "-e", "fetch('http://127.0.0.1:' + (process.env.PORT || 8787) + '/healthz').then(r => process.exit(r.ok ? 0 : 1), () => process.exit(1))"]
CMD ["bun", "run", "src/index.ts"]
