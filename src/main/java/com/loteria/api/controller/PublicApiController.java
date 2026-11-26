package com.loteria.api.controller;

import com.loteria.api.constants.ApiConstants;
import com.loteria.api.dto.PublicApiResponse;
import com.loteria.api.entity.Resultado;
import com.loteria.api.repository.ResultadoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Controller PÚBLICO da API de Loterias.
 *
 * Endpoints:
 * - GET /api                    → Lista modalidades (array de strings)
 * - GET /api/{modalidade}       → Histórico completo de uma modalidade
 * - GET /api/{modalidade}/latest → Último concurso
 * - GET /api/{modalidade}/{concurso} → Concurso específico
 *
 * Formato de resposta padrão (campos, tipos, nomes compatíveis com loteriascaixa-api.herokuapp.com).
 * Endpoints internos (/api/v1/internal/*) mantidos separados.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class PublicApiController {

    private final ResultadoRepository resultadoRepository;

    private static final ZoneId BRASILIA_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DATE_FMT_BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final int JANELA_INICIO_HORA = ApiConstants.JANELA_INICIO_HORA;
    private static final int JANELA_FIM_HORA = ApiConstants.JANELA_FIM_HORA;
    private static final int JANELA_FIM_MINUTO = ApiConstants.JANELA_FIM_MINUTO;

    // ============================================================
    // GET /api → Lista modalidades (array de strings)
    // ============================================================
    @GetMapping
    public ResponseEntity<List<String>> listarModalidades() {
        log.debug("GET /api (lista modalidades)");

        // Retorna array simples de strings
        List<String> modalidades = ApiConstants.MODALIDADES_SUPORTADAS.stream()
                .filter(m -> resultadoRepository.countByModalidade(m) > 0)
                .collect(Collectors.toList());

        CacheControl cacheControl = CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic();

        return ResponseEntity.ok()
                .cacheControl(cacheControl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(modalidades);
    }

    // ============================================================
    // GET /api/{modalidade} → Histórico completo (array de objetos)
    // ============================================================
    @GetMapping("/{modalidade}")
    public ResponseEntity<List<PublicApiResponse>> historicoCompleto(@PathVariable String modalidade) {
        String mod = modalidade.toLowerCase();
        log.debug("GET /api/{} (histórico completo)", mod);

        if (!ApiConstants.MODALIDADES_SUPORTADAS.contains(mod)) {
            return ResponseEntity.notFound().build();
        }

        // Busca todos os concursos da modalidade (ordenados por concurso DESC)
        List<Resultado> resultados = resultadoRepository.findByModalidadeOrderByConcursoDesc(mod);

        if (resultados.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<PublicApiResponse> response = resultados.stream()
                .map(this::converterParaPublicApi)
                .collect(Collectors.toList());

        // Histórico é imutável → cache longo
        CacheControl cacheControl = CacheControl.maxAge(365, TimeUnit.DAYS)
                .cachePublic()
                .immutable();

        return ResponseEntity.ok()
                .cacheControl(cacheControl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }

    // ============================================================
    // GET /api/{modalidade}/latest → Último concurso
    // ============================================================
    @GetMapping("/{modalidade}/latest")
    public ResponseEntity<PublicApiResponse> ultimoConcurso(@PathVariable String modalidade) {
        String mod = modalidade.toLowerCase();
        log.debug("GET /api/{}/latest (último concurso)", mod);

        if (!ApiConstants.MODALIDADES_SUPORTADAS.contains(mod)) {
            return ResponseEntity.notFound().build();
        }

        Optional<Resultado> resultadoOpt = resultadoRepository.findLatestByModalidade(mod);

        if (resultadoOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Resultado resultado = resultadoOpt.get();
        PublicApiResponse response = converterParaPublicApi(resultado);

        CacheControl cacheControl = buildCacheControl(resultado, true);

        return ResponseEntity.ok()
                .cacheControl(cacheControl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }

    // ============================================================
    // GET /api/{modalidade}/{concurso} → Concurso específico
    // ============================================================
    @GetMapping("/{modalidade}/{concurso}")
    public ResponseEntity<PublicApiResponse> concursoEspecifico(
            @PathVariable String modalidade,
            @PathVariable Integer concurso) {

        String mod = modalidade.toLowerCase();
        log.debug("GET /api/{}/{} (concurso específico)", mod, concurso);

        if (!ApiConstants.MODALIDADES_SUPORTADAS.contains(mod)) {
            return ResponseEntity.notFound().build();
        }

        Optional<Resultado> resultadoOpt = resultadoRepository.findByModalidadeAndConcurso(mod, concurso);

        if (resultadoOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Resultado resultado = resultadoOpt.get();
        PublicApiResponse response = converterParaPublicApi(resultado);

        // Concurso específico é imutável → cache longo
        CacheControl cacheControl = CacheControl.maxAge(365, TimeUnit.DAYS)
                .cachePublic()
                .immutable();

        return ResponseEntity.ok()
                .cacheControl(cacheControl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }

    // ============================================================
    // CONVERSÃO: Entidade Resultado → Formato da API Pública
    // ============================================================
    private PublicApiResponse converterParaPublicApi(Resultado r) {
        PublicApiResponse dto = new PublicApiResponse();

        // Campos básicos
        dto.setLoteria(r.getModalidade());
        dto.setConcurso(r.getConcurso());
        dto.setData(formatDateBr(r.getDataApuracao()));
        dto.setAcumulou(r.getAcumulado());

        // Dezenas (array de strings)
        dto.setDezenas(r.getDezenasAsString());

        // Dezenas em ordem de sorteio (do detalhes_extras)
        @SuppressWarnings("unchecked")
        List<String> dezenasOrdem = (List<String>) r.getDetalhesExtras().get("dezenasOrdemSorteio");
        dto.setDezenasOrdemSorteio(dezenasOrdem != null ? dezenasOrdem : r.getDezenasAsString());

        // Trevos (para +Milionária)
        @SuppressWarnings("unchecked")
        List<String> trevos = (List<String>) r.getDetalhesExtras().get("trevos");
        dto.setTrevos(trevos != null ? trevos : List.of());

        // Time do coração (Timemania)
        dto.setTimeCoracao((String) r.getDetalhesExtras().get("timeCoracao"));

        // Mês de sorte (+Milionária)
        dto.setMesSorte((String) r.getDetalhesExtras().get("mesSorte"));

        // Local do sorteio
        dto.setLocal((String) r.getDetalhesExtras().get("local"));

        // Concurso especial (Mega da Virada, etc.)
        dto.setConcursoEspecial((Boolean) r.getDetalhesExtras().get("concursoEspecial"));

        // Próximo concurso
        dto.setProximoConcurso((Integer) r.getDetalhesExtras().get("proximoConcurso"));

        // Data próximo concurso
        dto.setDataProximoConcurso(formatDateBr(r.getDataProximoConcurso()));

        // Observação
        dto.setObservacao((String) r.getDetalhesExtras().get("observacao"));

        // Estados premiados
        @SuppressWarnings("unchecked")
        List<String> estadosPremiados = (List<String>) r.getDetalhesExtras().get("estadosPremiados");
        dto.setEstadosPremiados(estadosPremiados != null ? estadosPremiados : List.of());

        // Local ganhadores
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> localGanhadoresRaw = (List<Map<String, Object>>) r.getDetalhesExtras().get("localGanhadores");
        if (localGanhadoresRaw != null) {
            dto.setLocalGanhadores(localGanhadoresRaw.stream()
                    .map(this::converterLocalGanhador)
                    .collect(Collectors.toList()));
        } else {
            dto.setLocalGanhadores(List.of());
        }

        // Valores monetários - usar strings originais da Caixa (salvas em detalhes_extras)
        dto.setValorArrecadado(parseDoubleFromString((String) r.getDetalhesExtras().get("valorArrecadado")));
        dto.setValorAcumuladoConcurso_0_5(parseDoubleFromString((String) r.getDetalhesExtras().get("valorAcumuladoConcurso_0_5")));
        dto.setValorAcumuladoConcursoEspecial(parseDoubleFromString((String) r.getDetalhesExtras().get("valorAcumuladoConcursoEspecial")));
        dto.setValorAcumuladoProximoConcurso(parseDoubleFromString((String) r.getDetalhesExtras().get("valorAcumuladoProximoConcurso")));
        // valorEstimadoProximoConcurso: prioriza string original da Caixa, fallback para campo da entidade
        String valorEstimadoStr = (String) r.getDetalhesExtras().get("valorEstimadoProximoConcurso");
        if (valorEstimadoStr != null) {
            dto.setValorEstimadoProximoConcurso(parseDoubleFromString(valorEstimadoStr));
        } else if (r.getValorEstimadoProximo() != null) {
            // Fallback: entity field is in centavos, but public API expects value that matches their parsing
            // Caixa returns "750000000" which API parses as 75,000,000 (divide by 10, not 100)
            dto.setValorEstimadoProximoConcurso(r.getValorEstimadoProximo().doubleValue() / 10.0);
        }

        // Premiações (rateio) - converter centavos → REAIS
        if (r.getRateioPremios() != null) {
            dto.setPremiacoes(r.getRateioPremios().stream()
                    .map(this::converterPremiacao)
                    .collect(Collectors.toList()));
        } else {
            dto.setPremiacoes(List.of());
        }

        return dto;
    }

    private PublicApiResponse.Premiacao converterPremiacao(Map<String, Object> faixa) {
        PublicApiResponse.Premiacao p = new PublicApiResponse.Premiacao();
        p.setDescricao((String) faixa.get("descricao"));
        p.setFaixa(extractFaixaNumber((String) faixa.get("descricao")));
        p.setGanhadores((Integer) faixa.get("ganhadores"));

        // Valor salvo em centavos (BigDecimal) → converter para REAIS (double)
        Object valorObj = faixa.get("valor");
        if (valorObj instanceof BigDecimal) {
            p.setValorPremio(((BigDecimal) valorObj).doubleValue() / 100.0);
        } else if (valorObj instanceof Number) {
            p.setValorPremio(((Number) valorObj).doubleValue() / 100.0);
        } else {
            p.setValorPremio(0.0);
        }
        return p;
    }

    private PublicApiResponse.LocalGanhador converterLocalGanhador(Map<String, Object> m) {
        PublicApiResponse.LocalGanhador lg = new PublicApiResponse.LocalGanhador();
        lg.setGanhadores((Integer) m.get("ganhadores"));
        lg.setMunicipio((String) m.get("municipio"));
        lg.setNomeFatansiaUL((String) m.get("nomeFatansiaUL"));
        lg.setSerie((String) m.get("serie"));
        lg.setPosicao((Integer) m.get("posicao"));
        lg.setUf((String) m.get("uf"));
        return lg;
    }

    private Integer extractFaixaNumber(String descricao) {
        if (descricao == null) return 0;
        // Extrai número da faixa de descrições como "6 acertos", "5 acertos", "15 acertos"
        try {
            String[] parts = descricao.split("\\s+");
            return Integer.parseInt(parts[0]);
        } catch (Exception e) {
            return 0;
        }
    }

    private String formatDateBr(LocalDate date) {
        if (date == null) return null;
        return date.format(DATE_FMT_BR);
    }

    private Double parseDoubleFromString(String str) {
        if (str == null || str.isBlank()) return null;
        try {
            // A API da Caixa já retorna valores em formato padrão (ex: "51041424.0", "3911431.93")
            // Apenas remove "R$" e espaços, NÃO remove pontos (que são separadores decimais)
            String limpo = str.replace("R$", "").replace(",", ".").trim();
            return Double.parseDouble(limpo);
        } catch (Exception e) {
            return null;
        }
    }

    // ============================================================
    // Cache-Control dinâmico
    // ============================================================
    private CacheControl buildCacheControl(Resultado resultado, boolean isLatest) {
        ZonedDateTime agora = ZonedDateTime.now(BRASILIA_ZONE);
        boolean duranteJanela = isDuranteJanelaApuracao(agora);

        if (!isLatest) {
            return CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable();
        }

        if (duranteJanela) {
            return CacheControl.maxAge(30, TimeUnit.SECONDS).cachePublic();
        } else {
            return CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic();
        }
    }

    private boolean isDuranteJanelaApuracao(ZonedDateTime agora) {
        int hora = agora.getHour();
        int minuto = agora.getMinute();

        if (hora < JANELA_INICIO_HORA || hora > JANELA_FIM_HORA) {
            return false;
        }
        if (hora == JANELA_FIM_HORA && minuto > JANELA_FIM_MINUTO) {
            return false;
        }
        return true;
    }
}