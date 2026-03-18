package com.monitor.dto.request;

import jakarta.validation.constraints.*;

/**
 * DTO de criação de Monitor.
 *
 * Usamos Java Records para DTOs: imutáveis por padrão,
 * sem boilerplate de getters/setters, equals/hashCode gerados.
 */
public record CreateMonitorRequest(

        @NotBlank(message = "Name is required")
        @Size(max = 200, message = "Name must be at most 200 characters")
        String name,

        @NotBlank(message = "URL is required")
        @Size(max = 2048)
        @Pattern(regexp = "https?://.+", message = "URL must start with http:// or https://")
        String url,

        @Min(value = 30, message = "Check interval must be at least 30 seconds")
        @Max(value = 3600, message = "Check interval must be at most 3600 seconds (1 hour)")
        Integer checkIntervalSeconds,

        @Min(value = 1000, message = "Timeout must be at least 1000ms")
        @Max(value = 30000, message = "Timeout must be at most 30000ms")
        Integer timeoutMs,

        @Size(max = 2048)
        String webhookUrl,

        @Size(max = 500)
        String tags,

        @Min(1) @Max(10)
        Integer failureThreshold
) {
    // Compact constructor para aplicar defaults
    public CreateMonitorRequest {
        if (checkIntervalSeconds == null) checkIntervalSeconds = 60;
        if (timeoutMs == null) timeoutMs = 5000;
        if (failureThreshold == null) failureThreshold = 2;
    }
}
