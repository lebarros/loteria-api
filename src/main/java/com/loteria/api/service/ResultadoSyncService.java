package com.loteria.api.service;

import com.loteria.api.client.CaixaApiClient;
import com.loteria.api.dto.CaixaApiResponse;
import com.loteria.api.entity.Resultado;
import com.loteria.api.entity.ResultadoId;
import com.loteria.api.repository.ResultadoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serviço compartilhado para sincronização de resultados da Caixa.
 * Usado tanto pelo Worker agendado quanto pelo endpoint manual de refresh.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ResultadoSyncService {

    private final CaixaApiClient caixaApiClient;
    private final ResultadoRepository resultadoRepository;
    private final CacheManager cacheManager;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * Sincroniza uma modalidade consultando a API da Caixa.
     *
     * @param modalidade      nome da modalidade (ex: "megasena")
     * @param forceRefresh    se true, ignora a janela de apuração e força a consulta
     * @return o resultado salvo/atualizado, ou null se não houver dados novos
     */
    @Transactional
    public Resultado syncModalidade(String modalidade, boolean forceRefresh) {
        log.info("Iniciando sincronização {} para {}", forceRefresh ? "forçada" : "agendada", modalidade);

        // 1. Consulta API da Caixa
        Optional<CaixaApiResponse> responseOpt = caixaApiClient.buscarUltimoConcurso(modalidade);

        if (responseOpt.isEmpty()) {
            log.warn("Sincronização {}: resposta vazia da API para {}", forceRefresh ? "forçada" : "agendada", modalidade);
            return null;
        }

        CaixaApiResponse api = responseOpt.get();

        // 2. Validações básicas
        if (!api.temDezenas()) {
            log.warn("Sincronização {}: dezenas ainda não disponíveis para {}", forceRefresh ? "forçada" : "agendada", modalidade);
            return null;
        }

        Integer concursoApi = api.getConcurso();
        if (concursoApi == null) {
            log.warn("Sincronização {}: número do concurso não informado para {}", forceRefresh ? "forçada" : "agendada", modalidade);
            return null;
        }

        // 3. Busca último concurso persistido
        Optional<Resultado> ultimoPersistido = resultadoRepository.findLatestByModalidade(modalidade);

        // Se não for forceRefresh e já temos este concurso, não faz nada
        if (!forceRefresh && ultimoPersistido.isPresent() && ultimoPersistido.get().getConcurso() >= concursoApi) {
            log.debug("Sincronização agendada: concurso {} já persistido (último: {})", concursoApi, ultimoPersistido.get().getConcurso());
            return ultimoPersistido.get();
        }

        // 4. Novo concurso detectado ou forceRefresh
        log.info("Sincronização {}: NOVO CONCURSO detectado #{} para {}", forceRefresh ? "forçada" : "agendada", concursoApi, modalidade);

        // Verifica se rateio está completo
        boolean rateioCompleto = api.isRateioCompleto();
        String status = rateioCompleto ? "FINALIZADO" : "APURACAO";

        // 5. Persiste
        Resultado resultado = converterParaEntidade(modalidade, api, status);
        Resultado saved = resultadoRepository.save(resultado);

        // 6. Invalida cache
        invalidarCache(modalidade, concursoApi);

        log.info("Sincronização {}: concurso #{} salvo com status {}", forceRefresh ? "forçada" : "agendada", concursoApi, status);

        return saved;
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
}