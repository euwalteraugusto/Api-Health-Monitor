package com.monitor.domain.model;

import com.monitor.domain.enums.CheckResult;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Registro imutável de cada verificação realizada.
 *
 * Design: Append-only — nunca atualizamos um HealthCheck, apenas criamos novos.
 * Isso facilita auditoria, análise histórica e cálculo de SLA.
 *
 * Particionamento futuro: A coluna checked_at é ideal para particionamento
 * por range de data no PostgreSQL (PARTITION BY RANGE).
 */
@Entity
@Table(
    name = "health_checks",
    indexes = {
        @Index(name = "idx_hc_monitor_id", columnList = "monitor_id"),
        @Index(name = "idx_hc_checked_at", columnList = "checked_at"),
        @Index(name = "idx_hc_monitor_checked", columnList = "monitor_id, checked_at DESC"),
        @Index(name = "idx_hc_result", columnList = "result")
    }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HealthCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "monitor_id", nullable = false)
    private Monitor monitor;

    /**
     * Resultado da verificação: SUCCESS, TIMEOUT, HTTP_ERROR, CONNECTION_ERROR, etc.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CheckResult result;

    /**
     * Código HTTP retornado. Null em caso de timeout/connection error.
     */
    @Column(name = "http_status")
    private Integer httpStatus;

    /**
     * Tempo de resposta em milissegundos.
     * Inclui tempo de DNS + conexão TCP + TLS + transferência.
     */
    @Column(name = "response_time_ms")
    private Long responseTimeMs;

    /**
     * Mensagem de erro quando result != SUCCESS.
     * Ex: "Connection refused", "Read timeout after 5000ms"
     */
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    /**
     * IP resolvido do host (útil para debug de DNS).
     */
    @Column(name = "resolved_ip", length = 45)
    private String resolvedIp;

    @CreationTimestamp
    @Column(name = "checked_at", updatable = false)
    private Instant checkedAt;

    // ─── Factory methods para clareza semântica ───────────────────────────────

    public static HealthCheck success(Monitor monitor, int httpStatus, long responseTimeMs) {
        return HealthCheck.builder()
                .monitor(monitor)
                .result(CheckResult.SUCCESS)
                .httpStatus(httpStatus)
                .responseTimeMs(responseTimeMs)
                .build();
    }

    public static HealthCheck timeout(Monitor monitor, long elapsedMs) {
        return HealthCheck.builder()
                .monitor(monitor)
                .result(CheckResult.TIMEOUT)
                .responseTimeMs(elapsedMs)
                .errorMessage("Request timed out after " + elapsedMs + "ms")
                .build();
    }

    public static HealthCheck httpError(Monitor monitor, int httpStatus, long responseTimeMs) {
        return HealthCheck.builder()
                .monitor(monitor)
                .result(CheckResult.HTTP_ERROR)
                .httpStatus(httpStatus)
                .responseTimeMs(responseTimeMs)
                .errorMessage("HTTP error: " + httpStatus)
                .build();
    }

    public static HealthCheck connectionError(Monitor monitor, String message, long elapsedMs) {
        return HealthCheck.builder()
                .monitor(monitor)
                .result(CheckResult.CONNECTION_ERROR)
                .responseTimeMs(elapsedMs)
                .errorMessage(message)
                .build();
    }

    public boolean isSuccess() {
        return CheckResult.SUCCESS.equals(this.result);
    }
}
