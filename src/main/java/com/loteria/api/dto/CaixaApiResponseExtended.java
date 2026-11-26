package com.loteria.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * DTO estendido para capturar TODOS os campos que a API da Caixa retorna,
 * incluindo os que a API pública expõe mas nós não capturávamos antes.
 *
 * Usado internamente no CaixaApiClient e ResultadoSyncService.
 * Não exposto diretamente na API pública.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class CaixaApiResponseExtended {

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
    private List<Object> dezenas;

    @JsonProperty("listaRateioPremio")
    private List<RateioPremio> listaRateioPremio;

    @JsonProperty("detalhesExtras")
    private Map<String, Object> detalhesExtras;

    @JsonProperty("observacao")
    private String observacao;

    @JsonProperty("listaResultadoJogos")
    private List<ResultadoJogo> listaResultadoJogos;

    // === CAMPOS EXTRAS DA CAIXA (compatíveis com API pública) ===
    @JsonProperty("local")
    private String local;

    @JsonProperty("concursoEspecial")
    private Boolean concursoEspecial;

    @JsonProperty("dezenasOrdemSorteio")
    private List<String> dezenasOrdemSorteio;

    @JsonProperty("trevos")
    private List<String> trevos;

    @JsonProperty("timeCoracao")
    private String timeCoracao;

    @JsonProperty("mesSorte")
    private String mesSorte;

    @JsonProperty("estadosPremiados")
    private List<String> estadosPremiados;

    @JsonProperty("localGanhadores")
    private List<LocalGanhadorCaixa> localGanhadores;

    @JsonProperty("valorArrecadado")
    private String valorArrecadado;

    @JsonProperty("valorAcumuladoConcurso_0_5")
    private String valorAcumuladoConcurso_0_5;

    @JsonProperty("valorAcumuladoConcursoEspecial")
    private String valorAcumuladoConcursoEspecial;

    @JsonProperty("valorAcumuladoProximoConcurso")
    private String valorAcumuladoProximoConcurso;

    @JsonProperty("proximoConcurso")
    private Integer proximoConcurso;

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
        private String resultado;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LocalGanhadorCaixa {
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

    public boolean temDezenas() {
        return dezenas != null && !dezenas.isEmpty();
    }

    public boolean isRateioCompleto() {
        return listaRateioPremio != null && !listaRateioPremio.isEmpty()
                && listaRateioPremio.stream().anyMatch(r -> r.getNumeroDeGanhadores() != null && r.getNumeroDeGanhadores() > 0);
    }
}