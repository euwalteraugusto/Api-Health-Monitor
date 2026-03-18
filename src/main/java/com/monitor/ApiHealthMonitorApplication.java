package com.monitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * API Health Monitor — Entry Point
 *
 * Sistema de monitoramento de disponibilidade de APIs com:
 *  - Virtual Threads (Java 21) para concorrência massiva
 *  - WebClient reativo para requisições HTTP não-bloqueantes
 *  - Circuit Breaker + Retry para resiliência
 *  - Notificações via Webhook (Discord/Telegram)
 *  - Métricas Prometheus para observabilidade
 */
@SpringBootApplication
@EnableScheduling
public class ApiHealthMonitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiHealthMonitorApplication.class, args);
    }
}
