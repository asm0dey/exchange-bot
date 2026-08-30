FROM gradle:9.6.1-jdk21 AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/install/exchange-bot /app
VOLUME ["/app/data"]
ENV DB_PATH=/app/data/exchange
ENTRYPOINT ["/app/bin/exchange-bot"]
