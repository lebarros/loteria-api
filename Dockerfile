# ============================================================
# Multi-stage Dockerfile para Loteria API (Fly.io Free Tier 256MB)
# Otimizado: eclipse-temurin JRE + SerialGC + lazy init + heap pequeno
# Imagem final ~80MB
# ============================================================

# ---- Stage 1: Build com Gradle ----
FROM gradle:8.8-jdk21-alpine AS builder

WORKDIR /app

# Copia apenas arquivos de build primeiro (cache de dependências)
COPY build.gradle settings.gradle ./
COPY gradle/ gradle/

# Baixa dependências (cache layer)
RUN gradle dependencies --no-daemon

# Copia source code
COPY src/ src/

# Build da aplicação
RUN gradle bootJar --no-daemon -x test

# ---- Stage 2: Runtime (Eclipse Temurin JRE) ----
FROM eclipse-temurin:21-jre-alpine

# Usuário não-root (segurança)
RUN addgroup -g 1000 -S appgroup && \
    adduser -u 1000 -S appuser -G appgroup

WORKDIR /app

# Copia JAR do stage de build
COPY --from=builder /app/build/libs/*.jar app.jar

# Muda ownership
RUN chown -R appuser:appgroup /app

USER appuser

# JVM flags para 256MB container (Fly.io free tier)
# -Xms32m -Xmx128m: heap pequeno (SerialGC tem overhead mínimo)
# -XX:+UseSerialGC: GC single-threaded, MENOR overhead de memória (crítico p/ 256MB)
# -XX:TieredStopAtLevel=1: compila apenas C1 (client), economiza CPU/memória
# -XX:CICompilerCount=1: 1 thread de compilação
# -XX:-UseContainerSupport: desabilita detecção automática (evita overallocation)
# -XX:MaxRAM=200m: limita RAM vista pelo JVM (deixa 56MB para OS/overhead)
# -Djava.security.egd=file:/dev/./urandom: entropy rápido
ENV JAVA_OPTS="-Xms32m -Xmx128m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:CICompilerCount=1 -XX:-UseContainerSupport -XX:MaxRAM=200m -Djava.security.egd=file:/dev/./urandom"

# Timezone Brasil
ENV TZ=America/Sao_Paulo

# Porta da aplicação
EXPOSE 8080

# Health check - usa liveness probe (sem DB)
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=5 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/api/v1/actuator/health/liveness || exit 1

# Entry point
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]