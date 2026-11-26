package com.loteria.api.constants;

import java.util.List;

/**
 * Constantes compartilhadas da API.
 * Centraliza listas de modalidades, endpoints, etc. para evitar duplicação.
 */
public final class ApiConstants {

    private ApiConstants() {
        // Utility class
    }

    /**
     * Modalidades suportadas (ordem alfabética).
     * Nomes internos (usados na URL e no banco).
     */
    public static final List<String> MODALIDADES_SUPORTADAS = List.of(
            "duplasena", "loteca", "lotofacil", "lotomania",
            "megasena", "quina", "super7", "timemania"
    );

    /**
     * Endpoints públicos (/api).
     */
    public static final class PublicEndpoints {
        public static final String BASE = "/api";
        public static final String MODALIDADES = BASE;
        public static final String HISTORICO = BASE + "/{modalidade}";
        public static final String LATEST = BASE + "/{modalidade}/latest";
        public static final String CONCURSO = BASE + "/{modalidade}/{concurso}";
    }

    /**
     * Endpoints internos protegidos (/api/v1/internal/sync).
     */
    public static final class InternalEndpoints {
        public static final String BASE = "/api/v1/internal/sync";
        public static final String SYNC = BASE + "/{modalidade}";
        public static final String REFRESH = BASE + "/refresh/{modalidade}";
        public static final String REFRESH_ALL = BASE + "/refresh/all";
        public static final String STATUS = BASE + "/status";
    }

    /**
     * Header de autenticação interna.
     */
    public static final String INTERNAL_SYNC_HEADER = "X-Internal-Sync-Key";

    /**
     * Janela de apuração padrão (BRT).
     */
    public static final int JANELA_INICIO_HORA = 20;
    public static final int JANELA_FIM_HORA = 22;
    public static final int JANELA_FIM_MINUTO = 30;
}