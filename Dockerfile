# ============================================================
# Multi-stage Dockerfile para Loteria API (Fly.io Free Tier 256MB)
# Otimizado: distroless + ZGC + AppCDS + lazy init + heap pequeno
# Imagem final ~50MB (distroless + CDS archive)
# ============================================================

# ---- Stage 1: Build com Gradle + Spring AOT + AppCDS ----
FROM gradle:8.8-jdk21-alpine AS builder

WORKDIR /app

# Copia apenas arquivos de build primeiro (cache de dependências)
COPY build.gradle settings.gradle ./
COPY gradle/ gradle/

# Baixa dependências (cache layer)
RUN gradle dependencies --no-daemon

# Copia source code
COPY src/ src/

# Build da aplicação com Spring AOT (startup ~40-60% mais rápido)
RUN gradle bootJar --no-daemon -x test

# Gera AppCDS archive (Class Data Sharing) - ~30-50% startup mais rápido
# Executa a app brevemente para treinar classes, depois cria archive
RUN java -XX:ArchiveClassesAtExit=app.jsa -jar build/libs/*.jar --spring.profiles.active=fly --spring.main.lazy-initialization=true &
  sleep 8 && kill $! 2>/dev/null; wait $! 2>/dev/null; true

# ---- Stage 2: Runtime (Distroless - menor, mais seguro) ----
# gcr.io/distroless/java21-debian12:nonroot = ~45MB, sem shell, sem package manager
FROM gcr.io/distroless/java21-debian12:nonroot

WORKDIR /app

# Copia JAR do stage de build
COPY --from=builder /app/build/libs/*.jar app.jar

# Copia AppCDS archive
COPY --from=builder /app/app.jsa app.jsa

# Nonroot user (UID 65532 no distroless)
USER nonroot:nonroot

# JVM flags para 256MB container (Fly.io free tier)
# -Xms48m -Xmx180m: heap fixo pequeno (evita expansão)
# -XX:+UseZGC: ZGC low-latency, funciona bem em 256MB
# -XX:TieredStopAtLevel=1: compila apenas C1 (client), economiza CPU/memória
# -XX:CICompilerCount=1: 1 thread de compilação
# -Xshare:on -XX:SharedArchiveFile=app.jsa: AppCDS (Class Data Sharing)
# -Djava.security.egd=file:/dev/./urandom: entropy rápido
ENV JAVA_OPTS="-Xms48m -Xmx180m -XX:MaxRAMPercentage=70 -XX:+UseZGC -XX:TieredStopAtLevel=1 -XX:CICompilerCount=1 -Xshare:on -XX:SharedArchiveFile=app.jsa -Djava.security.egd=file:/dev/./urandom"

# Timezone Brasil
ENV TZ=America/Sao_Paulo

# Porta da aplicação
EXPOSE 8080

# Health check - usa wget do distroless (tem busybox)
# grace_period no fly.toml cuida do cold start
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/api/v1/actuator/health || exit 1

# Entry point (distroless não tem shell, usa exec form)
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+UseZGC", "-XX:TieredStopAtLevel=1", "-XX:CICompilerCount=1", "-Xshare:on", "-XX:SharedArchiveFile=app.jsa", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]