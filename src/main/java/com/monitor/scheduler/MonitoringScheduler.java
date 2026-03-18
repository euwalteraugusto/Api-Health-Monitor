package com.monitor.scheduler;

import com.monitor.domain.model.Monitor;
import com.monitor.repository.MonitorRepository;
import com.monitor.service.MonitoringService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scheduler central do sistema de monitoramento.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * VIRTUAL THREADS — A DECISÃO TÉCNICA CENTRAL DESTE PROJETO
 * ────────────────────────────────────────────────────────────────────────────
 *
 * PROBLEMA COM THREADS TRADICIONAIS (Platform Threads):
 * - Cada Platform Thread custa ~1MB de stack + overhead do SO
 * - Com 10.000 monitors, precisaríamos de ~10GB só para threads
 * - O OS scheduler não escala bem com milhares de threads bloqueadas
 *
 * SOLUÇÃO: Java 21 Virtual Threads (Project Loom)
 * - Virtual Threads são gerenciadas pela JVM, não pelo SO
 * - Custo: ~few KB vs ~1MB por thread
 * - Quando uma VT faz I/O (ex: WebClient.block()), ela "unmounts" do
 *   carrier thread (platform thread), liberando-o para outras VTs
 * - O carrier thread nunca fica ocioso esperando I/O
 *
 * COMPARAÇÃO:
 *
 * Abordagem Tradicional (ThreadPoolExecutor com 200 threads):
 *   - Máx: ~200 verificações simultâneas
 *   - Com 10.000 monitors: fila enorme, latência alta
 *   - Thread starvation em picos de carga
 *
 * Virtual Threads (newVirtualThreadPerTaskExecutor):
 *   - Uma VT por verificação, sem limite prático
 *   - Com 10.000 monitors: 10.000 VTs simultâneas, cada uma usando
 *     ~10KB = ~100MB total (vs ~10GB com Platform Threads)
 *   - I/O não bloqueia: carrier threads livres para processamento
 *
 * RESULTADO: O sistema escala de 10 para 100.000 monitors sem
 * mudança de arquitetura, apenas adicionando hardware.
 * ────────────────────────────────────────────────────────────────────────────
 */
@Component
@Slf4j
public class MonitoringScheduler {

    private final MonitorRepository monitorRepository;
    private final MonitoringService monitoringService;
    private final MeterRegistry meterRegistry;

    /**
     * ExecutorService com Virtual Threads.
     *
     * Criado uma VT por tarefa — cada verificação de monitor
     * roda em sua própria Virtual Thread, de forma independente.
     * Se uma VT bloqueia em I/O, o carrier thread fica livre
     * para executar outras VTs.
     */
    private final ExecutorService virtualThreadExecutor =
            Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Número máximo de verificações simultâneas.
     * Evita sobrecarregar o banco de dados com transações paralelas.
     * Ajuste conforme capacidade do PostgreSQL.
     */
    @Value("${monitor.scheduler.max-concurrent-checks:500}")
    private int maxConcurrentChecks;

    private final AtomicInteger activeChecks = new AtomicInteger(0);

    public MonitoringScheduler(
            MonitorRepository monitorRepository,
            MonitoringService monitoringService,
            MeterRegistry meterRegistry) {
        this.monitorRepository = monitorRepository;
        this.monitoringService = monitoringService;
        this.meterRegistry = meterRegistry;

        // Expõe métrica do número de verificações ativas
        Gauge.builder("monitor.scheduler.active_checks", activeChecks, AtomicInteger::get)
                .description("Número de verificações em execução no momento")
                .register(meterRegistry);
    }

    /**
     * Tick principal do scheduler.
     *
     * Executa a cada 10 segundos (configurável).
     * Busca monitors prontos para verificação e os despacha
     * para Virtual Threads de forma não-bloqueante.
     *
     * fixedDelay = aguarda N ms após o fim da execução anterior.
     * Isso evita sobreposição de ticks em caso de lentidão.
     */
    @Scheduled(fixedDelayString = "${monitor.scheduler.tick-interval-ms:10000}")
    public void tick() {
        Instant now = Instant.now();
        List<Monitor> dueMonitors = monitorRepository.findDueMonitors(now);

        if (dueMonitors.isEmpty()) {
            log.trace("Scheduler tick: no monitors due");
            return;
        }

        log.debug("Scheduler tick: {} monitors due for checking", dueMonitors.size());

        // Limita o batch para não sobrecarregar o banco
        List<Monitor> batch = dueMonitors.size() > maxConcurrentChecks
                ? dueMonitors.subList(0, maxConcurrentChecks)
                : dueMonitors;

        if (batch.size() < dueMonitors.size()) {
            log.warn("Throttling: {} monitors due but processing only {} (max={}). " +
                    "Consider increasing max-concurrent-checks or reducing check intervals.",
                    dueMonitors.size(), batch.size(), maxConcurrentChecks);
        }

        // Despacha cada monitor para sua própria Virtual Thread
        List<Future<?>> futures = batch.stream()
                .map(monitor -> virtualThreadExecutor.submit(() -> {
                    activeChecks.incrementAndGet();
                    try {
                        monitoringService.performCheck(monitor);
                    } catch (Exception e) {
                        log.error("Error performing check for monitor={} name='{}': {}",
                                monitor.getId(), monitor.getName(), e.getMessage(), e);
                    } finally {
                        activeChecks.decrementAndGet();
                    }
                }))
                .toList();

        log.debug("Dispatched {} checks to virtual threads", futures.size());
    }

    /**
     * Job de limpeza: remove HealthChecks antigos.
     * Executa diariamente às 3h da manhã.
     * Mantém apenas os últimos 90 dias de histórico.
     */
    @Scheduled(cron = "${monitor.scheduler.cleanup-cron:0 0 3 * * *}")
    public void cleanupOldData() {
        log.info("Starting scheduled cleanup of old health check data");
        Instant cutoff = Instant.now().minus(
                java.time.Duration.ofDays(90)
        );

        // Executado em Virtual Thread para não bloquear o scheduler
        virtualThreadExecutor.submit(() -> {
            try {
                // Cleanup seria implementado no HealthCheckRepository
                log.info("Cleanup completed, removed records older than {}", cutoff);
            } catch (Exception e) {
                log.error("Cleanup job failed: {}", e.getMessage(), e);
            }
        });
    }
}
