package com.monitor.service;

import com.monitor.domain.model.HealthCheck;
import com.monitor.domain.model.Monitor;
import com.monitor.repository.HealthCheckRepository;
import com.monitor.repository.MonitorRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serviço central de monitoramento.
 *
 * Responsabilidades:
 * 1. Orquestrar uma verificação completa de um monitor
 * 2. Persistir o resultado (HealthCheck)
 * 3. Atualizar o estado do Monitor (isUp, consecutiveFailures, etc.)
 * 4. Disparar notificações quando necessário
 * 5. Registrar métricas
 *
 * Esta é a classe mais importante do sistema — aqui toda a lógica
 * de negócio de monitoramento se conecta.
 */
@Service
@Slf4j
public class MonitoringService {

    private final HttpCheckClient httpCheckClient;
    private final MonitorRepository monitorRepository;
    private final HealthCheckRepository healthCheckRepository;
    private final NotificationService notificationService;
    private final MeterRegistry meterRegistry;

    // Contadores para Prometheus
    private final Counter checksTotal;
    private final Counter checksSuccess;
    private final Counter checksFailure;
    private final Counter alertsDown;
    private final Counter alertsRecovered;

    public MonitoringService(
            HttpCheckClient httpCheckClient,
            MonitorRepository monitorRepository,
            HealthCheckRepository healthCheckRepository,
            NotificationService notificationService,
            MeterRegistry meterRegistry) {
        this.httpCheckClient = httpCheckClient;
        this.monitorRepository = monitorRepository;
        this.healthCheckRepository = healthCheckRepository;
        this.notificationService = notificationService;
        this.meterRegistry = meterRegistry;

        // Inicializa contadores Prometheus
        this.checksTotal = Counter.builder("monitor.checks.total")
                .description("Total de verificações realizadas")
                .register(meterRegistry);
        this.checksSuccess = Counter.builder("monitor.checks.success")
                .description("Verificações bem-sucedidas")
                .register(meterRegistry);
        this.checksFailure = Counter.builder("monitor.checks.failure")
                .description("Verificações com falha")
                .register(meterRegistry);
        this.alertsDown = Counter.builder("monitor.alerts.down")
                .description("Alertas de serviço indisponível enviados")
                .register(meterRegistry);
        this.alertsRecovered = Counter.builder("monitor.alerts.recovered")
                .description("Alertas de recuperação enviados")
                .register(meterRegistry);
    }

    /**
     * Executa uma verificação completa para um monitor.
     *
     * Fluxo:
     * 1. Executa requisição HTTP (via HttpCheckClient)
     * 2. Persiste o resultado como HealthCheck
     * 3. Atualiza estado do Monitor
     * 4. Dispara notificação se necessário
     * 5. Persiste estado atualizado do Monitor
     *
     * @param monitor Monitor a verificar
     */
    @Transactional
    public void performCheck(Monitor monitor) {
        log.debug("Performing check for monitor={} name='{}'", monitor.getId(), monitor.getName());

        checksTotal.increment();

        // Captura estado anterior para detectar transições UP→DOWN e DOWN→UP
        boolean wasDown = Boolean.FALSE.equals(monitor.getIsUp());

        // ── Step 1: Executa a verificação HTTP ──────────────────────────────
        HealthCheck check = httpCheckClient.check(monitor);

        // ── Step 2: Persiste o resultado ────────────────────────────────────
        check = healthCheckRepository.save(check);

        // ── Step 3: Atualiza estado do Monitor e detecta transições ─────────
        boolean justWentDown = false;
        boolean justRecovered = false;

        if (check.isSuccess()) {
            checksSuccess.increment();
            justRecovered = wasDown; // estava DOWN e agora está UP
            monitor.recordSuccess();
        } else {
            checksFailure.increment();
            justWentDown = monitor.recordFailure(); // retorna true quando atinge threshold
        }

        // ── Step 4: Notificações ─────────────────────────────────────────────
        if (justWentDown) {
            alertsDown.increment();
            log.warn("Monitor DOWN: id={} name='{}' url={} consecutiveFailures={}",
                    monitor.getId(), monitor.getName(), monitor.getUrl(),
                    monitor.getConsecutiveFailures());
            notificationService.notifyDown(monitor, check);
        }

        if (justRecovered) {
            alertsRecovered.increment();
            log.info("Monitor RECOVERED: id={} name='{}' url={}",
                    monitor.getId(), monitor.getName(), monitor.getUrl());
            notificationService.notifyRecovered(monitor, check);
        }

        // ── Step 5: Persiste estado atualizado ───────────────────────────────
        monitorRepository.save(monitor);

        log.debug("Check completed monitor={} result={} responseTime={}ms isUp={}",
                monitor.getId(), check.getResult(),
                check.getResponseTimeMs(), monitor.getIsUp());
    }
}
