# syntax=docker/dockerfile:1
#
# Demo backend image. Not production code — it exists so load balancer behaviour is visible.
#
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
COPY load-balancer/pom.xml load-balancer/pom.xml
COPY demo-backend/pom.xml demo-backend/pom.xml
RUN mvn -B -pl demo-backend -am dependency:go-offline -DskipTests

COPY demo-backend/src demo-backend/src
RUN mvn -B -pl demo-backend -am package -DskipTests

# ---------------------------------------------------------------------------

FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build /build/demo-backend/target/demo-backend-*.jar app.jar
RUN chown -R app:app /app
USER app

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
ENV SERVER_PORT=8080

HEALTHCHECK --interval=5s --timeout=3s --start-period=20s --retries=3 \
  CMD wget -q -O /dev/null http://localhost:8080/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
