package com.monitor.domain.model;

import com.monitor.domain.enums.MonitorStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Entidade central do sistema. Representa uma URL sendo monitorada.
 *
 * Decisão de design: UUID como PK para evitar enumeração de recursos
 * e facilitar distribuição em sistemas multi-nó no futuro.
 */
@Entity
@Table(
    name = "monitors",
    indexes = {
        @Index(name = "idx_monitors_status", columnList = "status"),
        @Index(name = "idx_monitors_next_check", columnList = "next_check_at"),
        @Index(name = "idx_monitors_owner", columnList = "owner_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"healthChecks"})
public class Monitor {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    /**
     * Nome descritivo para identificação humana do monitor.
     * Ex: "API de Pagamentos - Produção"
     */
    @Column(nullable = false, length = 200)
    private String name;

    /**
     * URL alvo do monitoramento. Deve incluir protocolo (https://).
     */
    @Column(nullable = false, length = 2048)
    private String url;

    /**
     * Intervalo entre checagens em segundos.
     * Mínimo: 30s | Máximo: 3600s (1h)
     */
    @Column(name = "check_interval_seconds", nullable = false)
    @Builder.Default
    private Integer checkIntervalSeconds = 60;

    /**
     * Timeout máximo para cada requisição HTTP em milissegundos.
     * Default: 5000ms (5 segundos)
     */
    @Column(name = "timeout_ms", nullable = false)
    @Builder.Default
    private Integer timeoutMs = 5000;

    /**
     * Status atual do monitor no ciclo de vida do sistema.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private MonitorStatus status = MonitorStatus.ACTIVE;

    /**
     * Estado de saúde atual da URL monitorada.
     * True = UP | False = DOWN
     */
    @Column(name = "is_up")
    @Builder.Default
    private Boolean isUp = true;

    /**
     * Quantas falhas consecutivas foram detectadas.
     * Usado para evitar falso-positivo: só notifica após N falhas.
     */
    @Column(name = "consecutive_failures")
    @Builder.Default
    private Integer consecutiveFailures = 0;

    /**
     * Número de falhas consecutivas necessárias para considerar DOWN.
     * Default: 2 (evita alertas por instabilidade momentânea)
     */
    @Column(name = "failure_threshold")
    @Builder.Default
    private Integer failureThreshold = 2;

    /**
     * Quando a próxima checagem deve ocorrer.
     * Atualizado após cada execução.
     */
    @Column(name = "next_check_at")
    private Instant nextCheckAt;

    /**
     * Último timestamp de verificação bem-sucedida.
     */
    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    /**
     * Webhook URL para notificações (Discord, Slack, etc.)
     * Opcional — se null, notificações são apenas logadas.
     */
    @Column(name = "webhook_url", length = 2048)
    private String webhookUrl;

    /**
     * Tags para organização e filtragem.
     * Ex: "production,payment,critical"
     */
    @Column(length = 500)
    private String tags;

    /**
     * Identificador do dono do monitor (para multi-tenancy futuro).
     */
    @Column(name = "owner_id", length = 100)
    private String ownerId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * Histórico de verificações (lazy para performance).
     * Não carregamos junto com o monitor na listagem.
     */
    @OneToMany(mappedBy = "monitor", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<HealthCheck> healthChecks = new ArrayList<>();

    // ─── Métodos de negócio ───────────────────────────────────────────────────

    /**
     * Registra uma checagem bem-sucedida.
     * Reseta contador de falhas e agenda próxima execução.
     */
    public void recordSuccess() {
        this.isUp = true;
        this.consecutiveFailures = 0;
        this.lastCheckedAt = Instant.now();
        scheduleNextCheck();
    }

    /**
     * Registra uma falha na checagem.
     * Incrementa contador e determina se deve marcar como DOWN.
     *
     * @return true se o serviço acabou de ficar DOWN (threshold atingido)
     */
    public boolean recordFailure() {
        this.consecutiveFailures++;
        this.lastCheckedAt = Instant.now();
        scheduleNextCheck();

        boolean justWentDown = !this.isUp || this.consecutiveFailures == this.failureThreshold;
        if (this.consecutiveFailures >= this.failureThreshold) {
            this.isUp = false;
        }
        return justWentDown && this.consecutiveFailures == this.failureThreshold;
    }

    /**
     * Verifica se o monitor estava DOWN e agora está UP novamente.
     * Usado para disparar notificação de recuperação.
     */
    public boolean justRecovered(boolean wasDown) {
        return wasDown && this.isUp;
    }

    public boolean isActive() {
        return MonitorStatus.ACTIVE.equals(this.status);
    }

    public boolean isDue() {
        return this.nextCheckAt == null || Instant.now().isAfter(this.nextCheckAt);
    }

    private void scheduleNextCheck() {
        this.nextCheckAt = Instant.now().plus(Duration.ofSeconds(this.checkIntervalSeconds));
    }
}
