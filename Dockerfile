# syntax=docker/dockerfile:1.7
# AezGram server image: the Ktor server, the web client and (when CI built it) the unsigned Android APK, so one
# pinned digest describes everything a phone or browser receives. The entrypoint signs the APK with a key that
# exists only on the server (deploy/entrypoint.sh). Built by .github/workflows/aezgram.yml.

FROM eclipse-temurin:21-jdk AS server
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY shared shared
COPY server server
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q :server:installDist

FROM node:22-bookworm-slim AS web
WORKDIR /src/web
COPY web/package.json web/package-lock.json web/.npmrc ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY web ./
RUN npm run build

FROM eclipse-temurin:21-jre
ARG AEZGRAM_VERSION=dev
ENV AEZGRAM_VERSION=$AEZGRAM_VERSION \
    AEZGRAM_DATA_DIR=/data \
    AEZGRAM_WEB_DIR=/app/web \
    AEZGRAM_APK_DIR=/data/apk
RUN useradd --system --uid 10001 --home-dir /data --shell /usr/sbin/nologin aezgram \
 && mkdir -p /data && chown aezgram:aezgram /data
COPY --from=server /src/server/build/install/server /app/server
COPY --from=web /src/web/dist /app/web
# CI drops aezgram-unsigned.apk, apksigner.jar and build.env here before building; locally it's empty and the
# download link says so.
COPY deploy/apk/ /app/apk/
COPY deploy/entrypoint.sh /app/entrypoint.sh
USER aezgram
EXPOSE 8080
VOLUME /data
ENTRYPOINT ["/app/entrypoint.sh"]
