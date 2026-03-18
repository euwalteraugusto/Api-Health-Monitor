package com.monitor.notification.strategy;

import com.monitor.domain.enums.NotificationStatus;
import com.monitor.domain.enums.NotificationType;
import com.monitor.domain.model.HealthCheck;
import com.monitor.domain.model.Monitor;
import com.monitor.domain.model.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Estratégia de notificação via Discord Webhook.
 *
 * Formato: Discord Embed com cor semântica (vermelho=DOWN, verde=RECOVERED)
 * e campos detalhados do incidente.
 *
 * Configurar Discord Webhook:
 * 1. Server Settings → Integrations → Webhooks
 * 2. New Webhook → Copy Webhook URL
 * 3. Cadastrar como webhookUrl no Monitor
 *
 * Exemplo URL: https://discord.com/api/webhooks/{id}/{token}
 */
@Service
@Slf4j
public class DiscordNotificationStrategy implements NotificationStrategy {

    // Cores Discord (formato decimal)
    private static final int COLOR_DOWN = 0xFF0000;       // Vermelho
    private static final int COLOR_RECOVERED = 0x00FF00;  // Verde

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("dd/MM/yyyy HH:mm:ss z")
            .withZone(ZoneId.of("America/Sao_Paulo"));

    private final WebClient webClient;

    public DiscordNotificationStrategy(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public String getChannelType() {
        return "discord";
    }

    @Override
    public boolean supports(String webhookUrl) {
        return webhookUrl != null && webhookUrl.contains("discord.com/api/webhooks");
    }

    @Override
    public Notification send(Monitor monitor, NotificationType type, HealthCheck check) {
        Notification notification = Notification.builder()
                .monitor(monitor)
                .type(type)
                .webhookUrl(monitor.getWebhookUrl())
                .build();

        String payload = buildDiscordPayload(monitor, type, check);
        notification.setPayload(payload);

        try {
            String response = webClient
                    .post()
                    .uri(monitor.getWebhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .defaultIfEmpty("204 No Content")
                    .block();

            notification.markAsSent(response);
            log.info("Discord notification sent monitor={} type={}", monitor.getId(), type);

        } catch (Exception e) {
            notification.markAsFailed(e.getMessage());
            log.error("Failed to send Discord notification monitor={} type={} error={}",
                    monitor.getId(), type, e.getMessage());
        }

        return notification;
    }

    /**
     * Monta o payload JSON no formato Discord Embed.
     *
     * Referência: https://discord.com/developers/docs/resources/webhook
     */
    private String buildDiscordPayload(Monitor monitor, NotificationType type, HealthCheck check) {
        boolean isDown = NotificationType.DOWN.equals(type);
        String emoji = isDown ? "🔴" : "🟢";
        String title = isDown
                ? emoji + " Serviço Indisponível"
                : emoji + " Serviço Recuperado";
        String description = isDown
                ? "O serviço **" + monitor.getName() + "** está fora do ar!"
                : "O serviço **" + monitor.getName() + "** voltou ao normal.";
        int color = isDown ? COLOR_DOWN : COLOR_RECOVERED;
        String statusField = isDown ? "❌ DOWN" : "✅ UP";

        String httpStatusField = check.getHttpStatus() != null
                ? String.valueOf(check.getHttpStatus())
                : "N/A";

        String responseTimeField = check.getResponseTimeMs() != null
                ? check.getResponseTimeMs() + "ms"
                : "N/A";

        String errorField = (check.getErrorMessage() != null && isDown)
                ? "\n**Erro:** `" + check.getErrorMessage() + "`"
                : "";

        String timestamp = FORMATTER.format(Instant.now());

        return """
                {
                  "username": "API Health Monitor",
                  "avatar_url": "https://cdn-icons-png.flaticon.com/512/2620/2620244.png",
                  "embeds": [{
                    "title": "%s",
                    "description": "%s%s",
                    "color": %d,
                    "fields": [
                      { "name": "🌐 URL", "value": "`%s`", "inline": false },
                      { "name": "📊 Status", "value": "%s", "inline": true },
                      { "name": "🔢 HTTP", "value": "%s", "inline": true },
                      { "name": "⚡ Resposta", "value": "%s", "inline": true }
                    ],
                    "footer": {
                      "text": "API Health Monitor • %s"
                    }
                  }]
                }
                """.formatted(
                        title, description, errorField, color,
                        monitor.getUrl(), statusField, httpStatusField,
                        responseTimeField, timestamp);
    }
}
