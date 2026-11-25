package com.loteria.api.service;

import com.loteria.api.client.CaixaApiClient;
import com.loteria.api.config.WorkerProperties;
import com.loteria.api.dto.CaixaApiResponse;
import com.loteria.api.entity.Resultado;
import com.loteria.api.entity.ResultadoId;
import com.loteria.api.repository.ResultadoRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
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
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoteriaWorkerService {

    private final CaixaApiClient caixaApiClient;
    private final ResultadoRepository resultadoRepository;
    private final CacheManager cacheManager;
    private final TaskScheduler taskScheduler;
    private final WorkerProperties props;

    // Estado do polling por modalidade
    private final Map<String, ScheduledFuture<?>> pollingTasks = new ConcurrentHashMap<>();
    private final Map<String, Boolean> modalidadeEmPolling = new ConcurrentHashMap<>();

    private static final ZoneId BRASILIA_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @PostConstruct
    public void init() {
        if (!props.isEnabled()) {
            log.info("Worker desabilitado via configuração (loteria.worker.enabled=false)");
            return;
        }
        log.info("LoteriaWorkerService iniciado. Modalidades configuradas: {}", props.getDiasSorteio().keySet());
    }

    /**
     * Verificação periódica (a cada 5 min) para agendar polling nos dias de sorteio.
     * Roda fora da janela para preparar o scheduling do dia.
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
     * Executa uma iteração de polling: consulta API, compara com banco, persiste se novo.
     */
    @Transactional
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
            Optional<CaixaApiResponse> responseOpt = caixaApiClient.buscarUltimoConcurso(modalidade);

            if (responseOpt.isEmpty()) {
                log.warn("Polling {}: resposta vazia da API", modalidade);
                return;
            }

            CaixaApiResponse api = responseOpt.get();

            if (!api.temDezenas()) {
                log.warn("Polling {}: dezenas ainda não disponíveis", modalidade);
                return;
            }

            Integer concursoApi = api.getConcurso();
            if (concursoApi == null) {
                log.warn("Polling {}: número do concurso não informado", modalidade);
                return;
            }

            // Busca último concurso persistido
            Optional<Resultado> ultimoPersistido = resultadoRepository.findLatestByModalidade(modalidade);

            if (ultimoPersistido.isPresent() && ultimoPersistido.get().getConcurso() >= concursoApi) {
                log.debug("Polling {}: concurso {} já persistido (último: {})", modalidade, concursoApi, ultimoPersistido.get().getConcurso());
                return; // já temos este ou mais recente
            }

            // Novo concurso detectado!
            log.info("Polling {}: NOVO CONCURSO detectado #{}", modalidade, concursoApi);

            // Verifica se rateio está completo
            boolean rateioCompleto = api.isRateioCompleto();
            String status = rateioCompleto ? "FINALIZADO" : "APURACAO";

            // Persiste
            Resultado resultado = converterParaEntidade(modalidade, api, status);
            resultadoRepository.save(resultado);

            // Invalida cache (RNF02)
            invalidarCache(modalidade, concursoApi);

            log.info("Polling {}: concurso #{} salvo com status {}", modalidade, concursoApi, status);

            // Circuit Breaker (RF05): se FINALIZADO, para polling desta modalidade
            if ("FINALIZADO".equals(status)) {
                log.info("Circuit breaker: rateio completo para {} #{}. Parando polling até próximo sorteio.", modalidade, concursoApi);
                pararPolling(modalidade);
            }
            // Se APURACAO, continua polling (RF06)

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
     * Converte resposta da API Caixa para entidade Resultado.
     */
    private Resultado converterParaEntidade(String modalidade, CaixaApiResponse api, String status) {
        ResultadoId id = new ResultadoId(modalidade, api.getConcurso());

        // Data de apuração
        LocalDate dataApuracao = parseDate(api.getDataApuracao());

        // Data próximo concurso
        LocalDate dataProximo = parseDate(api.getDataProximoConcurso());

        // Valor estimado
        BigDecimal valorEstimado = parseValor(api.getValorEstimadoProximoConcurso());

        // Dezenas
        List<Object> dezenas = api.getDezenas();

        // Rateio
        List<Map<String, Object>> rateio = new ArrayList<>();
        if (api.getListaRateioPremio() != null) {
            for (CaixaApiResponse.RateioPremio r : api.getListaRateioPremio()) {
                Map<String, Object> faixa = new LinkedHashMap<>();
                faixa.put("descricao", r.getDescricaoFaixa());
                faixa.put("ganhadores", r.getNumeroDeGanhadores());
                faixa.put("valor", parseValor(r.getValorPremio()));
                rateio.add(faixa);
            }
        }

        // Detalhes extras (time do coração, trevos, etc.)
        Map<String, Object> extras = new LinkedHashMap<>();
        if (api.getDetalhesExtras() != null) {
            extras.putAll(api.getDetalhesExtras());
        }
        if (api.getObservacao() != null) {
            extras.put("observacao", api.getObservacao());
        }
        // Loteca: resultados dos jogos
        if (api.getListaResultadoJogos() != null && !api.getListaResultadoJogos().isEmpty()) {
            List<Map<String, Object>> jogos = new ArrayList<>();
            for (CaixaApiResponse.ResultadoJogo j : api.getListaResultadoJogos()) {
                Map<String, Object> jogo = new LinkedHashMap<>();
                jogo.put("numero", j.getJogo());
                jogo.put("time_casa", j.getTimeCasa());
                jogo.put("time_fora", j.getTimeFora());
                jogo.put("resultado", j.getResultado());
                jogos.add(jogo);
            }
            extras.put("jogos", jogos);
        }

        return Resultado.builder()
                .modalidade(modalidade)
                .concurso(api.getConcurso())
                .dataApuracao(dataApuracao)
                .dezenas(dezenas)
                .acumulado(api.getAcumulado() != null ? api.getAcumulado() : false)
                .valorEstimadoProximo(valorEstimado)
                .dataProximoConcurso(dataProximo)
                .rateioPremios(rateio)
                .detalhesExtras(extras)
                .status(status)
                .build();
    }

    /**
     * Invalida caches relacionados à modalidade/concurso.
     */
    private void invalidarCache(String modalidade, Integer concurso) {
        try {
            // Cache: latestResultado
            cacheManager.getCache("latestResultado").evict(modalidade);

            // Cache: resultadoPorConcurso
            cacheManager.getCache("resultadoPorConcurso").evict(modalidade + ":" + concurso);

            // Cache: modalidades (lista completa)
            cacheManager.getCache("modalidades").clear();

            log.debug("Cache invalidado para {} #{}", modalidade, concurso);
        } catch (Exception e) {
            log.warn("Erro ao invalidar cache: {}", e.getMessage());
        }
    }

    // Helpers de parsing

    private LocalDate parseDate(String str) {
        if (str == null || str.isBlank()) return null;
        try {
            return LocalDate.parse(str, DATE_FMT);
        } catch (Exception e) {
            log.warn("Falha ao parsear data '{}': {}", str, e.getMessage());
            return null;
        }
    }

    private BigDecimal parseValor(String str) {
        if (str == null || str.isBlank()) return null;
        try {
            String limpo = str.replace("R$", "").replace(".", "").replace(",", ".").trim();
            return new BigDecimal(limpo);
        } catch (Exception e) {
            log.warn("Falha ao parsear valor '{}': {}", str, e.getMessage());
            return null;
        }
    }

    /**
     * Endpoint manual para forçar sincronização (RF10).
     */
    @Transactional
    public void sincronizarModalidade(String modalidade) {
        log.info("Sincronização manual acionada para {}", modalidade);
        executarPolling(modalidade);
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