package com.monitor.service;

import com.monitor.dto.request.CreateMonitorRequest;
import com.monitor.dto.request.UpdateMonitorRequest;
import com.monitor.dto.response.MonitorResponse;
import com.monitor.dto.response.MonitorStatsResponse;
import com.monitor.dto.response.PageResponse;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface MonitorService {
    MonitorResponse create(CreateMonitorRequest request);
    PageResponse<MonitorResponse> findAll(Pageable pageable);
    MonitorResponse findById(UUID id);
    MonitorResponse update(UUID id, UpdateMonitorRequest request);
    void activate(UUID id);
    void pause(UUID id);
    void delete(UUID id);
    MonitorStatsResponse getStats(UUID id, int days);
}
