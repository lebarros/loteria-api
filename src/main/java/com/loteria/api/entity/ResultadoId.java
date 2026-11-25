package com.loteria.api.entity;

import lombok.*;

import java.io.Serializable;
import java.util.Objects;

/**
 * Chave primária composta para Resultado (modalidade + concurso).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResultadoId implements Serializable {

    private String modalidade;
    private Integer concurso;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ResultadoId that)) return false;
        return Objects.equals(modalidade, that.modalidade) &&
               Objects.equals(concurso, that.concurso);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modalidade, concurso);
    }
}