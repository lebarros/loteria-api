package com.loteria.api.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handler global de exceções.
 * Retorna respostas padronizadas e aponta para a documentação em caso de erro 404.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final String DOCS_URL = "/api/v1/swagger-ui/index.html";
    private static final String API_DOCS_URL = "/api/v1/v3/api-docs";

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(
            NoHandlerFoundException ex, HttpServletRequest request) {

        Map<String, Object> error = new LinkedHashMap<>();
        error.put("timestamp", OffsetDateTime.now().toString());
        error.put("status", HttpStatus.NOT_FOUND.value());
        error.put("error", "Not Found");
        error.put("message", "Endpoint não encontrado: " + ex.getHttpMethod() + " " + ex.getRequestURL());
        error.put("path", request.getRequestURI());
        error.put("documentation", "Consulte a documentação da API em: " + DOCS_URL + " ou " + API_DOCS_URL);
        error.put("availableEndpoints", Map.of(
                "GET /api/v1/resultados/modalidades", "Lista todas as modalidades com último concurso",
                "GET /api/v1/resultados/{modalidade}/latest", "Último concurso de uma modalidade",
                "GET /api/v1/resultados/{modalidade}/{concurso}", "Concurso específico",
                "POST /api/v1/internal/sync/{modalidade}", "Sincronização manual (requer X-Internal-Sync-Key)",
                "GET /api/v1/internal/sync/status", "Status do worker (requer X-Internal-Sync-Key)",
                "GET /api/v1/actuator/health", "Health check"
        ));

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(
            Exception ex, HttpServletRequest request) {

        Map<String, Object> error = new LinkedHashMap<>();
        error.put("timestamp", OffsetDateTime.now().toString());
        error.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        error.put("error", "Internal Server Error");
        error.put("message", ex.getMessage());
        error.put("path", request.getRequestURI());
        error.put("documentation", "Consulte a documentação da API em: " + DOCS_URL + " ou " + API_DOCS_URL);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
}