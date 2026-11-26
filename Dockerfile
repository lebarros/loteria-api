# ============================================================
# Multi-stage Dockerfile para Loteria API (Fly.io Free Tier 256MB)
# Otimizado: distroless + ZGC + lazy init + heap pequeno
# Imagem final ~45MB (vs ~150MB alpine)
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

# Build da aplicação (sem testes para speed)
RUN gradle bootJar --no-daemon -x test

# ---- Stage 2: Runtime (Distroless - menor, mais seguro) ----
# gcr.io/distroless/java21-debian12:nonroot = ~45MB, sem shell, sem package manager
FROM gcr.io/distroless/java21-debian12:nonroot

WORKDIR /app

# Copia JAR do stage de build
COPY --from=builder /app/build/libs/*.jar app.jar

# Nonroot user (UID 65532 no distroless)
USER nonroot:nonroot

# JVM flags para 256MB container (Fly.io free tier)
# -Xms48m -Xmx180m: heap fixo pequeno (evita expansão)
# -XX:+UseZGC: ZGC low-latency, funciona bem em 256MB
# -XX:TieredStopAtLevel=1: compila apenas C1 (client), economiza CPU/memória
# -XX:CICompilerCount=1: 1 thread de compilação
# -Djava.security.egd=file:/dev/./urandom: entropy rápido
ENV JAVA_OPTS="-Xms48m -Xmx180m -XX:MaxRAMPercentage=70 -XX:+UseZGC -XX:TieredStopAtLevel=1 -XX:CICompilerCount=1 -Djava.security.egd=file:/dev/./urandom"

# Timezone Brasil
ENV TZ=America/Sao_Paulo

# Porta da aplicação
EXPOSE 8080

# Health check - usa wget do distroless (tem busybox)
# grace_period no fly.toml cuida do cold start
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/api/v1/actuator/health || exit 1

# Entry point (distroless não tem shell, usa exec form)
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+UseZGC", "-XX:TieredStopAtLevel=1", "-XX:CICompilerCount=1", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]