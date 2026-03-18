package com.monitor.domain.model;

import com.monitor.domain.enums.NotificationStatus;
import com.monitor.domain.enums.NotificationType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Registro de cada notificação enviada ou tentada.
 *
 * Mantemos um log completo para:
 * 1. Debugging de falhas de entrega
 * 2. Evitar notificações duplicadas (idempotência)
 * 3. Auditoria de alertas
 */
@Entity
@Table(
    name = "notifications",
    indexes = {
        @Index(name = "idx_notif_monitor_id", columnList = "monitor_id"),
        @Index(name = "idx_notif_status", columnList = "status"),
        @Index(name = "idx_notif_created_at", columnList = "created_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "monitor_id", nullable = false)
    private Monitor monitor;

    /**
     * Tipo da notificação: DOWN (serviço caiu) ou RECOVERED (voltou ao normal).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationType type;

    /**
     * Status de entrega: PENDING, SENT, FAILED.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private NotificationStatus status = NotificationStatus.PENDING;

    /**
     * Webhook destino onde a notificação foi enviada.
     */
    @Column(name = "webhook_url", length = 2048)
    private String webhookUrl;

    /**
     * Payload JSON enviado ao webhook.
     */
    @Column(columnDefinition = "TEXT")
    private String payload;

    /**
     * Resposta recebida do webhook (para debug).
     */
    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    /**
     * Mensagem de erro caso o envio falhe.
     */
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    /**
     * Número de tentativas de envio realizadas.
     */
    @Column(name = "attempt_count")
    @Builder.Default
    private Integer attemptCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    // ─── Métodos de estado ────────────────────────────────────────────────────

    public void markAsSent(String responseBody) {
        this.status = NotificationStatus.SENT;
        this.responseBody = responseBody;
        this.sentAt = Instant.now();
        this.attemptCount++;
    }

    public void markAsFailed(String errorMessage) {
        this.status = NotificationStatus.FAILED;
        this.errorMessage = errorMessage;
        this.attemptCount++;
    }

    public void incrementAttempt() {
        this.attemptCount++;
    }
}
