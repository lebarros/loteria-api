package com.loteria.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * DTOs no formato exato da API Heroku (loteriascaixa-api.herokuapp.com).
 * Usados para expor endpoints públicos 100% compatíveis.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class HerokuApiResponse {

    @JsonProperty("loteria")
    private String loteria;

    @JsonProperty("concurso")
    private Integer concurso;

    @JsonProperty("data")
    private String data; // formato DD/MM/YYYY

    @JsonProperty("local")
    private String local;

    @JsonProperty("concursoEspecial")
    private Boolean concursoEspecial;

    @JsonProperty("dezenasOrdemSorteio")
    private List<String> dezenasOrdemSorteio;

    @JsonProperty("dezenas")
    private List<String> dezenas;

    @JsonProperty("trevos")
    private List<String> trevos;

    @JsonProperty("timeCoracao")
    private String timeCoracao;

    @JsonProperty("mesSorte")
    private String mesSorte;

    @JsonProperty("premiacoes")
    private List<Premiacao> premiacoes;

    @JsonProperty("estadosPremiados")
    private List<String> estadosPremiados;

    @JsonProperty("observacao")
    private String observacao;

    @JsonProperty("acumulou")
    private Boolean acumulou;

    @JsonProperty("proximoConcurso")
    private Integer proximoConcurso;

    @JsonProperty("dataProximoConcurso")
    private String dataProximoConcurso; // DD/MM/YYYY

    @JsonProperty("localGanhadores")
    private List<LocalGanhador> localGanhadores;

    @JsonProperty("valorArrecadado")
    private Double valorArrecadado;

    @JsonProperty("valorAcumuladoConcurso_0_5")
    private Double valorAcumuladoConcurso_0_5;

    @JsonProperty("valorAcumuladoConcursoEspecial")
    private Double valorAcumuladoConcursoEspecial;

    @JsonProperty("valorAcumuladoProximoConcurso")
    private Double valorAcumuladoProximoConcurso;

    @JsonProperty("valorEstimadoProximoConcurso")
    private Double valorEstimadoProximoConcurso;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Premiacao {
        @JsonProperty("descricao")
        private String descricao;

        @JsonProperty("faixa")
        private Integer faixa;

        @JsonProperty("ganhadores")
        private Integer ganhadores;

        @JsonProperty("valorPremio")
        private Double valorPremio; // em REAIS (float), não centavos
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LocalGanhador {
        @JsonProperty("ganhadores")
        private Integer ganhadores;

        @JsonProperty("municipio")
        private String municipio;

        @JsonProperty("nomeFatansiaUL")
        private String nomeFatansiaUL;

        @JsonProperty("serie")
        private String serie;

        @JsonProperty("posicao")
        private Integer posicao;

        @JsonProperty("uf")
        private String uf;
    }
}