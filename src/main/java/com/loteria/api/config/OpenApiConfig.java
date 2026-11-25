package com.loteria.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Configuração do OpenAPI/Swagger para documentação da API.
 * Disponível em: /swagger-ui.html e /v3/api-docs
 */
@Configuration
public class OpenApiConfig {

    @Value("${server.servlet.context-path:/api/v1}")
    private String contextPath;

    @Value("${server.port:8080}")
    private String port;

    @Bean
    public OpenAPI loteriaOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Loteria API")
                        .description("""
                            API REST para consulta de resultados das loterias da Caixa Econômica Federal.

                            ## Funcionalidades
                            - Consulta do último concurso por modalidade
                            - Consulta de concurso específico
                            - Listagem de todas as modalidades com último concurso
                            - Sincronização manual via endpoint interno (protegido)
                            - Worker automático de coleta durante janelas de apuração (20h-22h30 BRT)

                            ## Endpoints Públicos
                            - `GET /api/v1/resultados/modalidades` - Lista todas as modalidades
                            - `GET /api/v1/resultados/{modalidade}/latest` - Último concurso
                            - `GET /api/v1/resultados/{modalidade}/{concurso}` - Concurso específico

                            ## Endpoints Internos (requerem X-Internal-Sync-Key)
                            - `POST /api/v1/internal/sync/{modalidade}` - Sincronização manual
                            - `GET /api/v1/internal/sync/status` - Status do worker

                            ## Modalidades Suportadas
                            - **megasena** (Ter, Qui, Sáb)
                            - **lotofacil** (Seg a Sáb)
                            - **quina** (Seg a Sáb)
                            - **lotomania** (Ter, Qui, Sáb)
                            - **timemania** (Seg, Qua, Sex)
                            - **duplasena** (Seg, Qua, Sex)
                            - **super7** (Seg, Qua, Sex)
                            - **loteca** (Sáb)

                            ## Cache & Performance
                            - Cache Caffeine com TTL dinâmico
                            - Headers Cache-Control otimizados para Cloudflare:
                              - Concursos finalizados: `max-age=31536000, immutable` (1 ano)
                              - Último concurso (fora janela): `max-age=3600` (1 hora)
                              - Durante apuração: `max-age=30` (30 segundos)

                            ## Autenticação
                            Endpoints públicos não requerem autenticação.
                            Endpoints internos (`/internal/**`) requerem header `X-Internal-Sync-Key`.
                            """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Loteria API")
                                .url("https://github.com/seu-usuario/loteria")
                                .email("suporte@loteria.local"))
                        .license(new License()
                                .name("MIT License")
                                .url("https://opensource.org/licenses/MIT")))
                .servers(List.of(
                        new Server().url("http://localhost:" + port + contextPath).description("Desenvolvimento Local"),
                        new Server().url("https://api.loteria.exemplo.com" + contextPath).description("Produção")
                ))
                .addSecurityItem(new SecurityRequirement().addList("internalSyncKey"))
                .components(new Components()
                        .addSecuritySchemes("internalSyncKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Internal-Sync-Key")
                                .description("Chave compartilhada para endpoints internos de sincronização")));
    }
}