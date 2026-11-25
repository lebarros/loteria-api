#!/usr/bin/env python3
"""
Script de carga histórica (ETL) para popular a tabela 'resultados' a partir dos .xlsx oficiais da Caixa.

Uso:
    python load_xlsx.py --modalidade megasena --arquivo "Mega-Sena.xlsx" --dsn "postgresql://user:pass@host/db"
    python load_xlsx.py --modalidade lotofacil --arquivo "Lotofácil.xlsx" --dsn "postgresql://user:pass@host/db"
    python load_xlsx.py --todas --dsn "postgresql://user:pass@host/db"  # carrega todos os arquivos conhecidos

Requisitos: pip install pandas openpyxl psycopg2 python-dotenv tqdm
"""

import argparse
import json
import os
import sys
from dataclasses import dataclass
from typing import Any, Dict, List, Optional

import pandas as pd
import psycopg2
from psycopg2.extras import execute_batch
from tqdm import tqdm
from dotenv import load_dotenv


# ============================================================
# MAPEAMENTO DE COLUNAS POR MODALIDADE - BASEADO NOS CABEÇALHOS REAIS
# ============================================================
MODALIDADES_CONFIG: Dict[str, Dict[str, Any]] = {
    "megasena": {
        "arquivo_padrao": "Mega-Sena.xlsx",
        "colunas": {
            "concurso": "Concurso",
            "data": "Data do Sorteio",
            "dezenas": ["Bola1", "Bola2", "Bola3", "Bola4", "Bola5", "Bola6"],
            "acumulado": "Acumulado 6 acertos",
            "valor_estimado_proximo": "Estimativa prêmio",
            "data_proximo_concurso": None,  # não existe no arquivo
            "rateio": {
                "faixa_6_ganhadores": "Ganhadores 6 acertos",
                "faixa_6_valor": "Rateio 6 acertos",
                "faixa_5_ganhadores": "Ganhadores 5 acertos",
                "faixa_5_valor": "Rateio 5 acertos",
                "faixa_4_ganhadores": "Ganhadores 4 acertos",
                "faixa_4_valor": "Rateio 4 acertos",
            },
            "extras": {
                "cidade_uf_faixa_6": "Cidade / UF",
                "acumulado_mega_virada": "Acumulado Sorteio Especial Mega da Virada",
                "arrecadacao_total": "Arrecadação Total",
                "observacao": "Observação",
            },
        },
    },
    "lotofacil": {
        "arquivo_padrao": "Lotofácil.xlsx",
        "colunas": {
            "concurso": "Concurso",
            "data": "Data Sorteio",
            "dezenas": [f"Bola{i}" for i in range(1, 16)],
            "acumulado": "Acumulado 15 acertos",
            "valor_estimado_proximo": "Estimativa Prêmio",
            "data_proximo_concurso": None,
            "rateio": {
                "faixa_15_ganhadores": "Ganhadores 15 acertos",
                "faixa_15_valor": "Rateio 15 acertos",
                "faixa_14_ganhadores": "Ganhadores 14 acertos",
                "faixa_14_valor": "Rateio 14 acertos",
                "faixa_13_ganhadores": "Ganhadores 13 acertos",
                "faixa_13_valor": "Rateio 13 acertos",
                "faixa_12_ganhadores": "Ganhadores 12 acertos",
                "faixa_12_valor": "Rateio 12 acertos",
                "faixa_11_ganhadores": "Ganhadores 11 acertos",
                "faixa_11_valor": "Rateio 11 acertos",
            },
            "extras": {
                "cidade_uf_faixa_15": "Cidade / UF",
                "acumulado_independencia": "Acumulado sorteio especial Lotofácil da Independência",
                "arrecadacao_total": "Arrecadacao Total",
                "observacao": "Observação",
            },
        },
    },
    "quina": {
        "arquivo_padrao": "Quina.xlsx",
        "colunas": {
            "concurso": "Concurso",
            "data": "Data Sorteio",
            "dezenas": [f"Bola{i}" for i in range(1, 6)],
            "acumulado": "Acumulado 5 acertos",
            "valor_estimado_proximo": "Estimativa Premio",
            "data_proximo_concurso": None,
            "rateio": {
                "faixa_5_ganhadores": "Ganhadores 5 acertos",
                "faixa_5_valor": "Rateio 5 acertos",
                "faixa_4_ganhadores": "Ganhadores 4 acertos",
                "faixa_4_valor": "Rateio 4 acertos",
                "faixa_3_ganhadores": "Ganhadores 3 acertos",
                "faixa_3_valor": "Rateio 3 acertos",
                "faixa_2_ganhadores": "Ganhadores 2 acertos",
                "faixa_2_valor": "Rateio 2 acertos",
            },
            "extras": {
                "cidade_uf_faixa_5": "Cidade / UF",
                "acumulado_sao_joao": "Acumulado Sorteio Especial Quina de São João",
                "arrecadacao_total": "Arrecadacao Total",
                "observacao": "observação",
            },
        },
    },
    "lotomania": {
        "arquivo_padrao": "Lotomania.xlsx",
        "colunas": {
            "concurso": "Concurso",
            "data": "Data Sorteio",
            "dezenas": [f"Bola{i}" for i in range(1, 21)],
            "acumulado": "Acumulado 20 acertos",
            "valor_estimado_proximo": "Estimativa Prêmio",
            "data_proximo_concurso": None,
            "rateio": {
                "faixa_20_ganhadores": "Ganhadores 20 acertos",
                "faixa_20_valor": "Rateio 20 acertos",
                "faixa_19_ganhadores": "Ganhadores 19 acertos",
                "faixa_19_valor": "Rateio 19 acertos",
                "faixa_18_ganhadores": "Ganhadores 18 acertos",
                "faixa_18_valor": "Rateio 18 acertos",
                "faixa_17_ganhadores": "Ganhadores 17 acertos",
                "faixa_17_valor": "Rateio 17 acertos",
                "faixa_16_ganhadores": "Ganhadores 16 acertos",
                "faixa_16_valor": "Rateio 16 acertos",
                "faixa_15_ganhadores": "Ganhadores 15 acertos",
                "faixa_15_valor": "Rateio 15 acertos",
                "faixa_0_ganhadores": "Ganhadores Nenhum Número",
                "faixa_0_valor": "Rateio Nenhum Número",
            },
            "extras": {
                "cidade_uf_faixa_20": "Cidade / UF",
                "arrecadacao_total": "Arrecadação Total",
                "observacao": "Observação",
            },
        },
    },
    "timemania": {
        "arquivo_padrao": "Timemania.xlsx",
        "colunas": {
            "concurso": "Concurso",
            "data": "Data Sorteio",
            "dezenas": [f"Bola{i}" for i in range(1, 8)],
            "time_coracao_col": None,  # não tem coluna separada no header mostrado
            "acumulado": "Acumulado 7 acertos",  # assumindo, ajustar se necessário
            "valor_estimado_proximo": "Estimativa Prêmio",
            "data_proximo_concurso": None,
            "rateio": {
                "faixa_7_ganhadores": "Ganhadores 7 acertos",
                "faixa_7_valor": "Rateio 7 acertos",
                "faixa_6_ganhadores": "Ganhadores 6 acertos",
                "faixa_6_valor": "Rateio 6 acertos",
                "faixa_5_ganhadores": "Ganhadores 5 acertos",
                "faixa_5_valor": "Rateio 5 acertos",
                "faixa_4_ganhadores": "Ganhadores 4 acertos",
                "faixa_4_valor": "Rateio 4 acertos",
                "faixa_3_ganhadores": "Ganhadores 3 acertos",
                "faixa_3_valor": "Rateio 3 acertos",
                # time do coração - verificar colunas reais
            },
            "extras": {
                "cidade_uf_faixa_7": "Cidade / UF",
                "arrecadacao_total": "Arrecadação Total",
                "observacao": "Observação",
            },
        },
    },
    "duplasena": {
        "arquivo_padrao": "Dupla Sena.xlsx",
        "colunas": {
            "concurso": "Concurso",
            "data": "Data Sorteio",
            "dezenas": {
                "sorteio_1": [
                    "Bola1 sorteio 1", "Bola2 sorteio 1", "Bola3 sorteio 1",
                    "Bola4 Sorteio 1", "Bola5 sorteio 1", "Bola6 sorteio 1"
                ],
                "sorteio_2": [
                    "Bola1 sorteio 2", "Bola2 sorteio 2", "Bola3 sorteio 2",
                    "Bola4 Sorteio 2", "Bola5 sorteio 2", "Bola6 sorteio 2"
                ],
            },
            "acumulado": " Acumulado 6 acertos sorteio 1",
            "valor_estimado_proximo": "Estimativa Prêmio",
            "data_proximo_concurso": None,
            "rateio": {
                # Sorteio 1
                "faixa_6_s1_ganhadores": "Ganhadores 6 acertos  Sorteio 1",
                "faixa_6_s1_valor": "Rateio 6 acertos  Sorteio1",
                "faixa_5_s1_ganhadores": "Ganhadores 5   acertos Sorteio1",
                "faixa_5_s1_valor": "Rateio 5 acertos Sorteio1",
                "faixa_4_s1_ganhadores": "Ganhadores 4 acertos Sorteio1",
                "faixa_4_s1_valor": "Rateio 4 acertos Sorteio1",
                "faixa_3_s1_ganhadores": "Ganhadores 3 acertos Sorteio1",
                "faixa_3_s1_valor": "Rateio 3 acertos Sorteio 1",
                # Sorteio 2
                "faixa_6_s2_ganhadores": "Ganhadores 6 acertos Sorteio2",
                "faixa_6_s2_valor": "Rateio 6 acertos  Sorteio 2",
                "faixa_5_s2_ganhadores": "Ganhadores 5 acertos Sorteio2",
                "faixa_5_s2_valor": "Rateio 5 acertos Sorteio 2",
                "faixa_4_s2_ganhadores": "Ganhadores 4 acertos Sorteio2",
                "faixa_4_s2_valor": "Rateio 4 acertos Sorteio 2",
                "faixa_3_s2_ganhadores": "Ganhadores 3 acertos Sorteio2",
                "faixa_3_s2_valor": "Rateio 3 acertos Sorteio 2",
            },
            "extras": {
                "cidade_uf_faixa_6_s1": "Cidade / UF",
                "acumulado_pascoa": "Acumulado Sorteio Especial Dupla de Páscoa",
                "arrecadacao_total": "Arrecadacao Total",
                "observacao": "Observação",
            },
        },
    },
    "super7": {
        "arquivo_padrao": "Super 7.xlsx",
        "colunas": {
            "concurso": "Concurso",
            "data": "Data Sorteio",
            "dezenas": [f"Coluna {i}" for i in range(1, 8)],
            "acumulado": "Acumulado 7 acertos",
            "valor_estimado_proximo": "Estimativa Prêmio",
            "data_proximo_concurso": None,
            "rateio": {
                "faixa_7_ganhadores": "Ganhadores 7 acertos  ",
                "faixa_7_valor": "Rateio 7 acertos",
                "faixa_6_ganhadores": "Ganhadores 6 acertos",
                "faixa_6_valor": "Rateio 6 acertos",
                "faixa_5_ganhadores": "Ganhadores 5 acertos",
                "faixa_5_valor": "Rateio 5 acertos",
                "faixa_4_ganhadores": "Ganhadores 4 acertos",
                "faixa_4_valor": "Rateio 4 acertos",
                "faixa_3_ganhadores": "Ganhadores 3 acertos",
                "faixa_3_valor": "Rateio 3 acertos",
            },
            "extras": {
                "cidade_uf_faixa_7": "Cidade / UF",
                "arrecadacao_total": "Arrecadação Total",
                "observacao": "Observação",
            },
        },
    },
    "loteca": {
        "arquivo_padrao": "Loteca.xlsx",
        "colunas": {
            "concurso": "Concurso",
            "data": "Data de apuração",
            "dezenas": [f"Jogo {i}" for i in range(1, 15)],
            "acumulado": "Acumulado 14 Acertos",
            "valor_estimado_proximo": "Estimativa Prêmio",
            "data_proximo_concurso": None,
            "rateio": {
                "faixa_14_ganhadores": "Ganhadores 14 Acertos",
                "faixa_14_valor": " Rateio 14 Acertos",
                "faixa_13_ganhadores": "Ganhadores 13 Acertos",
                "faixa_13_valor": "Rateio 13 Acertos",
            },
            "extras": {
                "cidade_uf_faixa_14": "Cidade / UF",
                "arrecadacao_total": "Arrecadacao Total",
                "observacao": "Observação",
            },
        },
    },
}


@dataclass
class ResultadoLinha:
    modalidade: str
    concurso: int
    data_apuracao: str
    dezenas: List[Any]  # int para loterias numéricas, str para Loteca (1, X, 2)
    acumulado: bool
    valor_estimado_proximo: Optional[float]
    data_proximo_concurso: Optional[str]
    rateio_premios: List[Dict]
    detalhes_extras: Dict
    status: str = "FINALIZADO"


def parse_bool(val: Any) -> bool:
    if isinstance(val, bool):
        return val
    if isinstance(val, (int, float)):
        return bool(val)
    if isinstance(val, str):
        val = val.strip()
        if not val or val == '-':
            return False
        return val.upper() in ("SIM", "S", "TRUE", "1", "YES", "Y")
    return False


def parse_float(val: Any) -> Optional[float]:
    if val is None or (isinstance(val, float) and pd.isna(val)):
        return None
    if isinstance(val, str):
        val = val.strip()
        if not val or val == '-':
            return None
        # Remove símbolo de moeda (R$, US$, etc.) e espaços
        import re
        val = re.sub(r'[R$\s]', '', val)
        # Remove separadores de milhar (.) e troca decimal (,) por (.)
        val = val.replace(".", "").replace(",", ".")
        if not val:
            return None
    try:
        return float(val)
    except (ValueError, TypeError):
        return None


def parse_date(val: Any) -> Optional[str]:
    if val is None or (isinstance(val, float) and pd.isna(val)):
        return None
    if isinstance(val, pd.Timestamp):
        return val.strftime("%Y-%m-%d")
    if isinstance(val, str):
        val = val.strip()
        if not val or val == '-':
            return None
        for fmt in ("%d/%m/%Y", "%Y-%m-%d", "%d-%m-%Y"):
            try:
                return pd.to_datetime(val, format=fmt).strftime("%Y-%m-%d")
            except ValueError:
                continue
    return None


def parse_int(val: Any) -> Optional[int]:
    if val is None or (isinstance(val, float) and pd.isna(val)):
        return None
    if isinstance(val, str):
        val = val.strip()
        if not val or val == '-':
            return None
    try:
        return int(float(val))
    except (ValueError, TypeError):
        return None


def montar_dezenas(row: pd.Series, config: Dict, modalidade: str = "") -> List[Any]:
    """Extrai as dezenas conforme configuração da modalidade."""
    dezenas = []
    cols = config["colunas"].get("dezenas", [])
    # Loteca: resultados são strings (1, X, 2), não inteiros
    as_string = modalidade == "loteca"

    if isinstance(cols, list):
        for c in cols:
            v = row.get(c)
            if v is not None and v != '-':
                if as_string:
                    dezenas.append(str(v).strip())
                else:
                    v_int = parse_int(v)
                    if v_int is not None:
                        dezenas.append(v_int)
    elif isinstance(cols, dict):
        # Dupla Sena tem dois sorteios
        for key, subcols in cols.items():
            for c in subcols:
                v = row.get(c)
                if v is not None and v != '-':
                    v_int = parse_int(v)
                    if v_int is not None:
                        dezenas.append(v_int)
    return sorted(dezenas) if not as_string else dezenas


def montar_rateio(row: pd.Series, config: Dict) -> List[Dict]:
    """Monta o JSONB rateio_premios a partir das colunas de ganhadores/valores."""
    rateio_cfg = config["colunas"].get("rateio", {})
    rateio = []
    faixas_processadas = set()

    for campo, coluna in rateio_cfg.items():
        if campo.endswith("_ganhadores"):
            # Extrai nome da faixa (remove _ganhadores e sufixos _s1/_s2)
            faixa_base = campo.replace("_ganhadores", "")
            if faixa_base in faixas_processadas:
                continue
            ganhadores = parse_int(row.get(coluna))
            valor_col = rateio_cfg.get(f"{faixa_base}_valor")
            valor = parse_float(row.get(valor_col)) if valor_col else None
            if ganhadores is not None or valor is not None:
                rateio.append({
                    "faixa": faixa_base,
                    "ganhadores": ganhadores,
                    "valor": valor
                })
                faixas_processadas.add(faixa_base)

    return rateio


def montar_detalhes_extras(row: pd.Series, config: Dict, modalidade: str) -> Dict:
    """Campos específicos por modalidade (cidade/UF, arrecadação, observações, etc.)."""
    extras = {}
    cols = config["colunas"].get("extras", {})

    for chave, coluna in cols.items():
        if coluna:
            v = row.get(coluna)
            if pd.notna(v):
                extras[chave] = str(v).strip()

    return extras


def processar_linha(row: pd.Series, config: Dict, modalidade: str) -> Optional[ResultadoLinha]:
    """Converte uma linha do DataFrame em ResultadoLinha."""
    cols = config["colunas"]

    concurso = parse_int(row.get(cols["concurso"]))
    if concurso is None:
        return None

    data_apuracao = parse_date(row.get(cols["data"]))
    if not data_apuracao:
        return None

    dezenas = montar_dezenas(row, config, modalidade)
    if not dezenas:
        return None

    # Acumulado: pega da coluna configurada
    acumulado_col = cols.get("acumulado")
    acumulado = parse_bool(row.get(acumulado_col)) if acumulado_col else False

    valor_estimado_col = cols.get("valor_estimado_proximo")
    valor_estimado = parse_float(row.get(valor_estimado_col)) if valor_estimado_col else None

    data_prox_col = cols.get("data_proximo_concurso")
    data_prox = parse_date(row.get(data_prox_col)) if data_prox_col else None

    rateio = montar_rateio(row, config)
    extras = montar_detalhes_extras(row, config, modalidade)

    return ResultadoLinha(
        modalidade=modalidade,
        concurso=concurso,
        data_apuracao=data_apuracao,
        dezenas=dezenas,
        acumulado=acumulado,
        valor_estimado_proximo=valor_estimado,
        data_proximo_concurso=data_prox,
        rateio_premios=rateio,
        detalhes_extras=extras,
        status="FINALIZADO",
    )


def upsert_batch(conn, resultados: List[ResultadoLinha], batch_size: int = 500):
    """Insere/atualiza em lote usando ON CONFLICT (PK composta)."""
    sql = """
        INSERT INTO resultados (
            modalidade, concurso, data_apuracao, dezenas, acumulado,
            valor_estimado_proximo, data_proximo_concurso,
            rateio_premios, detalhes_extras, status, atualizado_em
        ) VALUES (
            %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, NOW()
        )
        ON CONFLICT (modalidade, concurso) DO UPDATE SET
            data_apuracao = EXCLUDED.data_apuracao,
            dezenas = EXCLUDED.dezenas,
            acumulado = EXCLUDED.acumulado,
            valor_estimado_proximo = EXCLUDED.valor_estimado_proximo,
            data_proximo_concurso = EXCLUDED.data_proximo_concurso,
            rateio_premios = EXCLUDED.rateio_premios,
            detalhes_extras = EXCLUDED.detalhes_extras,
            status = EXCLUDED.status,
            atualizado_em = NOW()
    """

    dados = [
        (
            r.modalidade, r.concurso, r.data_apuracao,
            json.dumps(r.dezenas), r.acumulado,
            r.valor_estimado_proximo, r.data_proximo_concurso,
            json.dumps(r.rateio_premios), json.dumps(r.detalhes_extras),
            r.status
        )
        for r in resultados
    ]

    with conn.cursor() as cur:
        execute_batch(cur, sql, dados, page_size=batch_size)
    conn.commit()


def carregar_modalidade(conn, modalidade: str, arquivo: str, batch_size: int = 500):
    """Carrega um arquivo .xlsx para uma modalidade."""
    if modalidade not in MODALIDADES_CONFIG:
        print(f"❌ Modalidade '{modalidade}' não configurada. Disponíveis: {list(MODALIDADES_CONFIG.keys())}")
        return False

    config = MODALIDADES_CONFIG[modalidade]

    if not os.path.exists(arquivo):
        print(f"❌ Arquivo não encontrado: {arquivo}")
        return False

    print(f"\n📂 Carregando {modalidade} de {arquivo}...")
    # Parseia XML do .xlsx diretamente (evita cast automático do openpyxl)
    import zipfile
    import xml.etree.ElementTree as ET

    # Namespace do OpenXML
    NS = {'main': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}

    with zipfile.ZipFile(arquivo, 'r') as z:
        # Lê shared strings
        shared_strings = []
        if 'xl/sharedStrings.xml' in z.namelist():
            with z.open('xl/sharedStrings.xml') as f:
                tree = ET.parse(f)
                for si in tree.findall('.//main:si', NS):
                    t = si.find('main:t', NS)
                    shared_strings.append(t.text if t is not None else '')

        # Lê a primeira planilha (sheet1.xml)
        sheet_file = 'xl/worksheets/sheet1.xml'
        if sheet_file not in z.namelist():
            for name in z.namelist():
                if name.startswith('xl/worksheets/sheet') and name.endswith('.xml'):
                    sheet_file = name
                    break

        with z.open(sheet_file) as f:
            tree = ET.parse(f)
            root = tree.getroot()

            sheet_data = root.find('main:sheetData', NS)
            rows_xml = sheet_data.findall('main:row', NS) if sheet_data is not None else []

            dados = []
            header = None

            for i, row_xml in enumerate(rows_xml):
                row_vals = {}
                col_counter = 0  # posição na linha (para dados sem ref 'r')
                for cell_xml in row_xml.findall('main:c', NS):
                    # Valor da célula
                    cell_type = cell_xml.get('t', '')
                    v_elem = cell_xml.find('main:v', NS)

                    if v_elem is not None and v_elem.text is not None:
                        val = v_elem.text
                        if cell_type == 's':  # shared string
                            idx = int(val)
                            val = shared_strings[idx] if idx < len(shared_strings) else ''
                        elif cell_type == 'b':  # boolean
                            val = val == '1'
                    else:
                        val = None

                    if val == '-':
                        val = None

                    # Header (linha 0): usa ref 'r' para achar índice da coluna
                    # Dados (linhas >0): usa posição sequencial (col_counter)
                    if i == 0:
                        col_ref = cell_xml.get('r', '')
                        if not col_ref:
                            continue
                        col_letters = ''
                        for ch in col_ref:
                            if ch.isalpha():
                                col_letters += ch
                            else:
                                break
                        if not col_letters:
                            continue
                        col_idx = 0
                        for ch in col_letters:
                            col_idx = col_idx * 26 + (ord(ch.upper()) - ord('A') + 1)
                        col_idx -= 1
                    else:
                        col_idx = col_counter
                        col_counter += 1

                    if i == 0:
                        if header is None:
                            header = {}
                        header[col_idx] = str(val).strip() if val is not None else f"COL_{col_idx}"
                    else:
                        col_name = header.get(col_idx, f"COL_{col_idx}") if header else f"COL_{col_idx}"
                        row_vals[col_name] = val

                if i > 0 and row_vals:
                    dados.append(row_vals)

    df = pd.DataFrame(dados)
    print(f"   Linhas lidas: {len(df)}")
    if len(df) > 0:
        print(f"   Colunas: {df.columns.tolist()}")

    # Processa linhas
    resultados = []
    erros = 0
    for _, row in tqdm(df.iterrows(), total=len(df), desc=f"  Processando {modalidade}"):
        try:
            r = processar_linha(row, config, modalidade)
            if r:
                resultados.append(r)
            else:
                erros += 1
        except Exception as e:
            erros += 1
            if erros <= 5:
                print(f"   ⚠️ Erro na linha: {e}")

    if not resultados:
        print("   Nenhum registro válido encontrado.")
        return False

    # Insere em lotes
    print(f"   Inserindo/atualizando {len(resultados)} concursos (erros: {erros})...")
    for i in range(0, len(resultados), batch_size):
        upsert_batch(conn, resultados[i:i + batch_size], batch_size)

    print(f"   ✅ {modalidade} concluído!")
    return True


def main():
    load_dotenv()

    parser = argparse.ArgumentParser(description="ETL histórico - carrega .xlsx da Caixa para PostgreSQL")
    parser.add_argument("--modalidade", "-m", help="Modalidade única (ex: megasena, lotofacil, quina, ...)")
    parser.add_argument("--arquivo", "-a", help="Caminho do arquivo .xlsx (padrão: nome padrão da modalidade)")
    parser.add_argument("--todas", action="store_true", help="Carrega todas as modalidades conhecidas")
    parser.add_argument("--dsn", help="Connection string PostgreSQL (ex: postgresql://user:pass@host/db)")
    parser.add_argument("--batch-size", type=int, default=500, help="Tamanho do batch de insert")
    parser.add_argument("--listar", action="store_true", help="Lista modalidades suportadas e sai")

    args = parser.parse_args()

    if args.listar:
        print("Modalidades suportadas:")
        for m, cfg in MODALIDADES_CONFIG.items():
            print(f"  {m:12s} -> {cfg['arquivo_padrao']}")
        return

    dsn = args.dsn or os.getenv("DATABASE_URL")
    if not dsn:
        print("❌ DSN não informado. Use --dsn ou defina DATABASE_URL no .env")
        sys.exit(1)

    try:
        conn = psycopg2.connect(dsn)
        conn.autocommit = False
        print(f"✅ Conectado ao PostgreSQL")
    except Exception as e:
        print(f"❌ Falha ao conectar: {e}")
        sys.exit(1)

    try:
        if args.todas:
            for modalidade, cfg in MODALIDADES_CONFIG.items():
                arquivo = args.arquivo or cfg["arquivo_padrao"]
                carregar_modalidade(conn, modalidade, arquivo, args.batch_size)
        elif args.modalidade:
            arquivo = args.arquivo or MODALIDADES_CONFIG.get(args.modalidade, {}).get("arquivo_padrao")
            if not arquivo:
                print(f"❌ Modalidade desconhecida: {args.modalidade}")
                sys.exit(1)
            carregar_modalidade(conn, args.modalidade, arquivo, args.batch_size)
        else:
            parser.print_help()
            sys.exit(1)
    finally:
        conn.close()
        print("\n🔌 Conexão fechada.")


if __name__ == "__main__":
    main()