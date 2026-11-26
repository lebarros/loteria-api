# ============================================================
# Multi-stage Dockerfile para Loteria API (equivalente ao Jib)
# Otimizado para 512MB RAM (configuração original que funcionava)
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

# ---- Stage 2: Runtime (JRE Alpine) ----
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

# JVM flags para 512MB container (configuração original)
# -Xmx350m: heap máximo ~70% de 512MB
# -Xss256k: stack reduzida p/ muitas threads
# -XX:+UseZGC: GC de baixa latência, bom p/ heap pequeno
# -XX:MaxRAMPercentage=70: fallback se -Xmx não respeitado
# -XX:+UseContainerSupport: detecta limites de container (K8s/Docker)
ENV JAVA_OPTS="-Xmx350m -Xss256k -XX:+UseContainerSupport -XX:MaxRAMPercentage=70 -XX:+UseZGC -Djava.security.egd=file:/dev/./urandom"

# Timezone Brasil
ENV TZ=America/Sao_Paulo

# Porta da aplicação
EXPOSE 8080

# Health check (Spring Actuator) - start-period maior p/ cold start no free tier
HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/api/v1/actuator/health || exit 1

# Entry point
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]