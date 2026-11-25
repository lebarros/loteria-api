# Requisitos do Sistema - Loteria API

---

## 1. Visão Geral do Sistema

API REST desenvolvida em **Spring Boot** para disponibilizar os resultados das Loterias da Caixa com:

- ✅ **Baixa latência**
- ✅ **Atualização automatizada** logo após os sorteios
- ✅ **Custo inicial zero**
- ✅ **Arquitetura preparada para picos exponenciais** de acesso

---

## 2. Requisitos Funcionais (RF)

### 📦 Carga Inicial e Histórico (ETL)

| ID | Requisito | Descrição |
|----|-----------|-----------|
| **RF01** | **Importação Histórica em Lote** | Script de carga única (Python/Pandas ou Java/Apache POI) para ler planilhas oficiais `.xlsx` da Caixa (`.../resultados/download?modalidade={Modalidade}`) e popular o banco sem acionar bloqueio por excesso de requisições. |
| **RF02** | **Sincronização Fina Pós-Carga** | Após importar as planilhas, consultar a API JSON da Caixa para preencher concursos recentes que ainda não constem nos arquivos `.xlsx`. |

### 🤖 Coleta e Atualização Automática (Worker)

| ID | Requisito | Descrição |
|----|-----------|-----------|
| **RF03** | **Consumo da API Interna da Caixa** | Coletor busca dados nos endpoints JSON oficiais:<br>`servicebus2.caixa.gov.br/portaldeloterias/api/{modalidade}`<br>`servicebus2.caixa.gov.br/portaldeloterias/api/{modalidade}/{concurso}` |
| **RF04** | **Polling Baseado em Calendário** | Agendador conhece os dias de sorteio de cada modalidade e executa consultas em alta frequência (30–60 seg) **exclusivamente na janela de apuração (20h00–22h30 BRT)**. |
| **RF05** | **Parada Automática (Circuit Breaker de Agenda)** | Ao detectar que o número do concurso retornado é superior ao último persistido **e** os dados de premiação estão completos: salva o registro, invalida o cache e encerra o polling daquela modalidade até o próximo dia de sorteio. |
| **RF06** | **Tratamento de Dados Parciais** | Se a Caixa liberar as dezenas antes do fechamento do rateio (`listaRateioPremio` vazio): persiste o resultado com `status: "APURACAO"` e continua o polling até o preenchimento definitivo (`status: "FINALIZADO"`). |

### 🌐 Exposição da API REST

| ID | Requisito | Endpoint | Descrição |
|----|-----------|----------|-----------|
| **RF07** | **Consulta do Último Concurso** | `GET /api/v1/{modalidade}/latest` | Retorna o sorteio mais recente. |
| **RF08** | **Consulta por Número do Concurso** | `GET /api/v1/{modalidade}/{concurso}` | Retorna os dados de um sorteio específico. |
| **RF09** | **Listagem de Modalidades** | `GET /api/v1/modalidades` | Informa as loterias suportadas e o número do último concurso disponível de cada uma. |
| **RF10** | **Gatilho Interno de Sincronização** | `POST /internal/sync/{modalidade}` | Endpoint protegido para acionamento externo via Google Cloud Scheduler (opcional para serverless). |

---

## 3. Requisitos Não Funcionais (RNF)

### ⚡ Desempenho e Cache

| ID | Requisito | Descrição |
|----|-----------|-----------|
| **RNF01** | **Desacoplamento Total** | Nenhuma requisição de usuário final consulta diretamente os servidores da Caixa; a API lê exclusivamente do cache ou do banco próprio. |
| **RNF02** | **Cache em Memória (Aplicação)** | Spring Cache + **Caffeine** para manter em RAM o último resultado de cada modalidade, com invalidação automática (`@CacheEvict`) quando o worker persiste um novo sorteio. |
| **RNF03** | **Cache de Borda (CDN)** | Cabeçalhos HTTP `Cache-Control` dinâmicos para Cloudflare:<br>• **Concursos passados:** `public, max-age=31536000, immutable`<br>• **Último concurso (fora da janela):** `public, max-age=3600`<br>• **Último concurso (durante janela 20h–22h30):** `public, max-age=30` |

### 🛡️ Resiliência e Rede

| ID | Requisito | Descrição |
|----|-----------|-----------|
| **RNF04** | **Prevenção de Bloqueios (WAF)** | Requisições do worker à Caixa incluem headers de navegador (`User-Agent`, `Accept`) e respeitam intervalos seguros para evitar erros **403** e **429**. |
| **RNF05** | **Tolerância a Instabilidades** | Timeouts curtos (5–10 seg) no `RestClient` + política de **retry com backoff exponencial** para lidar com lentidões da Caixa em dias de prêmios acumulados. |

### 🔒 Recursos e Segurança

| ID | Requisito | Descrição |
|----|-----------|-----------|
| **RNF06** | **Otimização de Memória da JVM** | Container Docker limita explicitamente RAM para operar em planos gratuitos de **512 MB**:<br>`-Xmx350m -Xss256k -XX:+UseContainerSupport` |
| **RNF07** | **Proteção de Rotas e Credenciais** | Credenciais de banco e chaves do endpoint `/internal/sync` injetadas **exclusivamente via variáveis de ambiente**. |

---

## 4. Arquitetura e Stack Tecnológica

| Camada | Tecnologia / Provedor | Justificativa |
|--------|----------------------|---------------|
| **Backend / API** | Java 21+, Spring Boot 3+ (Web, Data JPA, Cache, RestClient) | Produtividade, tipagem forte, ecossistema maduro |
| **Banco de Dados** | PostgreSQL (Neon) | Histórico < 100 MB; suporte nativo a JSONB |
| **Hospedagem API** | Google Cloud Run (+ Cloud Scheduler) ou Oracle Cloud Always Free | Custo zero inicial; auto-scaling nativo (Cloud Run) ou VM ARM 24/7 sem cold start (Oracle) |
| **Borda / Escala** | Cloudflare (Plano Gratuito) | Absorve > 99% dos picos de leitura antes de atingir API/banco |
| **Empacotamento** | Docker (Multi-stage build com JRE slim/alpine) | Portabilidade entre clouds e controle estrito de memória |

---

## 5. Modelagem de Dados (PostgreSQL)

**Tabela principal: `resultados`**  
Abordagem híbrida: relacional para consultas rápidas + **JSONB** para estruturas variáveis por modalidade.

| Coluna | Tipo | Constraints | Descrição |
|--------|------|-------------|-----------|
| `modalidade` | `VARCHAR(30)` | **PK composta** | Ex: `megasena`, `lotofacil`, `quina`, `lotomania`, `timemania`, `duplasena`, `super7`, `loteca` |
| `concurso` | `INTEGER` | **PK composta** | Número sequencial do sorteio |
| `data_apuracao` | `DATE` | `NOT NULL` | Data oficial do sorteio |
| `dezenas` | `JSONB` | `NOT NULL` | Lista das dezenas sorteadas em ordem (ex: `[1,2,3,4,5,6]`) |
| `acumulado` | `BOOLEAN` | `NOT NULL DEFAULT FALSE` | Prêmio principal acumulou? |
| `valor_estimado_proximo` | `NUMERIC(15,2)` | | Estimativa para o próximo concurso (reais) |
| `data_proximo_concurso` | `DATE` | | Data prevista do próximo sorteio |
| `rateio_premios` | `JSONB` | `NOT NULL DEFAULT '[]'` | Faixas de premiação: `[{"descricao":"6 acertos","ganhadores":2,"valor":1500000.00},...]` |
| `detalhes_extras` | `JSONB` | `NOT NULL DEFAULT '{}'` | Campos específicos: trevos (+Milionária), time_coracao (Timemania), cidades_ganhadores, etc. |
| `status` | `VARCHAR(20)` | `NOT NULL DEFAULT 'FINALIZADO'`<br>`CHECK (status IN ('APURACAO','FINALIZADO'))` | Estado do resultado |
| `atualizado_em` | `TIMESTAMPTZ` | `NOT NULL DEFAULT NOW()` | Auditoria da última atualização |

### Índices Recomendados

```sql
-- Para queries de último concurso e listagem por data
CREATE INDEX idx_resultados_modalidade_data
    ON resultados (modalidade, data_apuracao DESC);

-- Para buscar concursos em apuração
CREATE INDEX idx_resultados_status
    ON resultados (status) WHERE status = 'APURACAO';

-- Para queries JSONB
CREATE INDEX idx_resultados_rateio_gin ON resultados USING GIN (rateio_premios);
CREATE INDEX idx_resultados_detalhes_gin ON resultados USING GIN (detalhes_extras);
```

---

## ✅ Checklist de Implementação

| Etapa | Status | Observações |
|-------|--------|-------------|
| **1. Setup Spring Boot + Docker** | ✅ Concluído | Java 21, Spring Boot 3.3, Gradle, Jib, Multi-stage Dockerfile |
| **2. Modelagem JPA + Repository** | ✅ Concluído | Entity `Resultado` + `ResultadoId` (PK composta), `ResultadoRepository` com `@Cacheable`/`@CacheEvict` |
| **3. ETL - Importação Histórica** | ✅ Concluído | Script Python `etl/load_xlsx.py` com parsing XML nativo (evita bug do openpyxl com `-`) |
| **4. Worker Coleta Automática** | ✅ Concluído | `LoteriaWorkerService` + `CaixaApiClient` com `@Retryable`, calendar scheduling, circuit breaker, status APURACAO/FINALIZADO |
| **5. API REST Endpoints** | ✅ Concluído | `ResultadoController` (RF07-RF09) + `InternalSyncController` (RF10) com Cache-Control dinâmico |
| **6. Cache + Performance** | ✅ Concluído | Caffeine config, TTLs diferenciados, invalidação no worker |
| **7. Configs Produção** | ✅ Concluído | JVM tuning, env vars, secrets, RestClient timeouts/retry, headers anti-WAF |

---

## 🚀 Como Executar

```bash
# 1. Configurar variáveis de ambiente
cp etl/.env.example etl/.env
# Editar etl/.env com DATABASE_URL do Neon

# 2. Carregar histórico (ETL)
cd etl
pip install -r requirements.txt
python load_xlsx.py --todas

# 3. Subir API local (Docker Compose)
cd ..
docker-compose up -d postgres
export DATABASE_URL="jdbc:postgresql://localhost:5432/loteria"
export INTERNAL_SYNC_KEY="dev-secret"
export WORKER_ENABLED=true
gradle8 bootRun

# 4. Testar endpoints
curl http://localhost:8080/api/v1/modalidades
curl http://localhost:8080/api/v1/megasena/latest
curl http://localhost:8080/api/v1/megasena/2700
```

---

## 📁 Estrutura do Projeto

```
loteria/
├── etl/
│   ├── load_xlsx.py          # ETL Python (RF01, RF02)
│   ├── requirements.txt
│   └── .env.example
├── src/main/
│   ├── java/com/loteria/api/
│   │   ├── LoteriaApiApplication.java
│   │   ├── config/
│   │   │   ├── CacheConfig.java
│   │   │   └── WorkerProperties.java
│   │   ├── controller/
│   │   │   ├── ResultadoController.java      # RF07-RF09 + RNF03
│   │   │   └── InternalSyncController.java   # RF10
│   │   ├── client/
│   │   │   └── CaixaApiClient.java           # RF03 + RNF04, RNF05
│   │   ├── dto/
│   │   │   └── CaixaApiResponse.java
│   │   ├── entity/
│   │   │   ├── Resultado.java
│   │   │   └── ResultadoId.java
│   │   ├── repository/
│   │   │   └── ResultadoRepository.java      # + Cache annotations
│   │   └── service/
│   │       └── LoteriaWorkerService.java     # RF04-RF06 + Circuit Breaker
│   └── resources/
│       ├── application.yml
│       └── db/migration/
│           └── V1__create_resultados_table.sql
├── build.gradle
├── Dockerfile
├── docker-compose.yml
├── gradlew / gradle/wrapper/
├── REQUISITOS.md           # Este arquivo
└── Requisitos.txt          # Original
```

---

*Documento gerado a partir de `Requisitos.txt` — Última atualização: 2026-11-25*