package com.loteria.api.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health indicator para liveness probe (Kubernetes/Fly.io).
 * NÃO verifica banco de dados - apenas se a aplicação subiu.
 * Usado para evitar timeout no startup (health check passa rápido).
 */
@Component("appLiveness")
public class LivenessHealthIndicator implements HealthIndicator {

    @Override
    public Health health() {
        // Apenas verifica se a JVM está viva
        // Não testa DB, não testa conexões externas
        return Health.up()
            .withDetail("status", "running")
            .withDetail("check", "liveness")
            .build();
    }
}