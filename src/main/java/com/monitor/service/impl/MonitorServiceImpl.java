package com.monitor.service.impl;

import com.monitor.domain.enums.MonitorStatus;
import com.monitor.domain.model.Monitor;
import com.monitor.dto.request.CreateMonitorRequest;
import com.monitor.dto.request.UpdateMonitorRequest;
import com.monitor.dto.response.MonitorResponse;
import com.monitor.dto.response.MonitorStatsResponse;
import com.monitor.dto.response.PageResponse;
import com.monitor.exception.MonitorNotFoundException;
import com.monitor.repository.HealthCheckRepository;
import com.monitor.repository.MonitorRepository;
import com.monitor.service.MonitorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Implementação dos casos de uso de gerenciamento de monitors.
 *
 * Separamos a interface MonitorService da implementação seguindo
 * o Dependency Inversion Principle — o controller depende da abstração,
 * não da implementação concreta.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class MonitorServiceImpl implements MonitorService {

    private final MonitorRepository monitorRepository;
    private final HealthCheckRepository healthCheckRepository;

    @Override
    @Transactional
    public MonitorResponse create(CreateMonitorRequest request) {
        Monitor monitor = Monitor.builder()
                .name(request.name())
                .url(request.url())
                .checkIntervalSeconds(request.checkIntervalSeconds())
                .timeoutMs(request.timeoutMs())
                .webhookUrl(request.webhookUrl())
                .tags(request.tags())
                .failureThreshold(request.failureThreshold() != null ? request.failureThreshold() : 2)
                .status(MonitorStatus.ACTIVE)
                .build();

        Monitor saved = monitorRepository.save(monitor);
        log.info("Monitor created: id={} name='{}' url={}", saved.getId(), saved.getName(), saved.getUrl());
        return MonitorResponse.from(saved);
    }

    @Override
    public PageResponse<MonitorResponse> findAll(Pageable pageable) {
        Page<Monitor> page = monitorRepository.findByStatusNot(MonitorStatus.DELETED, pageable);
        return PageResponse.from(page.map(MonitorResponse::from));
    }

    @Override
    public MonitorResponse findById(UUID id) {
        Monitor monitor = getActiveOrThrow(id);
        return MonitorResponse.from(monitor);
    }

    @Override
    @Transactional
    public MonitorResponse update(UUID id, UpdateMonitorRequest request) {
        Monitor monitor = getActiveOrThrow(id);

        if (request.name() != null) monitor.setName(request.name());
        if (request.url() != null) monitor.setUrl(request.url());
        if (request.checkIntervalSeconds() != null) monitor.setCheckIntervalSeconds(request.checkIntervalSeconds());
        if (request.timeoutMs() != null) monitor.setTimeoutMs(request.timeoutMs());
        if (request.webhookUrl() != null) monitor.setWebhookUrl(request.webhookUrl());
        if (request.tags() != null) monitor.setTags(request.tags());
        if (request.failureThreshold() != null) monitor.setFailureThreshold(request.failureThreshold());

        Monitor saved = monitorRepository.save(monitor);
        log.info("Monitor updated: id={}", id);
        return MonitorResponse.from(saved);
    }

    @Override
    @Transactional
    public void activate(UUID id) {
        Monitor monitor = getActiveOrThrow(id);
        monitor.setStatus(MonitorStatus.ACTIVE);
        monitorRepository.save(monitor);
        log.info("Monitor activated: id={}", id);
    }

    @Override
    @Transactional
    public void pause(UUID id) {
        Monitor monitor = getActiveOrThrow(id);
        monitor.setStatus(MonitorStatus.PAUSED);
        monitorRepository.save(monitor);
        log.info("Monitor paused: id={}", id);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        int updated = monitorRepository.softDelete(id);
        if (updated == 0) throw new MonitorNotFoundException(id);
        log.info("Monitor soft-deleted: id={}", id);
    }

    @Override
    public MonitorStatsResponse getStats(UUID id, int days) {
        getActiveOrThrow(id); // valida existência
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);

        double uptime = healthCheckRepository
                .calculateUptimePercentage(id, since)
                .orElse(100.0);

        double avgResponseTime = healthCheckRepository
                .calculateAverageResponseTime(id, since)
                .orElse(0.0);

        return new MonitorStatsResponse(id, days, uptime, avgResponseTime);
    }

    private Monitor getActiveOrThrow(UUID id) {
        return monitorRepository.findByIdAndStatusNot(id, MonitorStatus.DELETED)
                .orElseThrow(() -> new MonitorNotFoundException(id));
    }
}
