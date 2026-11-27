#!/usr/bin/env python3
"""
Script para backfill dos 10 últimos concursos de cada loteria
com a ordem correta de sorteio (dezenasOrdemSorteio) e demais campos extras.

Executa via: python3 backfill_ordem_sorteio.py
"""

import os
import sys
import json
import time
import psycopg2
import requests
from datetime import datetime
from typing import Optional, Dict, List, Any
from urllib.parse import urlparse

# Carrega variáveis do .env
from dotenv import load_dotenv
load_dotenv()

# Configuração do banco (Neon via pooler) - lê do .env
DATABASE_URL = os.getenv("DATABASE_URL")
if not DATABASE_URL:
    print("❌ DATABASE_URL não encontrado no .env")
    sys.exit(1)

# Mapeamento de modalidades para endpoint da Caixa
CAIXA_MODALIDADE_MAP = {
    "megasena": "megasena",
    "lotofacil": "lotofacil",
    "quina": "quina",
    "lotomania": "lotomania",
    "timemania": "timemania",
    "duplasena": "duplasena",
    "super7": "supersete",  # Caixa usa "supersete"
    "loteca": "loteca",
}

# Headers para evitar WAF
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Accept": "application/json",
    "Accept-Language": "pt-BR,pt;q=0.9,en;q=0.8",
    "Referer": "https://loterias.caixa.gov.br/",
    "Origin": "https://loterias.caixa.gov.br",
}

BASE_URL = "https://servicebus2.caixa.gov.br/portaldeloterias/api"

def get_db_connection():
    """Cria conexão com o banco Neon."""
    return psycopg2.connect(DATABASE_URL)

def fetch_concurso(modalidade: str, concurso: int) -> Optional[Dict]:
    """Busca um concurso específico na API da Caixa."""
    endpoint = CAIXA_MODALIDADE_MAP.get(modalidade, modalidade)
    url = f"{BASE_URL}/{endpoint}/{concurso}"

    try:
        response = requests.get(url, headers=HEADERS, timeout=30)
        if response.status_code == 404:
            return None
        response.raise_for_status()
        data = response.json()

        # Verifica se tem dados válidos
        if not data.get("listaDezenas"):
            return None

        return data
    except requests.RequestException as e:
        print(f"  ⚠️  Erro ao buscar {modalidade}/{concurso}: {e}")
        return None
    except json.JSONDecodeError as e:
        print(f"  ⚠️  Erro ao parsear JSON {modalidade}/{concurso}: {e}")
        return None

def fetch_ultimo_concurso(modalidade: str) -> Optional[Dict]:
    """Busca o último concurso da modalidade."""
    endpoint = CAIXA_MODALIDADE_MAP.get(modalidade, modalidade)
    url = f"{BASE_URL}/{endpoint}"

    try:
        response = requests.get(url, headers=HEADERS, timeout=30)
        response.raise_for_status()
        data = response.json()

        if not data.get("listaDezenas"):
            return None

        return data
    except Exception as e:
        print(f"  ⚠️  Erro ao buscar último {modalidade}: {e}")
        return None

def parse_data_apuracao(data_str: str) -> Optional[str]:
    """Converte data DD/MM/YYYY para YYYY-MM-DD."""
    if not data_str:
        return None
    try:
        return datetime.strptime(data_str, "%d/%m/%Y").strftime("%Y-%m-%d")
    except:
        return None

def parse_valor(valor_str: str) -> Optional[float]:
    """Converte string de valor para float (REAIS)."""
    if not valor_str:
        return None
    try:
        # Caixa retorna em reais (ex: "38160.32") -> manter em reais
        return float(valor_str.replace(",", "."))
    except:
        return None

def parse_valor_bruto(valor_str: str) -> Optional[str]:
    """Mantém valor original como string para detalhes_extras."""
    if not valor_str:
        return None
    return valor_str.replace(",", ".")

def normalizar_local(local_sorteio: str, municipio_uf: str) -> Optional[str]:
    """Normaliza local do sorteio."""
    if not local_sorteio and not municipio_uf:
        return None
    parts = []
    if local_sorteio:
        parts.append(local_sorteio.strip())
    if municipio_uf:
        # Normaliza espaços: "SÃO PAULO,  SP" -> "SÃO PAULO, SP"
        normalized = " ".join(municipio_uf.split())
        parts.append(normalized)
    return " em ".join(parts) if parts else None

def normalizar_time_coracao(nome: str) -> Optional[str]:
    """Normaliza time do coração: 'CASCAVEL         /PR' -> 'CASCAVEL /PR'.
    Remove caracteres nulos (\u0000) que a Caixa retorna para loterias sem time."""
    if not nome:
        return None
    # Remove caracteres nulos e espaços extras
    cleaned = nome.replace("\u0000", "").replace("\x00", "")
    normalized = " ".join(cleaned.split())
    return normalized if normalized else None

def construir_detalhes_extras(api_data: Dict) -> Dict:
    """Constrói o JSON detalhes_extras com todos os campos extras."""
    extras = {}

    # Observação
    if api_data.get("observacao"):
        extras["observacao"] = api_data["observacao"]

    # Local
    local = normalizar_local(api_data.get("localSorteio"), api_data.get("nomeMunicipioUFSorteio"))
    if local:
        extras["local"] = local

    # Concurso especial (Caixa retorna 1 para todos, mas mantemos false para compatibilidade Heroku)
    # TODO: detectar concursos realmente especiais (Mega da Virada, etc.)
    extras["concursoEspecial"] = False

    # Dezenas em ordem de sorteio (O CAMPO MAIS IMPORTANTE!)
    if api_data.get("dezenasSorteadasOrdemSorteio"):
        extras["dezenasOrdemSorteio"] = api_data["dezenasSorteadasOrdemSorteio"]

    # Trevos (para +Milionária)
    if api_data.get("trevos"):
        extras["trevos"] = api_data["trevos"]

    # Time do coração (Timemania)
    time_coracao = normalizar_time_coracao(api_data.get("nomeTimeCoracaoMesSorte"))
    if time_coracao:
        extras["timeCoracao"] = time_coracao

    # Mês de sorte (+Milionária)
    if api_data.get("mesSorte"):
        extras["mesSorte"] = api_data["mesSorte"]

    # Estados premiados
    if api_data.get("estadosPremiados"):
        extras["estadosPremiados"] = api_data["estadosPremiados"]

    # Valores monetários (strings originais da Caixa)
    if api_data.get("valorArrecadado") is not None:
        extras["valorArrecadado"] = parse_valor_bruto(str(api_data["valorArrecadado"]))
    if api_data.get("valorAcumuladoConcurso_0_5") is not None:
        extras["valorAcumuladoConcurso_0_5"] = parse_valor_bruto(str(api_data["valorAcumuladoConcurso_0_5"]))
    if api_data.get("valorAcumuladoConcursoEspecial") is not None:
        extras["valorAcumuladoConcursoEspecial"] = parse_valor_bruto(str(api_data["valorAcumuladoConcursoEspecial"]))
    if api_data.get("valorAcumuladoProximoConcurso") is not None:
        extras["valorAcumuladoProximoConcurso"] = parse_valor_bruto(str(api_data["valorAcumuladoProximoConcurso"]))
    if api_data.get("valorEstimadoProximoConcurso") is not None:
        extras["valorEstimadoProximoConcurso"] = parse_valor_bruto(str(api_data["valorEstimadoProximoConcurso"]))

    # Próximo concurso
    if api_data.get("numeroConcursoProximo"):
        extras["proximoConcurso"] = api_data["numeroConcursoProximo"]

    # Local ganhadores
    if api_data.get("listaMunicipioUFGanhadores"):
        local_ganhadores = []
        for lg in api_data["listaMunicipioUFGanhadores"]:
            local_ganhadores.append({
                "ganhadores": lg.get("ganhadores"),
                "municipio": lg.get("municipio"),
                "nomeFatansiaUL": lg.get("nomeFatansiaUL"),
                "serie": lg.get("serie"),
                "posicao": lg.get("posicao"),
                "uf": lg.get("uf")
            })
        if local_ganhadores:
            extras["localGanhadores"] = local_ganhadores

    # Loteca: resultados dos jogos
    if api_data.get("listaResultadoJogos"):
        jogos = []
        for j in api_data["listaResultadoJogos"]:
            jogos.append({
                "numero": j.get("jogo"),
                "time_casa": j.get("timeCasa"),
                "time_fora": j.get("timeFora"),
                "resultado": j.get("resultado")
            })
        if jogos:
            extras["jogos"] = jogos

    return extras

def construir_rateio(api_data: Dict) -> List[Dict]:
    """Constrói lista de rateio (em REAIS)."""
    rateio = []
    if api_data.get("listaRateioPremio"):
        for r in api_data["listaRateioPremio"]:
            valor = parse_valor(str(r.get("valorPremio", 0)))
            rateio.append({
                "descricao": r.get("descricaoFaixa"),
                "ganhadores": r.get("numeroDeGanhadores"),
                "valor": valor
            })
    return rateio

def upsert_resultado(conn, modalidade: str, concurso: int, api_data: Dict) -> bool:
    """Insere ou atualiza resultado no banco."""
    try:
        data_apuracao = parse_data_apuracao(api_data.get("dataApuracao"))
        data_proximo = parse_data_apuracao(api_data.get("dataProximoConcurso"))

        # Dezenas como array de strings
        dezenas = api_data.get("listaDezenas", [])
        dezenas_str = json.dumps([str(d) for d in dezenas])

        # Acumulado
        acumulado = api_data.get("acumulado", False)

        # Valor estimado próximo (em REAIS)
        valor_estimado = parse_valor(str(api_data.get("valorEstimadoProximoConcurso", 0)))

        # Rateio
        rateio = json.dumps(construir_rateio(api_data))

        # Detalhes extras
        detalhes_extras = json.dumps(construir_detalhes_extras(api_data))

        # Status
        rateio_completo = any(r["ganhadores"] and r["ganhadores"] > 0 for r in json.loads(rateio)) if rateio != "[]" else False
        status = "FINALIZADO" if rateio_completo else "APURACAO"

        with conn.cursor() as cur:
            # Verifica se já existe
            cur.execute(
                "SELECT concurso FROM resultados WHERE modalidade = %s AND concurso = %s",
                (modalidade, concurso)
            )
            exists = cur.fetchone()

            if exists:
                # UPDATE
                cur.execute("""
                    UPDATE resultados SET
                        data_apuracao = %s,
                        dezenas = %s::jsonb,
                        acumulado = %s,
                        valor_estimado_proximo = %s,
                        data_proximo_concurso = %s,
                        rateio_premios = %s::jsonb,
                        detalhes_extras = %s::jsonb,
                        status = %s
                    WHERE modalidade = %s AND concurso = %s
                """, (
                    data_apuracao, dezenas_str, acumulado, valor_estimado,
                    data_proximo, rateio, detalhes_extras, status,
                    modalidade, concurso
                ))
                print(f"  ✅ Atualizado: {modalidade} #{concurso}")
            else:
                # INSERT
                cur.execute("""
                    INSERT INTO resultados (
                        modalidade, concurso, data_apuracao, dezenas, acumulado,
                        valor_estimado_proximo, data_proximo_concurso,
                        rateio_premios, detalhes_extras, status
                    ) VALUES (%s, %s, %s, %s::jsonb, %s, %s, %s, %s::jsonb, %s::jsonb, %s)
                """, (
                    modalidade, concurso, data_apuracao, dezenas_str, acumulado,
                    valor_estimado, data_proximo, rateio, detalhes_extras, status
                ))
                print(f"  ✅ Inserido: {modalidade} #{concurso}")

        conn.commit()
        return True

    except Exception as e:
        conn.rollback()
        print(f"  ❌ Erro ao salvar {modalidade} #{concurso}: {e}")
        return False

def get_ultimo_concurso_banco(conn, modalidade: str) -> Optional[int]:
    """Obtém o último concurso salvo no banco para uma modalidade."""
    with conn.cursor() as cur:
        cur.execute(
            "SELECT MAX(concurso) FROM resultados WHERE modalidade = %s",
            (modalidade,)
        )
        result = cur.fetchone()
        return result[0] if result and result[0] else None

def processar_modalidade(conn, modalidade: str, qtd: int = 10):
    """Processa os últimos N concursos de uma modalidade."""
    print(f"\n{'='*60}")
    print(f"🎰 Processando: {modalidade.upper()} (últimos {qtd} concursos)")
    print(f"{'='*60}")

    # 1. Busca o último concurso da Caixa para saber até onde ir
    ultimo_api = fetch_ultimo_concurso(modalidade)
    if not ultimo_api:
        print(f"  ⚠️  Não foi possível buscar último concurso da Caixa")
        return

    ultimo_concurso_api = ultimo_api.get("numero")
    if not ultimo_concurso_api:
        print(f"  ⚠️  Número do concurso não encontrado na resposta")
        return

    print(f"  📋 Último concurso na Caixa: #{ultimo_concurso_api}")

    # 2. Processa os últimos N concursos
    inicio = max(1, ultimo_concurso_api - qtd + 1)
    sucessos = 0
    erros = 0

    for concurso in range(ultimo_concurso_api, inicio - 1, -1):
        print(f"  🔄 Buscando concurso #{concurso}...", end=" ", flush=True)

        api_data = fetch_concurso(modalidade, concurso)
        if not api_data:
            print("⚠️  Sem dados")
            erros += 1
            time.sleep(0.5)  # Rate limiting
            continue

        if upsert_resultado(conn, modalidade, concurso, api_data):
            sucessos += 1
        else:
            erros += 1

        time.sleep(0.3)  # Rate limiting amigável

    print(f"\n  📊 Resultado {modalidade}: {sucessos} sucessos, {erros} erros")

def main():
    print("🚀 Iniciando backfill dos últimos 30 concursos de cada loteria")
    print(f"⏰ Início: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")

    modalidades = list(CAIXA_MODALIDADE_MAP.keys())

    try:
        conn = get_db_connection()
        print("✅ Conectado ao banco Neon")

        for modalidade in modalidades:
            processar_modalidade(conn, modalidade, qtd=30)

        conn.close()
        print(f"\n✅ Backfill concluído às {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")

    except Exception as e:
        print(f"\n❌ Erro fatal: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()