FROM oven/bun:1-slim
WORKDIR /app
COPY package.json tsconfig.json ./
COPY src ./src
RUN mkdir -p /app/data && chown bun:bun /app/data
USER bun
ENV PORT=8787 DB_PATH=/app/data/sanefeed.sqlite
VOLUME /app/data
EXPOSE 8787
CMD ["bun", "run", "src/index.ts"]
