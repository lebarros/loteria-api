package com.loteria.api.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loteria.api.config.WorkerProperties;
import com.loteria.api.dto.CaixaApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Cliente para consumir a API JSON oficial da Caixa (RF03).
 * Endpoints: servicebus2.caixa.gov.br/portaldeloterias/api/{modalidade}
 *            servicebus2.caixa.gov.br/portaldeloterias/api/{modalidade}/{concurso}
 *
 * RNF04: Headers de navegador (User-Agent, Accept) para evitar 403/429
 * RNF05: Timeouts curtos + retry com backoff exponencial
 */
@Component
public class CaixaApiClient {

    private static final Logger log = LoggerFactory.getLogger(CaixaApiClient.class);

    private final RestClient restClient;
    private final String baseUrl;
    private final ObjectMapper objectMapper;

    // Mapeamento: nome interno -> nome na API da Caixa
    private static final Map<String, String> CAIXA_MODALIDADE_MAP = Map.of(
            "megasena", "megasena",
            "lotofacil", "lotofacil",
            "quina", "quina",
            "lotomania", "lotomania",
            "timemania", "timemania",
            "duplasena", "duplasena",
            "super7", "supersete",      // Caixa usa "supersete"
            "loteca", "loteca"
    );

    public CaixaApiClient(WorkerProperties props, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.baseUrl = "https://servicebus2.caixa.gov.br/portaldeloterias/api";

        var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(props.getHttpTimeoutSegundos()));
        requestFactory.setReadTimeout(Duration.ofSeconds(props.getHttpTimeoutSegundos()));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.USER_AGENT, props.getUserAgent())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "pt-BR,pt;q=0.9,en;q=0.8")
                .defaultHeader(HttpHeaders.CONNECTION, "keep-alive")
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Converte nome interno para nome usado na API da Caixa.
     */
    private String toCaixaModalidade(String modalidade) {
        return CAIXA_MODALIDADE_MAP.getOrDefault(modalidade.toLowerCase(), modalidade.toLowerCase());
    }

    /**
     * Busca o último concurso de uma modalidade.
     * GET /api/{modalidade}
     */
    @Retryable(
            retryFor = {Exception.class},
            maxAttemptsExpression = "#{@workerProperties.maxRetries}",
            backoff = @Backoff(delayExpression = "#{@workerProperties.httpTimeoutSegundos * 1000}", multiplier = 2)
    )
    public Optional<CaixaApiResponse> buscarUltimoConcurso(String modalidade) {
        String caixaModalidade = toCaixaModalidade(modalidade);
        String url = "/" + caixaModalidade;
        log.debug("Buscando último concurso da Caixa: {} (interno: {})", url, modalidade);

        try {
            String response = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);

            if (response == null || response.isBlank()) {
                log.warn("Resposta vazia para {}", url);
                return Optional.empty();
            }

            CaixaApiResponse dto = objectMapper.readValue(response, CaixaApiResponse.class);
            log.debug("Resposta recebida para {}: concurso={}", modalidade, dto.getConcurso());
            return Optional.of(dto);

        } catch (Exception e) {
            log.warn("Erro ao buscar último concurso {}: {}", modalidade, e.getMessage());
            throw new RuntimeException("Falha ao consultar API Caixa: " + url, e);
        }
    }

    /**
     * Busca um concurso específico.
     * GET /api/{modalidade}/{concurso}
     */
    @Retryable(
            retryFor = {Exception.class},
            maxAttemptsExpression = "#{@workerProperties.maxRetries}",
            backoff = @Backoff(delayExpression = "#{@workerProperties.httpTimeoutSegundos * 1000}", multiplier = 2)
    )
    public Optional<CaixaApiResponse> buscarConcurso(String modalidade, int concurso) {
        String caixaModalidade = toCaixaModalidade(modalidade);
        String url = "/" + caixaModalidade + "/" + concurso;
        log.debug("Buscando concurso {} da Caixa: {} (interno: {})", concurso, url, modalidade);

        try {
            String response = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);

            if (response == null || response.isBlank()) {
                log.warn("Resposta vazia para {}", url);
                return Optional.empty();
            }

            CaixaApiResponse dto = objectMapper.readValue(response, CaixaApiResponse.class);
            log.debug("Resposta recebida para {} #{}: {}", modalidade, concurso, dto);
            return Optional.of(dto);

        } catch (Exception e) {
            log.warn("Erro ao buscar concurso {} #{}: {}", modalidade, concurso, e.getMessage());
            throw new RuntimeException("Falha ao consultar API Caixa: " + url, e);
        }
    }

    /**
     * Recovery method para @Retryable - chamado após esgotar retries.
     */
    @Recover
    public Optional<CaixaApiResponse> recoverBuscarUltimoConcurso(Exception e, String modalidade) {
        log.error("Falha definitiva ao buscar último concurso {} após retries: {}", modalidade, e.getMessage());
        return Optional.empty();
    }

    @Recover
    public Optional<CaixaApiResponse> recoverBuscarConcurso(Exception e, String modalidade, int concurso) {
        log.error("Falha definitiva ao buscar concurso {} #{} após retries: {}", modalidade, concurso, e.getMessage());
        return Optional.empty();
    }
}