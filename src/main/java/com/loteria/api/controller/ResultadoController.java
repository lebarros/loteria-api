package com.loteria.api.controller;

import com.loteria.api.entity.Resultado;
import com.loteria.api.repository.ResultadoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * API REST pública para consulta de resultados (RF07-RF09).
 * <p>
 * Cache-Control dinâmico (RNF03):
 * - Concursos passados: public, max-age=31536000, immutable
 * - Último concurso (fora janela): public, max-age=3600
 * - Último concurso (durante janela 20h-22h30): public, max-age=30
 */
@RestController
@RequestMapping("/resultados")
@RequiredArgsConstructor
@Slf4j
public class ResultadoController {

    private final ResultadoRepository resultadoRepository;

    private static final ZoneId BRASILIA_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final int JANELA_INICIO_HORA = 20;
    private static final int JANELA_FIM_HORA = 22;
    private static final int JANELA_FIM_MINUTO = 30;

    // ============================================================
    // RF07 - GET /api/v1/{modalidade}/latest
    // ============================================================
    @GetMapping("/{modalidade}/latest")
    public ResponseEntity<Resultado> latest(@PathVariable String modalidade) {
        log.debug("GET /{}/latest", modalidade);

        Optional<Resultado> resultado = resultadoRepository.findLatestByModalidade(modalidade.toLowerCase());

        if (resultado.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Resultado r = resultado.get();
        CacheControl cacheControl = buildCacheControl(r, true);

        return ResponseEntity.ok()
                .cacheControl(cacheControl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(r);
    }

    // ============================================================
    // RF08 - GET /api/v1/{modalidade}/{concurso}
    // ============================================================
    @GetMapping("/{modalidade}/{concurso}")
    public ResponseEntity<Resultado> porConcurso(
            @PathVariable String modalidade,
            @PathVariable Integer concurso) {

        log.debug("GET /{}/{}", modalidade, concurso);

        Optional<Resultado> resultado = resultadoRepository.findByModalidadeAndConcurso(
                modalidade.toLowerCase(), concurso);

        if (resultado.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Resultado r = resultado.get();
        // Concurso específico é imutável -> cache longo
        CacheControl cacheControl = CacheControl.maxAge(365, TimeUnit.DAYS)
                .cachePublic()
                .immutable();

        return ResponseEntity.ok()
                .cacheControl(cacheControl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(r);
    }

    // ============================================================
    // RF09 - GET /api/v1/modalidades
    // ============================================================
    @GetMapping("/modalidades")
    public ResponseEntity<List<Map<String, Object>>> modalidades() {
        log.debug("GET /modalidades");

        List<Object[]> rows = resultadoRepository.findModalidadesComUltimoConcurso();

        List<Map<String, Object>> response = rows.stream()
                .map(row -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("modalidade", row[0]);
                    m.put("ultimo_concurso", row[1]);
                    return m;
                })
                .collect(Collectors.toList());

        // Cache de 1 hora para lista de modalidades
        CacheControl cacheControl = CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic();

        return ResponseEntity.ok()
                .cacheControl(cacheControl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }

    // ============================================================
    // Health check adicional
    // ============================================================
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "timestamp", ZonedDateTime.now(BRASILIA_ZONE).toString(),
                "version", "1.0.0"
        ));
    }

    // ============================================================
    // Helpers para Cache-Control dinâmico (RNF03)
    // ============================================================
    private CacheControl buildCacheControl(Resultado resultado, boolean isLatest) {
        ZonedDateTime agora = ZonedDateTime.now(BRASILIA_ZONE);
        boolean duranteJanela = isDuranteJanelaApuracao(agora);

        if (!isLatest) {
            // Concurso específico (passado) -> imutável, 1 ano
            return CacheControl.maxAge(365, TimeUnit.DAYS)
                    .cachePublic()
                    .immutable();
        }

        // Último concurso
        if (duranteJanela) {
            // Durante janela de apuração -> 30 segundos
            return CacheControl.maxAge(30, TimeUnit.SECONDS).cachePublic();
        } else {
            // Fora da janela -> 1 hora
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