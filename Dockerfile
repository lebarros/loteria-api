# ============================================================
# Multi-stage Docker build para Loteria API
# RNF06: JVM tuning para 512MB (-Xmx350m -Xss256k)
# ============================================================

# ---- Stage 1: Build ----
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Copia arquivos de build
COPY build.gradle settings.gradle ./
COPY gradle gradle
COPY gradlew ./

# Baixa dependências (cache layer)
RUN ./gradlew dependencies --no-daemon

# Copia código fonte
COPY src src

# Build da aplicação (sem testes para acelerar)
RUN ./gradlew bootJar --no-daemon -x test

# ---- Stage 2: Runtime ----
FROM eclipse-temurin:21-jre-alpine

# Instala curl para healthcheck
RUN apk add --no-cache curl

# Usuário não-root (segurança)
RUN addgroup -g 1000 -S appgroup && \
    adduser -u 1000 -S appuser -G appgroup

WORKDIR /app

# Copia JAR do stage de build
COPY --from=builder /app/build/libs/*.jar app.jar

# Muda ownership
RUN chown appuser:appgroup app.jar

USER appuser

# JVM tuning para container com limite de 512MB (RNF06)
ENV JAVA_OPTS="-Xmx350m -Xss256k -XX:+UseContainerSupport -XX:MaxRAMPercentage=70 -XX:+UseZGC -Djava.security.egd=file:/dev/./urandom"
ENV SPRING_PROFILES_ACTIVE=prod
ENV TZ=America/Sao_Paulo

EXPOSE 8080

# Healthcheck
HEALTHCHECK --interval=30s --timeout=10s --start-period=20s --retries=3 \
    CMD curl -f http://localhost:8080/api/v1/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]