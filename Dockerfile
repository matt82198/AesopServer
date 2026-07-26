# Multi-stage Dockerfile for AesopServer
# Stage 1: Build
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build
COPY . .
RUN ./mvnw -q clean package -DskipTests

# Stage 2: Runtime (layered JAR)
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Copy the layered JAR from builder
COPY --from=builder /build/target/aesop-server.jar application.jar

# Extract layers for efficient caching
RUN java -Djarmode=layertools -jar application.jar extract --destination extracted

# Stage 3: Final image
FROM eclipse-temurin:21-jre

# Set non-root user
RUN useradd -m -u 1000 aesop

WORKDIR /app

# Copy extracted layers (most frequently changing last for cache efficiency)
COPY --from=runtime --chown=aesop:aesop /app/extracted/dependencies/ ./
COPY --from=runtime --chown=aesop:aesop /app/extracted/spring-boot-loader/ ./
COPY --from=runtime --chown=aesop:aesop /app/extracted/snapshot-dependencies/ ./
COPY --from=runtime --chown=aesop:aesop /app/extracted/application/ ./

USER aesop

# Health check: curl the actuator/health endpoint
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD java -version 2>&1 | grep -q "openjdk" || exit 1

EXPOSE 8870

# Start the application
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
