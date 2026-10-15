# One Maven build shared by every Java service; pick the service with --target.
FROM maven:3.9.11-eclipse-temurin-21 AS build
# Optional, for builds behind a proxy: e.g. -Dhttps.proxyHost=... and a truststore passed as the
# "truststore" build secret (-Djavax.net.ssl.trustStore=/run/secrets/truststore).
ARG MAVEN_OPTS=""
WORKDIR /src
COPY pom.xml ./
COPY ads-common ads-common
COPY ingest-gateway ingest-gateway
COPY event-simulator event-simulator
COPY stream-processor stream-processor
COPY openrtb openrtb
COPY mock-dsp mock-dsp
COPY ad-server ad-server
COPY pacing-service pacing-service
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=secret,id=truststore,required=false \
    MAVEN_OPTS="$MAVEN_OPTS" mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre AS runtime-base
RUN useradd --system --uid 10001 app
USER app
WORKDIR /app
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -XX:+ZGenerational"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

FROM runtime-base AS gateway
COPY --from=build /src/ingest-gateway/target/ingest-gateway.jar /app/app.jar
EXPOSE 8080

FROM runtime-base AS simulator
COPY --from=build /src/event-simulator/target/event-simulator.jar /app/app.jar

FROM runtime-base AS ad-server
COPY --from=build /src/ad-server/target/ad-server.jar /app/app.jar
EXPOSE 8090

FROM runtime-base AS pacing-service
COPY --from=build /src/pacing-service/target/pacing-service.jar /app/app.jar
EXPOSE 8095

FROM runtime-base AS mock-dsp
COPY --from=build /src/mock-dsp/target/mock-dsp.jar /app/app.jar
EXPOSE 9000

# Flink application image: the job jar in usrlib is picked up by "standalone-job" mode.
FROM flink:2.2.1-java21 AS stream-processor
# Mount point for checkpoints; a named volume inherits this ownership (Flink runs as "flink").
USER root
RUN mkdir -p /flink-data && chown flink:flink /flink-data
USER flink
COPY --from=build --chown=flink:flink /src/stream-processor/target/stream-processor.jar /opt/flink/usrlib/stream-processor.jar
