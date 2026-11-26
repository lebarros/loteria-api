package com.loteria.api.service;

import com.loteria.api.config.WorkerProperties;
import com.loteria.api.entity.Resultado;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Worker de coleta automática (RF03-RF06).
 * <p>
 * Funcionamento:
 * 1. Agenda execuções apenas nos dias de sorteio de cada modalidade
 * 2. Durante a janela de apuração (20h-22h30 BRT), faz polling a cada 30-60s
 * 3. Detecta novo concurso comparando com último persistido no banco
 * 4. Se rateio incompleto -> salva com status APURACAO e continua polling
 * 5. Se rateio completo -> salva com status FINALIZADO, invalida cache, para polling (RF05)
 * 6. Circuit breaker: para polling da modalidade até próximo dia de sorteio
 * <p>
 * A lógica de sincronização propriamente dita (chamada à API, parse, persistência)
 * é delegada para {@link ResultadoSyncService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoteriaWorkerService {

    private final ResultadoSyncService syncService;
    private final TaskScheduler taskScheduler;
    private final WorkerProperties props;

    // Estado do polling por modalidade
    private final Map<String, ScheduledFuture<?>> pollingTasks = new ConcurrentHashMap<>();
    private final Map<String, Boolean> modalidadeEmPolling = new ConcurrentHashMap<>();

    private static final ZoneId BRASILIA_ZONE = ZoneId.of("America/Sao_Paulo");

    @PostConstruct
    public void init() {
        if (!props.isEnabled()) {
            log.info("Worker desabilitado via configuração (loteria.worker.enabled=false)");
            return;
        }
        log.info("LoteriaWorkerService iniciado. Modalidades configuradas: {}", props.getDiasSorteio().keySet());
    }

    /**
     * Verificação periódica para agendar polling nos dias de sorteio.
     * Roda a cada {@code loteria.worker.polling-interval-segundos} (padrão 30s).
     */
    @Scheduled(fixedRateString = "${loteria.worker.polling-interval-segundos:30}000", initialDelay = 60000)
    public void verificarEAgendarPolling() {
        if (!props.isEnabled()) return;

        ZonedDateTime agora = ZonedDateTime.now(BRASILIA_ZONE);
        int diaSemana = agora.getDayOfWeek().getValue() % 7; // 0=Dom ... 6=Sáb

        props.getDiasSorteio().forEach((modalidade, dias) -> {
            if (dias.contains(diaSemana)) {
                agendarPollingSeNecessario(modalidade, agora);
            } else {
                // Não é dia de sorteio - garante que não está polling
                pararPolling(modalidade);
            }
        });
    }

    /**
     * Agenda o polling para uma modalidade se ainda não estiver rodando
     * e estiver dentro da janela de apuração.
     */
    private void agendarPollingSeNecessario(String modalidade, ZonedDateTime agora) {
        if (modalidadeEmPolling.getOrDefault(modalidade, false)) {
            return; // já está polling
        }

        LocalTime agoraTime = agora.toLocalTime();
        LocalTime inicio = props.getJanelaApuracaoInicio();
        LocalTime fim = props.getJanelaApuracaoFim();

        boolean dentroDaJanela = !agoraTime.isBefore(inicio) && !agoraTime.isAfter(fim);

        if (dentroDaJanela) {
            iniciarPolling(modalidade);
        } else if (agoraTime.isBefore(inicio)) {
            // Agenda para iniciar no início da janela
            long delayMillis = Duration.between(agoraTime, inicio).toMillis();
            taskScheduler.schedule(() -> iniciarPolling(modalidade), Instant.now().plusMillis(delayMillis));
            log.info("Polling agendado para {} às {} (início da janela)", modalidade, inicio);
        }
    }

    /**
     * Inicia o polling periódico para uma modalidade.
     */
    private void iniciarPolling(String modalidade) {
        if (modalidadeEmPolling.getOrDefault(modalidade, false)) {
            return;
        }

        modalidadeEmPolling.put(modalidade, true);
        log.info("Iniciando polling para {} (intervalo: {}s)", modalidade, props.getPollingIntervalSegundos());

        ScheduledFuture<?> future = taskScheduler.scheduleAtFixedRate(
                () -> executarPolling(modalidade),
                props.getPollingIntervalSegundos() * 1000L
        );
        pollingTasks.put(modalidade, future);
    }

    /**
     * Executa uma iteração de polling: delega para ResultadoSyncService.
     * Se forceRefresh=false, o serviço respeita a janela e verifica se já tem o concurso.
     */
    public void executarPolling(String modalidade) {
        // Verifica se ainda está na janela
        ZonedDateTime agora = ZonedDateTime.now(BRASILIA_ZONE);
        LocalTime agoraTime = agora.toLocalTime();

        if (agoraTime.isBefore(props.getJanelaApuracaoInicio()) || agoraTime.isAfter(props.getJanelaApuracaoFim())) {
            log.info("Fora da janela de apuração para {}. Parando polling.", modalidade);
            pararPolling(modalidade);
            return;
        }

        log.debug("Polling {}: consultando API da Caixa...", modalidade);

        try {
            // Delega para o serviço compartilhado (forceRefresh=false = respeita janela/verifica duplicados)
            Resultado resultado = syncService.syncModalidade(modalidade, false);

            if (resultado != null) {
                // Circuit Breaker (RF05): se FINALIZADO, para polling desta modalidade
                if ("FINALIZADO".equals(resultado.getStatus())) {
                    log.info("Circuit breaker: rateio completo para {} #{}. Parando polling até próximo sorteio.",
                            modalidade, resultado.getConcurso());
                    pararPolling(modalidade);
                }
                // Se APURACAO, continua polling (RF06)
            }

        } catch (Exception e) {
            log.error("Erro no polling {}: {}", modalidade, e.getMessage(), e);
        }
    }

    /**
     * Para o polling de uma modalidade.
     */
    private void pararPolling(String modalidade) {
        ScheduledFuture<?> future = pollingTasks.remove(modalidade);
        if (future != null) {
            future.cancel(false);
        }
        modalidadeEmPolling.put(modalidade, false);
    }

    /**
     * Endpoint manual para forçar sincronização (RF10).
     * Usa forceRefresh=true para ignorar janela e forçar atualização.
     */
    public Resultado sincronizarModalidade(String modalidade) {
        log.info("Sincronização manual acionada para {}", modalidade);
        return syncService.syncModalidade(modalidade, true);
    }

    /**
     * Status do worker para monitoramento.
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", props.isEnabled());
        status.put("janelaApuracao", props.getJanelaApuracaoInicio() + " - " + props.getJanelaApuracaoFim());
        status.put("pollingIntervalSegundos", props.getPollingIntervalSegundos());

        Map<String, Object> modalidadesStatus = new LinkedHashMap<>();
        props.getDiasSorteio().forEach((mod, dias) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("diasSorteio", dias);
            m.put("emPolling", modalidadeEmPolling.getOrDefault(mod, false));
            m.put("proximoSorteio", proximoDiaSorteio(mod));
            modalidadesStatus.put(mod, m);
        });
        status.put("modalidades", modalidadesStatus);

        return status;
    }

    private String proximoDiaSorteio(String modalidade) {
        List<Integer> dias = props.getDiasSorteio().get(modalidade);
        if (dias == null || dias.isEmpty()) return "N/A";

        ZonedDateTime agora = ZonedDateTime.now(BRASILIA_ZONE);
        int hoje = agora.getDayOfWeek().getValue() % 7;

        for (int i = 0; i < 7; i++) {
            int dia = (hoje + i) % 7;
            if (dias.contains(dia)) {
                return switch (dia) {
                    case 0 -> "Domingo";
                    case 1 -> "Segunda";
                    case 2 -> "Terça";
                    case 3 -> "Quarta";
                    case 4 -> "Quinta";
                    case 5 -> "Sexta";
                    case 6 -> "Sábado";
                    default -> "?";
                } + (i == 0 ? " (hoje)" : i == 1 ? " (amanhã)" : " (em " + i + " dias)");
            }
        }
        return "N/A";
    }
}