# BookSound server image. Build the server first (the Android build needs its SDK, so it is not built in
# here), then the image:
#
#   ./gradlew :server:installDist
#   docker build -f deploy/Dockerfile -t booksound-server .
#
# CI publishes it as ghcr.io/hutao1love/booksound-server for amd64 and arm64. Torrents need the amd64
# image: libtorrent4j has no arm64 Linux build, so on arm64 the server runs without them.
FROM eclipse-temurin:25-jre

RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 1001 booksound \
 && useradd --system --uid 1001 --gid booksound --home-dir /data --shell /usr/sbin/nologin booksound \
 && mkdir -p /data && chown booksound:booksound /data

COPY server/build/install/booksound-server /opt/booksound

ENV BOOKSOUND_DATA=/data \
    BOOKSOUND_PORT=8080

USER booksound
VOLUME ["/data"]
# 8080: web app and API. 6881: incoming torrent connections (optional to forward).
EXPOSE 8080 6881/tcp 6881/udp
HEALTHCHECK --interval=60s --timeout=5s --start-period=60s CMD curl -fsS http://localhost:${BOOKSOUND_PORT}/api/status || exit 1

ENTRYPOINT ["/opt/booksound/bin/booksound-server"]
