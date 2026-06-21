# ==============================================================================
# Dockerfile — multi-stage build producing a small, production-ready image.
# ==============================================================================
# WHY MULTI-STAGE (two FROM blocks below) INSTEAD OF ONE?
#   Stage 1 ("build") needs the full Maven + JDK toolchain to COMPILE our
#   source code — that toolchain alone is several hundred MB and is utterly
#   useless at runtime (nobody needs a compiler to RUN an already-compiled
#   .jar). Stage 2 ("runtime") starts from a minimal JRE-only base image and
#   copies ONLY the final built .jar out of stage 1. The build tools never
#   end up in the final image at all, which means:
#     - Smaller image (faster deploys, less storage/bandwidth cost).
#     - Smaller attack surface (no compiler/build tooling available inside
#       a running production container for an attacker to abuse if they
#       ever gained shell access).
# ==============================================================================

# ---------- Stage 1: build the application ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy ONLY the pom.xml first and run a dependency download as its own
# Docker layer. WHY: Docker caches each instruction as a layer; as long as
# pom.xml doesn't change between builds, this (slow) dependency-download
# layer is reused from cache on every subsequent build, even if we change
# our Java source code — dramatically speeding up iterative local builds.
COPY pom.xml .
RUN mvn -B dependency:go-offline

# Now copy the actual source code and build the executable jar.
COPY src ./src
RUN mvn -B clean package -DskipTests
# -DskipTests: tests already ran in CI before this Docker build step in a
# real pipeline; skipping them here keeps the image build fast and focused
# purely on producing the artifact (see README's CI/CD section).

# ---------- Stage 2: run the application ----------
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Run as a non-root user — a basic container security best practice so
# that even if an attacker exploited a vulnerability inside the running
# application, they would not automatically have root privileges inside
# the container.
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Copy ONLY the final built jar from the build stage — none of the Maven
# cache, source code, or JDK compiler tooling are present in this final
# image at all.
COPY --from=build /app/target/url-shortener.jar app.jar

EXPOSE 8080

# Use the "exec form" (JSON array) rather than the "shell form" so the JVM
# process becomes PID 1 directly and correctly receives OS signals (like
# SIGTERM on `docker stop`) for graceful shutdown, instead of an extra
# shell process sitting in between swallowing them.
ENTRYPOINT ["java", "-jar", "app.jar"]
