package com.loteria.api.controller;

import com.loteria.api.entity.Resultado;
import com.loteria.api.service.LoteriaWorkerService;
import com.loteria.api.service.ResultadoSyncService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Endpoint interno protegido para sincronização manual (RF10).
 * Protegido por header compartilhado (X-Internal-Sync-Key).
 * Usado por Google Cloud Scheduler / Cloud Scheduler em ambientes serverless
 * que hibernam sem tráfego.
 */
@RestController
@RequestMapping("/api/v1/internal/sync")
@RequiredArgsConstructor
@Slf4j
public class InternalSyncController {

    private final LoteriaWorkerService workerService;
    private final ResultadoSyncService syncService;

    @Value("${loteria.internal-sync.header-name:X-Internal-Sync-Key}")
    private String headerName;

    @Value("${loteria.internal-sync.enabled:true}")
    private boolean enabled;

    /**
     * Sincroniza uma modalidade específica (respeita janela de apuração).
     * POST /internal/sync/{modalidade}
     * Header: X-Internal-Sync-Key: <shared-secret>
     */
    @PostMapping("/{modalidade}")
    public ResponseEntity<Map<String, Object>> sincronizar(
            @PathVariable String modalidade,
            HttpServletRequest request) {

        if (!enabled) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Endpoint desabilitado"));
        }

        // Verifica autenticação interna
        String providedKey = request.getHeader(headerName);
        String expectedKey = System.getenv("INTERNAL_SYNC_KEY");

        if (expectedKey == null || expectedKey.isBlank()) {
            log.warn("INTERNAL_SYNC_KEY não configurado no ambiente");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Configuração de segurança ausente"));
        }

        if (!expectedKey.equals(providedKey)) {
            log.warn("Tentativa de acesso não autorizado a /internal/sync/{} de {}", modalidade, request.getRemoteAddr());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Não autorizado"));
        }

        try {
            log.info("Sincronização manual acionada para {} via {}", modalidade, headerName);
            // forceRefresh=true para forçar mesmo fora da janela
            Resultado resultado = workerService.sincronizarModalidade(modalidade);

            if (resultado == null) {
                return ResponseEntity.ok(Map.of(
                        "status", "no_data",
                        "modalidade", modalidade,
                        "message", "Nenhum dado novo encontrado na API da Caixa"
                ));
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "ok");
            response.put("modalidade", modalidade);
            response.put("message", "Sincronização concluída");
            response.put("resultado", resultado);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Erro na sincronização manual de {}: {}", modalidade, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Falha na sincronização: " + e.getMessage()));
        }
    }

    /**
     * Força atualização de uma modalidade ignorando a janela de apuração.
     * POST /internal/sync/refresh/{modalidade}
     * Header: X-Internal-Sync-Key: <shared-secret>
     *
     * Diferente de /sync/{modalidade}, este endpoint:
     * - Ignora a janela de apuração (20h-22h30)
     * - Retorna o resultado completo
     * - Útil para atualizações manuais fora do horário
     */
    @PostMapping("/refresh/{modalidade}")
    public ResponseEntity<Map<String, Object>> forceRefresh(
            @PathVariable String modalidade,
            HttpServletRequest request) {

        if (!enabled) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Endpoint desabilitado"));
        }

        // Verifica autenticação interna (mesma chave)
        String providedKey = request.getHeader(headerName);
        String expectedKey = System.getenv("INTERNAL_SYNC_KEY");

        if (expectedKey == null || expectedKey.isBlank()) {
            log.warn("INTERNAL_SYNC_KEY não configurado no ambiente");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Configuração de segurança ausente"));
        }

        if (!expectedKey.equals(providedKey)) {
            log.warn("Tentativa de acesso não autorizado a /internal/sync/refresh/{} de {}", modalidade, request.getRemoteAddr());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Não autorizado"));
        }

        try {
            log.info("Force refresh acionado para {} via {}", modalidade, headerName);
            // forceRefresh=true para ignorar janela e verificar/atualizar
            Resultado resultado = syncService.syncModalidade(modalidade, true);

            if (resultado == null) {
                return ResponseEntity.ok(Map.of(
                        "status", "no_data",
                        "modalidade", modalidade,
                        "message", "Nenhum dado disponível na API da Caixa para esta modalidade"
                ));
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "ok");
            response.put("modalidade", modalidade);
            response.put("message", "Atualização forçada concluída");
            response.put("resultado", resultado);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Erro no force refresh de {}: {}", modalidade, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Falha na atualização forçada: " + e.getMessage()));
        }
    }

    /**
     * Força atualização de TODAS as modalidades de uma vez.
     * POST /internal/sync/refresh/all
     * Header: X-Internal-Sync-Key: <shared-secret>
     *
     * Útil para atualização em lote (ex: cron diário).
     * Retorna resumo com resultados por modalidade.
     */
    @PostMapping("/refresh/all")
    public ResponseEntity<Map<String, Object>> forceRefreshAll(HttpServletRequest request) {

        if (!enabled) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Endpoint desabilitado"));
        }

        // Verifica autenticação interna
        String providedKey = request.getHeader(headerName);
        String expectedKey = System.getenv("INTERNAL_SYNC_KEY");

        if (expectedKey == null || expectedKey.isBlank()) {
            log.warn("INTERNAL_SYNC_KEY não configurado no ambiente");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Configuração de segurança ausente"));
        }

        if (!expectedKey.equals(providedKey)) {
            log.warn("Tentativa de acesso não autorizado a /internal/sync/refresh/all de {}", request.getRemoteAddr());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Não autorizado"));
        }

        try {
            log.info("Force refresh ALL acionado via {}", headerName);

            // Lista de modalidades suportadas
            List<String> modalidades = List.of(
                    "megasena", "lotofacil", "quina", "lotomania",
                    "timemania", "duplasena", "super7", "loteca"
            );

            Map<String, Object> resultados = new LinkedHashMap<>();
            int sucessos = 0;
            int semDados = 0;
            int erros = 0;

            for (String modalidade : modalidades) {
                try {
                    Resultado resultado = syncService.syncModalidade(modalidade, true);

                    Map<String, Object> r = new LinkedHashMap<>();
                    if (resultado == null) {
                        r.put("status", "no_data");
                        r.put("message", "Nenhum dado disponível");
                        semDados++;
                    } else {
                        r.put("status", "ok");
                        r.put("concurso", resultado.getConcurso());
                        r.put("data_apuracao", resultado.getDataApuracao());
                        r.put("status_concurso", resultado.getStatus());
                        sucessos++;
                    }
                    resultados.put(modalidade, r);

                } catch (Exception e) {
                    log.error("Erro no force refresh de {}: {}", modalidade, e.getMessage(), e);
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("status", "error");
                    r.put("message", e.getMessage());
                    resultados.put(modalidade, r);
                    erros++;
                }
            }

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total", modalidades.size());
            summary.put("sucessos", sucessos);
            summary.put("sem_dados", semDados);
            summary.put("erros", erros);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", erros > 0 ? "partial" : "ok");
            response.put("summary", summary);
            response.put("resultados", resultados);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Erro no force refresh all: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Falha na atualização em lote: " + e.getMessage()));
        }
    }

    /**
     * Status do worker (para monitoramento).
     * GET /internal/sync/status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest request) {
        if (!enabled) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        String providedKey = request.getHeader(headerName);
        String expectedKey = System.getenv("INTERNAL_SYNC_KEY");

        if (expectedKey == null || !expectedKey.equals(providedKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return ResponseEntity.ok(workerService.getStatus());
    }
}