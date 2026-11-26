-- V1__create_resultados_table.sql
-- Tabela única para todas as modalidades (PK composta: modalidade + concurso)
-- Compatível com PostgreSQL / Neon

CREATE TABLE IF NOT EXISTS resultados (
    modalidade        VARCHAR(30)     NOT NULL,
    concurso          INTEGER         NOT NULL,
    data_apuracao     DATE            NOT NULL,
    dezenas           JSONB           NOT NULL,
    acumulado         BOOLEAN         NOT NULL DEFAULT FALSE,
    valor_estimado_proximo   NUMERIC(15,2),
    data_proximo_concurso    DATE,
    rateio_premios    JSONB           NOT NULL DEFAULT '[]'::jsonb,
    detalhes_extras   JSONB           NOT NULL DEFAULT '{}'::jsonb,
    status            VARCHAR(20)     NOT NULL DEFAULT 'FINALIZADO'
        CHECK (status IN ('APURACAO', 'FINALIZADO')),
    atualizado_em     TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_resultados PRIMARY KEY (modalidade, concurso)
);

-- Índices para performance das queries da API
CREATE INDEX IF NOT EXISTS idx_resultados_modalidade_data
    ON resultados (modalidade, data_apuracao DESC);

CREATE INDEX IF NOT EXISTS idx_resultados_status
    ON resultados (status) WHERE status = 'APURACAO';

-- Índice GIN para buscas em JSONB (rateio_premios, detalhes_extras)
CREATE INDEX IF NOT EXISTS idx_resultados_rateio_gin
    ON resultados USING GIN (rateio_premios);

CREATE INDEX IF NOT EXISTS idx_resultados_detalhes_gin
    ON resultados USING GIN (detalhes_extras);

-- Comentários para documentação
COMMENT ON TABLE resultados IS 'Resultados de todas as modalidades de loteria (PK: modalidade + concurso)';
COMMENT ON COLUMN resultados.modalidade IS 'Ex: megasena, lotofacil, quina, lotomania, timemania, diadesorte, super7, milionaria';
COMMENT ON COLUMN resultados.concurso IS 'Número sequencial do sorteio';
COMMENT ON COLUMN resultados.data_apuracao IS 'Data oficial do sorteio';
COMMENT ON COLUMN resultados.dezenas IS 'Array JSON das dezenas sorteadas em ordem: [1,2,3,4,5,6]';
COMMENT ON COLUMN resultados.acumulado IS 'Se o prêmio principal acumulou para o próximo concurso';
COMMENT ON COLUMN resultados.valor_estimado_proximo IS 'Estimativa do prêmio para o próximo concurso (reais)';
COMMENT ON COLUMN resultados.data_proximo_concurso IS 'Data prevista do próximo sorteio';
COMMENT ON COLUMN resultados.rateio_premios IS 'Faixas de premiação: [{"descricao":"6 acertos","ganhadores":2,"valor":1500000.00},...]';
COMMENT ON COLUMN resultados.detalhes_extras IS 'Campos específicos por modalidade: trevos (+Milionária), time_coracao (Timemania), cidades_ganhadores, etc.';
COMMENT ON COLUMN resultados.status IS 'APURACAO (dezenas divulgadas, rateio pendente) ou FINALIZADO (completo)';
COMMENT ON COLUMN resultados.atualizado_em IS 'Timestamp da última atualização (auditoria)';