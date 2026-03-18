package com.monitor.dto.request;

import jakarta.validation.constraints.*;

/**
 * DTO de atualização parcial (PATCH).
 * Todos os campos são opcionais — apenas os não-nulos são aplicados.
 */
public record UpdateMonitorRequest(
        @Size(max = 200) String name,
        @Size(max = 2048) @Pattern(regexp = "https?://.+|^$") String url,
        @Min(30) @Max(3600) Integer checkIntervalSeconds,
        @Min(1000) @Max(30000) Integer timeoutMs,
        @Size(max = 2048) String webhookUrl,
        @Size(max = 500) String tags,
        @Min(1) @Max(10) Integer failureThreshold
) {}
