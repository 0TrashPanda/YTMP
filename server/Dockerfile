# YTMP server + web app. Build from the repo root:
#   docker build -f server/Dockerfile -t ytmp .

FROM node:24-alpine AS frontend
RUN corepack enable
WORKDIR /src/frontend
COPY frontend/package.json frontend/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY frontend/ ./
RUN pnpm build

FROM eclipse-temurin:21-jdk AS server
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY protocol protocol
COPY core core
COPY cast cast
COPY host host
COPY server server
RUN ./gradlew :server:installDist --no-daemon -q

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=server /src/server/build/install/server /app
COPY --from=frontend /src/frontend/build /app/frontend/build
RUN useradd --system ytmp && mkdir -p /var/lib/ytmp && chown ytmp /var/lib/ytmp
USER ytmp
ENV YTMP_CONFIG=/config/ytmp.toml
ENV YTMP_DB_PATH=/var/lib/ytmp/ytmp.db
VOLUME /var/lib/ytmp
EXPOSE 8080
ENTRYPOINT ["/app/bin/server"]
