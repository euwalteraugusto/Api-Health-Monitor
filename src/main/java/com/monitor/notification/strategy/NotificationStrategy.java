package com.monitor.notification.strategy;

import com.monitor.domain.model.Monitor;
import com.monitor.domain.model.Notification;
import com.monitor.domain.enums.NotificationType;

/**
 * Strategy Pattern para canais de notificação.
 *
 * Cada implementação sabe como formatar e enviar alertas
 * para um canal específico (Discord, Telegram, Slack, etc.).
 *
 * Para adicionar um novo canal:
 * 1. Implemente esta interface
 * 2. Anote com @Service
 * 3. O sistema automaticamente usará a nova implementação
 *    via injeção por lista (@Autowired List<NotificationStrategy>)
 */
public interface NotificationStrategy {

    /**
     * Identifica o tipo de canal que esta strategy suporta.
     * Ex: "discord", "telegram", "slack"
     */
    String getChannelType();

    /**
     * Verifica se esta strategy consegue tratar o webhook URL fornecido.
     * Permite roteamento automático baseado no formato da URL.
     */
    boolean supports(String webhookUrl);

    /**
     * Envia a notificação.
     *
     * @param monitor   Monitor que gerou o alerta
     * @param type      DOWN ou RECOVERED
     * @param check     HealthCheck que triggou a notificação (para contexto)
     * @return Notification populada com resultado do envio
     */
    Notification send(Monitor monitor, NotificationType type, com.monitor.domain.model.HealthCheck check);
}
