package com.monitor.dto.response;

import com.monitor.domain.enums.CheckResult;
import com.monitor.domain.model.HealthCheck;

import java.time.Instant;
import java.util.UUID;

public record HealthCheckResponse(
        UUID id,
        UUID monitorId,
        CheckResult result,
        Integer httpStatus,
        Long responseTimeMs,
        String errorMessage,
        Instant checkedAt
) {
    public static HealthCheckResponse from(HealthCheck hc) {
        return new HealthCheckResponse(
                hc.getId(),
                hc.getMonitor().getId(),
                hc.getResult(),
                hc.getHttpStatus(),
                hc.getResponseTimeMs(),
                hc.getErrorMessage(),
                hc.getCheckedAt()
        );
    }
}
