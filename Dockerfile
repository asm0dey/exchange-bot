FROM gradle:9.6.1-jdk21 AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/install/exchange-bot /app
# Non-root: least privilege for a process that only needs to talk to Telegram and
# read/write its own data directory. TZ=UTC: closes a known timestamp-zone edge
# case — every stored timestamp and expiry calculation is meant to be UTC, and a
# container without this pins the JVM default zone to whatever the host image ships.
RUN useradd --create-home --uid 10001 --shell /usr/sbin/nologin exchange-bot \
    && mkdir -p /app/data \
    && chown -R exchange-bot:exchange-bot /app
USER exchange-bot
VOLUME ["/app/data"]
ENV DB_PATH=/app/data/exchange
ENV TZ=UTC
ENTRYPOINT ["/app/bin/exchange-bot"]
