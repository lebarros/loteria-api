#!/bin/bash
# ============================================================
# Script para rodar a Loteria API localmente (conecta no Neon PostgreSQL)
# ============================================================

set -e

PROJECT_DIR="/home/leandro/Dev/loteria"
cd "$PROJECT_DIR"

echo "🚀 Iniciando Loteria API local (conectando no Neon)"
echo "=============================================="

# ------------------------------------------------------------------
# 1. Verifica/instala Gradle wrapper se necessário
# ------------------------------------------------------------------
if [ ! -f "$GRADLE_CMD" ]; then
    echo "⚠️  gradlew não encontrado. Baixando Gradle 8.8..."
    mkdir -p gradle/wrapper
    curl -sL -o gradle/wrapper/gradle-wrapper.jar \
        "https://github.com/gradle/gradle/raw/v8.8.0/gradle/wrapper/gradle-wrapper.jar"
    chmod +x gradlew
fi

# Usa Gradle 8.8 instalado localmente
export PATH="$PATH:$HOME/.local/bin"
GRADLE_CMD="gradle8"

# Verifica se gradle8 existe
if ! command -v $GRADLE_CMD &> /dev/null; then
    echo "❌ gradle8 não encontrado em \$PATH"
    echo "   Execute: export PATH=\"\$PATH:\$HOME/.local/bin\""
    exit 1
fi

# ------------------------------------------------------------------
# 2. Carrega variáveis de ambiente do .env (Neon)
# ------------------------------------------------------------------
echo ""
echo "🔧 Carregando configuração do Neon..."

ENV_FILE=".env"
if [ -f "$ENV_FILE" ]; then
    echo "📄 Carregando $ENV_FILE..."
    export $(grep -v '^#' "$ENV_FILE" | xargs)
else
    echo "❌ Arquivo $ENV_FILE não encontrado!"
    echo "   Crie o arquivo .env com sua DATABASE_URL do Neon:"
    echo "   DATABASE_URL=jdbc:postgresql://ep-xxx.neon.tech/neondb?sslmode=require"
    echo "   DATABASE_USER=seu_usuario"
    echo "   DATABASE_PASSWORD=sua_senha"
    echo "   INTERNAL_SYNC_KEY=sua-chave-secreta"
    echo "   WORKER_ENABLED=true"
    exit 1
fi

# Se DATABASE_URL contém user:pass@host, extrai para variáveis separadas
# (evita problema de @ na senha quebrar JDBC URL)
if [[ "$DATABASE_URL" =~ ^postgresql://([^:]+):([^@]+)@(.+)$ ]]; then
    export DATABASE_USER="${BASH_REMATCH[1]}"
    export DATABASE_PASSWORD="${BASH_REMATCH[2]}"
    export DATABASE_URL="jdbc:postgresql://${BASH_REMATCH[3]}"
    echo "   🔧 DATABASE_URL parseada: user + password separados"
elif [[ "$DATABASE_URL" =~ ^jdbc:postgresql://([^:]+):([^@]+)@(.+)$ ]]; then
    export DATABASE_USER="${BASH_REMATCH[1]}"
    export DATABASE_PASSWORD="${BASH_REMATCH[2]}"
    export DATABASE_URL="jdbc:postgresql://${BASH_REMATCH[3]}"
    echo "   🔧 DATABASE_URL parseada: user + password separados"
fi

# Valida DATABASE_URL
if [ -z "$DATABASE_URL" ]; then
    echo "❌ DATABASE_URL não definida no .env"
    exit 1
fi

# Garante que começa com jdbc:
if [[ "$DATABASE_URL" == postgresql://* ]]; then
    export DATABASE_URL="jdbc:$DATABASE_URL"
fi

export INTERNAL_SYNC_KEY="${INTERNAL_SYNC_KEY:-dev-secret-change-in-prod}"
export WORKER_ENABLED="${WORKER_ENABLED:-true}"
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}"

echo "   ✅ DATABASE_URL: $DATABASE_URL"
echo "   ✅ DATABASE_USER: $DATABASE_USER"
echo "   ✅ WORKER_ENABLED: $WORKER_ENABLED"

# ------------------------------------------------------------------
# 3. Compila e roda a aplicação (Tomcat embarcado na porta 8080)
# ------------------------------------------------------------------
echo ""
echo "🔨 Compilando projeto..."
$GRADLE_CMD build -x test --quiet

echo ""
echo "🌐 Iniciando API na porta 8080 (Tomcat embarcado)..."
echo "=============================================="
echo "Endpoints disponíveis:"
echo "  GET  http://localhost:8080/api/v1/modalidades"
echo "  GET  http://localhost:8080/api/v1/megasena/latest"
echo "  GET  http://localhost:8080/api/v1/megasena/2700"
echo "  GET  http://localhost:8080/api/v1/actuator/health"
echo "  POST http://localhost:8080/api/v1/internal/sync/megasena (header: X-Internal-Sync-Key)"
echo "=============================================="
echo ""
echo "Pressione Ctrl+C para parar"
echo ""

# Roda a aplicação
$GRADLE_CMD bootRun