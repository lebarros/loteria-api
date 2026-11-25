#!/bin/bash
# ============================================================
# Script de teste da Loteria API (curl commands)
# Execute após subir a API com ./run-local.sh
# ============================================================

BASE_URL="http://localhost:8080/api/v1"
SYNC_KEY="dev-secret-change-in-prod"

# Função para pretty-print JSON (usa python se jq não disponível)
json_pp() {
    if command -v jq >/dev/null 2>&1; then
        jq .
    else
        python3 -m json.tool
    fi
}

echo "🧪 Testando Loteria API em $BASE_URL"
echo "=============================================="

# ------------------------------------------------------------------
# Health Check
# ------------------------------------------------------------------
echo ""
echo "1️⃣  Health Check:"
curl -s "$BASE_URL/health" | json_pp

# ------------------------------------------------------------------
# RF09 - Listagem de Modalidades
# ------------------------------------------------------------------
echo ""
echo "2️⃣  RF09 - GET /modalidades (lista loterias + último concurso):"
curl -s "$BASE_URL/modalidades" | json_pp

# ------------------------------------------------------------------
# RF07 - Último concurso (testa com Mega-Sena)
# ------------------------------------------------------------------
echo ""
echo "3️⃣  RF07 - GET /megasena/latest (último concurso):"
curl -s "$BASE_URL/megasena/latest" | json_pp

# ------------------------------------------------------------------
# RF08 - Concurso específico
# ------------------------------------------------------------------
echo ""
echo "4️⃣  RF08 - GET /megasena/2700 (concurso específico):"
curl -s "$BASE_URL/megasena/2700" | json_pp

# ------------------------------------------------------------------
# Testa outras modalidades
# ------------------------------------------------------------------
for mod in lotofacil quina lotomania timemania duplasena super7 loteca; do
    echo ""
    echo "5️⃣  $mod - GET /${mod}/latest:"
    curl -s "$BASE_URL/${mod}/latest" | json_pp
done

# ------------------------------------------------------------------
# Testa Cache-Control headers (RNF03)
# ------------------------------------------------------------------
echo ""
echo "6️⃣  Verificando headers Cache-Control (RNF03):"
echo "--- Concurso passado (deve ter max-age=31536000, immutable) ---"
curl -sI "$BASE_URL/megasena/1" | grep -i cache-control

echo "--- Último concurso (fora janela: max-age=3600) ---"
curl -sI "$BASE_URL/megasena/latest" | grep -i cache-control

# ------------------------------------------------------------------
# RF10 - Sync interno (protegido por header)
# ------------------------------------------------------------------
echo ""
echo "7️⃣  RF10 - POST /internal/sync/megasena (sync manual):"
curl -s -X POST "$BASE_URL/internal/sync/megasena" \
    -H "X-Internal-Sync-Key: $SYNC_KEY" | json_pp

# ------------------------------------------------------------------
# Status do Worker
# ------------------------------------------------------------------
echo ""
echo "8️⃣  GET /internal/sync/status (status do worker):"
curl -s "$BASE_URL/internal/sync/status" \
    -H "X-Internal-Sync-Key: $SYNC_KEY" | json_pp

echo ""
echo "✅ Testes concluídos!"