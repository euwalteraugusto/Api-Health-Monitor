package com.monitor.dto.response;

import java.util.UUID;

public record MonitorStatsResponse(
        UUID monitorId,
        int periodDays,
        double uptimePercentage,
        double averageResponseTimeMs
) {}
