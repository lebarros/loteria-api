package com.loteria.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.loteria.api")  // Restringe scan (startup mais rápido)
@EnableCaching
@EnableRetry
@EnableScheduling
public class LoteriaApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoteriaApiApplication.class, args);
    }
}