package com.monitor.service;

import com.monitor.domain.enums.NotificationType;
import com.monitor.domain.model.HealthCheck;
import com.monitor.domain.model.Monitor;
import com.monitor.domain.model.Notification;
import com.monitor.notification.strategy.NotificationStrategy;
import com.monitor.repository.NotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Orquestrador de notificações.
 *
 * Responsabilidades:
 * 1. Selecionar a strategy correta para o webhookUrl do monitor
 * 2. Delegar o envio para a strategy
 * 3. Persistir o resultado (para auditoria e retry futuro)
 *
 * Design: Usa o padrão Strategy + Auto-discovery via Spring IoC.
 * Novas integrações (Slack, PagerDuty, etc.) são adicionadas sem
 * modificar este serviço — apenas criando nova @Service implementando
 * NotificationStrategy.
 */
@Service
@Slf4j
public class NotificationService {

    private final List<NotificationStrategy> strategies;
    private final NotificationRepository notificationRepository;

    /**
     * Spring injeta automaticamente todas as implementações de NotificationStrategy.
     * Isso é o Open/Closed Principle em prática: aberto para extensão (nova strategy),
     * fechado para modificação (este serviço não muda).
     */
    public NotificationService(
            List<NotificationStrategy> strategies,
            NotificationRepository notificationRepository) {
        this.strategies = strategies;
        this.notificationRepository = notificationRepository;
        log.info("NotificationService initialized with {} strategies: {}",
                strategies.size(),
                strategies.stream().map(NotificationStrategy::getChannelType).toList());
    }

    /**
     * Envia notificação de serviço caído.
     * Chamado quando consecutive_failures atinge o threshold.
     */
    @Transactional
    public void notifyDown(Monitor monitor, HealthCheck lastCheck) {
        if (monitor.getWebhookUrl() == null || monitor.getWebhookUrl().isBlank()) {
            log.debug("Monitor {} has no webhook configured, skipping notification", monitor.getId());
            return;
        }

        log.warn("Sending DOWN notification for monitor={} name='{}' url={}",
                monitor.getId(), monitor.getName(), monitor.getUrl());

        sendNotification(monitor, NotificationType.DOWN, lastCheck);
    }

    /**
     * Envia notificação de serviço recuperado.
     * Chamado quando após estar DOWN, uma checagem é bem-sucedida.
     */
    @Transactional
    public void notifyRecovered(Monitor monitor, HealthCheck lastCheck) {
        if (monitor.getWebhookUrl() == null || monitor.getWebhookUrl().isBlank()) {
            return;
        }

        log.info("Sending RECOVERED notification for monitor={} name='{}' url={}",
                monitor.getId(), monitor.getName(), monitor.getUrl());

        sendNotification(monitor, NotificationType.RECOVERED, lastCheck);
    }

    private void sendNotification(Monitor monitor, NotificationType type, HealthCheck check) {
        NotificationStrategy strategy = findStrategy(monitor.getWebhookUrl());

        if (strategy == null) {
            log.warn("No strategy found for webhookUrl={} monitor={}",
                    monitor.getWebhookUrl(), monitor.getId());
            return;
        }

        Notification notification = strategy.send(monitor, type, check);
        notificationRepository.save(notification);
    }

    /**
     * Encontra a strategy adequada para o webhookUrl.
     * Retorna a primeira que declarar suporte via supports().
     */
    private NotificationStrategy findStrategy(String webhookUrl) {
        return strategies.stream()
                .filter(s -> s.supports(webhookUrl))
                .findFirst()
                .orElse(null);
    }
}
