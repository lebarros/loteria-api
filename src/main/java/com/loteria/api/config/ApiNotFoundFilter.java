package com.loteria.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Filtro para capturar 404 em rotas de API e retornar resposta padronizada com links para documentação.
 * Necessário porque spring.mvc.throw-exception-if-no-handler-found=false para permitir
 * que resource handlers (Swagger UI, WebJars) funcionem corretamente.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiNotFoundFilter extends OncePerRequestFilter {

    private static final String DOCS_URL = "/api/v1/swagger-ui/index.html";
    private static final String API_DOCS_URL = "/api/v1/v3/api-docs";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // Executa a chain normalmente
        filterChain.doFilter(request, response);

        // Se a resposta for 404 e for uma rota de API (não recurso estático), retorna erro customizado
        if (response.getStatus() == HttpServletResponse.SC_NOT_FOUND) {
            String path = request.getRequestURI();

            // Ignora rotas de recursos estáticos (Swagger UI, WebJars, Actuator, etc.)
            if (isStaticResource(path)) {
                return;
            }

            // Prepara resposta de erro padronizada
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("timestamp", OffsetDateTime.now().toString());
            error.put("status", HttpServletResponse.SC_NOT_FOUND);
            error.put("error", "Not Found");
            error.put("message", "Endpoint não encontrado: " + request.getMethod() + " " + path);
            error.put("path", path);
            error.put("documentation", "Consulte a documentação da API em: " + DOCS_URL + " ou " + API_DOCS_URL);
            error.put("availableEndpoints", Map.of(
                    "GET /api/v1/resultados/modalidades", "Lista todas as modalidades com último concurso",
                    "GET /api/v1/resultados/{modalidade}/latest", "Último concurso de uma modalidade",
                    "GET /api/v1/resultados/{modalidade}/{concurso}", "Concurso específico",
                    "POST /api/v1/internal/sync/{modalidade}", "Sincronização manual (requer X-Internal-Sync-Key)",
                    "GET /api/v1/internal/sync/status", "Status do worker (requer X-Internal-Sync-Key)",
                    "GET /api/v1/actuator/health", "Health check"
            ));

            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);

            try (var writer = response.getWriter()) {
                writer.write(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(error));
            }
        }
    }

    private boolean isStaticResource(String path) {
        return path.startsWith("/api/v1/swagger-ui") ||
               path.startsWith("/api/v1/webjars/") ||
               path.startsWith("/api/v1/actuator") ||
               path.startsWith("/api/v1/v3/api-docs") ||
               path.equals("/api/v1/") ||
               path.equals("/api/v1") ||
               path.matches("/api/v1/.*\\.(css|js|png|ico|map|woff2?|ttf|svg)$");
    }
}