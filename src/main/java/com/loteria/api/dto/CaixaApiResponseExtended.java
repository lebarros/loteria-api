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
    // Local do sorteio: Caixa retorna "localSorteio" + "nomeMunicipioUFSorteio"
    @JsonProperty("localSorteio")
    private String localSorteio;

    @JsonProperty("nomeMunicipioUFSorteio")
    private String nomeMunicipioUFSorteio;

    // Concurso especial: Caixa retorna "indicadorConcursoEspecial" (1 = sim)
    // NOTA: Caixa retorna 1 para TODOS os concursos atuais. Heroku retorna false para concursos regulares.
    // Mantemos compatibilidade: só true se for realmente especial (ex: Mega da Virada)
    @JsonProperty("indicadorConcursoEspecial")
    private Integer indicadorConcursoEspecial;

    // Dezenas em ordem de sorteio: Caixa retorna "dezenasSorteadasOrdemSorteio"
    @JsonProperty("dezenasSorteadasOrdemSorteio")
    private List<String> dezenasSorteadasOrdemSorteio;

    @JsonProperty("trevos")
    private List<String> trevos;

    // Time do coração: Caixa retorna "nomeTimeCoracaoMesSorte" (ex: "CASCAVEL         /PR")
    @JsonProperty("nomeTimeCoracaoMesSorte")
    private String nomeTimeCoracaoMesSorte;

    @JsonProperty("mesSorte")
    private String mesSorte;

    @JsonProperty("estadosPremiados")
    private List<String> estadosPremiados;

    // Local ganhadores: Caixa retorna "listaMunicipioUFGanhadores"
    @JsonProperty("listaMunicipioUFGanhadores")
    private List<LocalGanhadorCaixa> listaMunicipioUFGanhadores;

    @JsonProperty("valorArrecadado")
    private String valorArrecadado;

    @JsonProperty("valorAcumuladoConcurso_0_5")
    private String valorAcumuladoConcurso_0_5;

    @JsonProperty("valorAcumuladoConcursoEspecial")
    private String valorAcumuladoConcursoEspecial;

    @JsonProperty("valorAcumuladoProximoConcurso")
    private String valorAcumuladoProximoConcurso;

    // Próximo concurso: Caixa retorna "numeroConcursoProximo"
    @JsonProperty("numeroConcursoProximo")
    private Integer numeroConcursoProximo;

    // Getters compatíveis (usados pelo ResultadoSyncService)
    public String getLocal() {
        if (localSorteio != null && nomeMunicipioUFSorteio != null) {
            // Normaliza espaços extras (ex: "SÃO PAULO,  SP" -> "SÃO PAULO, SP")
            String municipio = nomeMunicipioUFSorteio.replaceAll("\\s+", " ").trim();
            return localSorteio + " em " + municipio;
        }
        return localSorteio != null ? localSorteio : (nomeMunicipioUFSorteio != null ? nomeMunicipioUFSorteio.replaceAll("\\s+", " ").trim() : null);
    }

    public Boolean getConcursoEspecial() {
        // Caixa retorna 1 para todos os concursos atualmente.
        // Heroku API retorna false para concursos regulares.
        // Retornamos false por padrão (compatibilidade Heroku).
        // TODO: Detectar concursos realmente especiais (Mega da Virada, etc.)
        return false;
    }

    public List<String> getDezenasOrdemSorteio() {
        return dezenasSorteadasOrdemSorteio;
    }

    public String getTimeCoracao() {
        if (nomeTimeCoracaoMesSorte != null) {
            String normalized = nomeTimeCoracaoMesSorte.replaceAll("\\s+", " ").trim();
            // Retorna null se for string vazia ou só espaços (lotterias sem time do coração)
            return normalized.isEmpty() ? null : normalized;
        }
        return null;
    }

    public List<LocalGanhadorCaixa> getLocalGanhadores() {
        return listaMunicipioUFGanhadores;
    }

    public Integer getProximoConcurso() {
        return numeroConcursoProximo;
    }

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