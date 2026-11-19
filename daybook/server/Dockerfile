# Runtime image for the Daybook sync server.
#
# Build the distribution first, from the daybook/ folder:
#   ./gradlew :server:installDist
#   docker build -t daybook-server server
#
# CI publishes a ready-made image as ghcr.io/<owner>/daybook-server, so on a server you normally just
# pull it (see docker-compose.yml).
FROM eclipse-temurin:21-jre

RUN useradd --system --uid 10001 --home-dir /app daybook \
    && mkdir -p /data && chown daybook /data

COPY --chown=daybook build/install/daybook-server /app

USER daybook
ENV DAYBOOK_DB=/data/daybook.db \
    DAYBOOK_PORT=8080
VOLUME ["/data"]
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q ok <&3"]

ENTRYPOINT ["/app/bin/daybook-server"]
