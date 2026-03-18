package com.monitor.dto.response;

import com.monitor.domain.enums.MonitorStatus;
import com.monitor.domain.model.Monitor;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO de resposta do Monitor.
 *
 * Não expõe a entidade JPA diretamente — isolamos o contrato
 * da API da modelagem interna do banco.
 */
public record MonitorResponse(
        UUID id,
        String name,
        String url,
        int checkIntervalSeconds,
        int timeoutMs,
        MonitorStatus status,
        Boolean isUp,
        int consecutiveFailures,
        int failureThreshold,
        String webhookUrl,
        String tags,
        Instant nextCheckAt,
        Instant lastCheckedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static MonitorResponse from(Monitor m) {
        return new MonitorResponse(
                m.getId(), m.getName(), m.getUrl(),
                m.getCheckIntervalSeconds(), m.getTimeoutMs(),
                m.getStatus(), m.getIsUp(), m.getConsecutiveFailures(),
                m.getFailureThreshold(), m.getWebhookUrl(), m.getTags(),
                m.getNextCheckAt(), m.getLastCheckedAt(),
                m.getCreatedAt(), m.getUpdatedAt()
        );
    }
}
