# syntax=docker/dockerfile:1
#
# Multi-stage build for the load balancer.
#
# Stage 1 resolves dependencies in its own layer so that editing source code does not
# re-download the Maven world on every build. Stage 2 carries only a JRE and the jar.
#
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependency layer: copy only the POMs, then go offline. This layer is cached until a
# POM changes, which is what keeps an incremental image build to a few seconds.
COPY pom.xml .
COPY load-balancer/pom.xml load-balancer/pom.xml
COPY demo-backend/pom.xml demo-backend/pom.xml
RUN mvn -B -pl load-balancer -am dependency:go-offline -DskipTests

COPY load-balancer/src load-balancer/src
RUN mvn -B -pl load-balancer -am package -DskipTests

# ---------------------------------------------------------------------------

FROM eclipse-temurin:21-jre-alpine AS runtime

# Run unprivileged. A reverse proxy is directly internet-facing; a container escape from a
# root process is a host compromise, whereas from this user it is not.
RUN addgroup -S alb && adduser -S -G alb alb

WORKDIR /app
COPY --from=build /build/load-balancer/target/load-balancer-*.jar app.jar
RUN chown -R alb:alb /app
USER alb

EXPOSE 8080

ENV JAVA_OPTS="\
  -XX:MaxRAMPercentage=75 \
  -XX:+UseZGC \
  -XX:+ExitOnOutOfMemoryError \
  -Djava.security.egd=file:/dev/./urandom"

# MaxRAMPercentage, not -Xmx: the heap follows the container limit, so changing the memory
#   limit in compose or Kubernetes does not silently leave the JVM sized for the old one.
# ZGC: sub-millisecond pauses. For a proxy, GC pause time lands directly in client-visible
#   p99 latency and, worse, can stall health-check responses long enough to look like an outage.
# ExitOnOutOfMemoryError: a proxy that has exhausted its heap cannot serve. Dying lets the
#   orchestrator replace it and lets the upstream health check route around it, which is far
#   better than limping along failing requests unpredictably.

ENV SPRING_PROFILES_ACTIVE=json-logs

# Container health check hits the ALB's own readiness, not a backend's.
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
  CMD wget -q -O /dev/null http://localhost:8080/actuator/health/readiness || exit 1

# exec form via sh -c so JAVA_OPTS is expanded, while the JVM still receives SIGTERM as PID 1
# and can run its graceful shutdown.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
