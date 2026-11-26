package com.loteria.api.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Configuração do Cache Manager com Caffeine (RNF02).
 * Caches:
 * - latestResultado: último concurso por modalidade (TTL 1h fora janela, 30s durante)
 * - resultadoPorConcurso: concurso específico (imutável, TTL longo)
 * - modalidades: lista de modalidades com último concurso (TTL 1h)
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(caffeineCacheBuilder());
        cacheManager.setCacheNames(List.of(
                "latestResultado",
                "resultadoPorConcurso",
                "modalidades"
        ));
        cacheManager.setAllowNullValues(true);
        return cacheManager;
    }

    /**
     * Builder base do Caffeine.
     * TTLs específicos podem ser sobrescritos via @Cacheable(cacheNames="...", key="...", cacheManager="cacheManager")
     * ou programaticamente no serviço.
     */
    private Caffeine<Object, Object> caffeineCacheBuilder() {
        return Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(1, TimeUnit.HOURS)   // default 1h
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .recordStats()
                .weakKeys()
                .weakValues();
    }

    /**
     * Cache específico para 'latestResultado' com TTL dinâmico.
     * O TTL real é controlado via Cache-Control headers no Controller
     * e invalidação (@CacheEvict) pelo Worker ao persistir novo sorteio.
     */
    @Bean
    public Caffeine<Object, Object> latestResultadoCacheBuilder() {
        return Caffeine.newBuilder()
                .maximumSize(100)  // uma entrada por modalidade
                .expireAfterWrite(1, TimeUnit.HOURS)
                .recordStats();
    }

    /**
     * Cache para concursos específicos (imutáveis).
     * TTL longo (1 ano) pois concursos passados não mudam.
     */
    @Bean
    public Caffeine<Object, Object> resultadoPorConcursoCacheBuilder() {
        return Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(365, TimeUnit.DAYS)
                .recordStats();
    }

    /**
     * Cache para lista de modalidades.
     */
    @Bean
    public Caffeine<Object, Object> modalidadesCacheBuilder() {
        return Caffeine.newBuilder()
                .maximumSize(10)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .recordStats();
    }
}