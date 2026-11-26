package com.loteria.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * DTOs para mapear a resposta JSON da API da Caixa.
 * Estrutura baseada na documentação oficial e exemplos reais.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class CaixaApiResponse {

    @JsonProperty("numero")
    private Integer concurso;

    @JsonProperty("dataApuracao")
    private String dataApuracao;

    @JsonProperty("dataProximoConcurso")
    private String dataProximoConcurso;

    @JsonProperty("valorEstimadoProximoConcurso")
    private String valorEstimadoProximoConcurso;

    @JsonProperty("acumulado")
    private Boolean acumulado;

    @JsonProperty("listaDezenas")
    private List<Object> dezenas; // Integer ou String (Loteca: "1", "X", "2")

    @JsonProperty("listaRateioPremio")
    private List<RateioPremio> listaRateioPremio;

    @JsonProperty("detalhesExtras")
    private Map<String, Object> detalhesExtras;

    @JsonProperty("observacao")
    private String observacao;

    // Para Loteca - resultados dos jogos
    @JsonProperty("listaResultadoJogos")
    private List<ResultadoJogo> listaResultadoJogos;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RateioPremio {
        @JsonProperty("descricaoFaixa")
        private String descricaoFaixa;

        @JsonProperty("numeroDeGanhadores")
        private Integer numeroDeGanhadores;

        @JsonProperty("valorPremio")
        private String valorPremio;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResultadoJogo {
        @JsonProperty("jogo")
        private Integer jogo;

        @JsonProperty("timeCasa")
        private String timeCasa;

        @JsonProperty("timeFora")
        private String timeFora;

        @JsonProperty("resultado")
        private String resultado; // "1", "X", "2"
    }

    /**
     * Verifica se o rateio está preenchido (não está em apuração).
     */
    public boolean isRateioCompleto() {
        return listaRateioPremio != null && !listaRateioPremio.isEmpty()
                && listaRateioPremio.stream().anyMatch(r -> r.getNumeroDeGanhadores() != null && r.getNumeroDeGanhadores() > 0);
    }

    /**
     * Verifica se tem dezenas sorteadas.
     */
    public boolean temDezenas() {
        return dezenas != null && !dezenas.isEmpty();
    }
}