package com.loteria.api.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Entidade mapeada para a tabela 'resultados' (PK composta: modalidade + concurso).
 * Usa JSONB para campos flexíveis por modalidade.
 */
@Entity
@Table(name = "resultados", indexes = {
    @Index(name = "idx_resultados_modalidade_data", columnList = "modalidade, data_apuracao DESC"),
    @Index(name = "idx_resultados_status", columnList = "status")
})
@IdClass(ResultadoId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class Resultado {

    @Id
    @Column(name = "modalidade", length = 30, nullable = false)
    private String modalidade;

    @Id
    @Column(name = "concurso", nullable = false)
    private Integer concurso;

    @Column(name = "data_apuracao", nullable = false)
    private LocalDate dataApuracao;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dezenas", columnDefinition = "jsonb", nullable = false)
    private List<Object> dezenas; // Integer para numéricas, String para Loteca (1, X, 2)

    @Column(name = "acumulado", nullable = false)
    @Builder.Default
    private Boolean acumulado = false;

    @Column(name = "valor_estimado_proximo", precision = 15, scale = 2)
    private BigDecimal valorEstimadoProximo;

    @Column(name = "data_proximo_concurso")
    private LocalDate dataProximoConcurso;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rateio_premios", columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> rateioPremios;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detalhes_extras", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> detalhesExtras;

    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private String status = "FINALIZADO"; // APURACAO ou FINALIZADO

    @Column(name = "atualizado_em", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime atualizadoEm;

    // Helpers para facilitar uso na API

    public List<Integer> getDezenasAsInt() {
        if (dezenas == null) return List.of();
        return dezenas.stream()
                .filter(d -> d instanceof Number)
                .map(d -> ((Number) d).intValue())
                .toList();
    }

    public List<String> getDezenasAsString() {
        if (dezenas == null) return List.of();
        return dezenas.stream()
                .map(Object::toString)
                .toList();
    }

    public boolean isFinalizado() {
        return "FINALIZADO".equalsIgnoreCase(status);
    }

    public boolean isApuracao() {
        return "APURACAO".equalsIgnoreCase(status);
    }
}