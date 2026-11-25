package com.loteria.api.controller;

import com.loteria.api.service.LoteriaWorkerService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Endpoint interno protegido para sincronização manual (RF10).
 * Protegido por header compartilhado (X-Internal-Sync-Key).
 * Usado por Google Cloud Scheduler / Cloud Scheduler em ambientes serverless
 * que hibernam sem tráfego.
 */
@RestController
@RequestMapping("/internal/sync")
@RequiredArgsConstructor
@Slf4j
public class InternalSyncController {

    private final LoteriaWorkerService workerService;

    @Value("${loteria.internal-sync.header-name:X-Internal-Sync-Key}")
    private String headerName;

    @Value("${loteria.internal-sync.enabled:true}")
    private boolean enabled;

    /**
     * Sincroniza uma modalidade específica.
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
            workerService.sincronizarModalidade(modalidade);

            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "modalidade", modalidade,
                    "message", "Sincronização iniciada"
            ));

        } catch (Exception e) {
            log.error("Erro na sincronização manual de {}: {}", modalidade, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Falha na sincronização: " + e.getMessage()));
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