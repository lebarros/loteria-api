package com.loteria.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * Propriedades do Worker de coleta automática (RF03-RF06).
 * Configuradas via application.yml / variáveis de ambiente.
 */
@Component
@ConfigurationProperties(prefix = "loteria.worker")
public class WorkerProperties {

    private boolean enabled = true;

    /**
     * Dias de sorteio por modalidade (0=Domingo ... 6=Sábado).
     * Chave = nome da modalidade (ex: "megasena").
     */
    private Map<String, List<Integer>> diasSorteio = Map.of();

    /**
     * Início da janela de apuração (BRT).
     */
    private LocalTime janelaApuracaoInicio = LocalTime.of(20, 0);

    /**
     * Fim da janela de apuração (BRT).
     */
    private LocalTime janelaApuracaoFim = LocalTime.of(22, 30);

    /**
     * Intervalo de polling em segundos durante a janela.
     */
    private int pollingIntervalSegundos = 30;

    /**
     * Timeout HTTP para chamadas à API da Caixa (segundos).
     */
    private int httpTimeoutSegundos = 10;

    /**
     * Máximo de retries com backoff exponencial.
     */
    private int maxRetries = 3;

    /**
     * User-Agent para evitar bloqueio WAF (RNF04).
     */
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

    // Getters e Setters

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, List<Integer>> getDiasSorteio() {
        return diasSorteio;
    }

    public void setDiasSorteio(Map<String, List<Integer>> diasSorteio) {
        this.diasSorteio = diasSorteio;
    }

    public LocalTime getJanelaApuracaoInicio() {
        return janelaApuracaoInicio;
    }

    public void setJanelaApuracaoInicio(LocalTime janelaApuracaoInicio) {
        this.janelaApuracaoInicio = janelaApuracaoInicio;
    }

    public LocalTime getJanelaApuracaoFim() {
        return janelaApuracaoFim;
    }

    public void setJanelaApuracaoFim(LocalTime janelaApuracaoFim) {
        this.janelaApuracaoFim = janelaApuracaoFim;
    }

    public int getPollingIntervalSegundos() {
        return pollingIntervalSegundos;
    }

    public void setPollingIntervalSegundos(int pollingIntervalSegundos) {
        this.pollingIntervalSegundos = pollingIntervalSegundos;
    }

    public int getHttpTimeoutSegundos() {
        return httpTimeoutSegundos;
    }

    public void setHttpTimeoutSegundos(int httpTimeoutSegundos) {
        this.httpTimeoutSegundos = httpTimeoutSegundos;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }
}