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
 * Estratégia de notificação via Telegram Bot API.
 *
 * Usa a Telegram Bot API para enviar mensagens com formatação HTML.
 *
 * Setup do Telegram Bot:
 * 1. Abra o Telegram, busque @BotFather
 * 2. /newbot → siga as instruções → copie o TOKEN
 * 3. Envie uma mensagem para o bot e acesse:
 *    https://api.telegram.org/bot{TOKEN}/getUpdates
 * 4. Copie o chat_id da resposta
 * 5. webhookUrl format: https://api.telegram.org/bot{TOKEN}/sendMessage?chat_id={CHAT_ID}
 */
@Service
@Slf4j
public class TelegramNotificationStrategy implements NotificationStrategy {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("dd/MM/yyyy HH:mm:ss")
            .withZone(ZoneId.of("America/Sao_Paulo"));

    private final WebClient webClient;

    public TelegramNotificationStrategy(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public String getChannelType() {
        return "telegram";
    }

    @Override
    public boolean supports(String webhookUrl) {
        return webhookUrl != null && webhookUrl.contains("api.telegram.org");
    }

    @Override
    public Notification send(Monitor monitor, NotificationType type, HealthCheck check) {
        Notification notification = Notification.builder()
                .monitor(monitor)
                .type(type)
                .webhookUrl(monitor.getWebhookUrl())
                .build();

        String payload = buildTelegramPayload(monitor, type, check);
        notification.setPayload(payload);

        try {
            String response = webClient
                    .post()
                    .uri(monitor.getWebhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            notification.markAsSent(response);
            log.info("Telegram notification sent monitor={} type={}", monitor.getId(), type);

        } catch (Exception e) {
            notification.markAsFailed(e.getMessage());
            log.error("Failed to send Telegram notification monitor={} type={} error={}",
                    monitor.getId(), type, e.getMessage());
        }

        return notification;
    }

    private String buildTelegramPayload(Monitor monitor, NotificationType type, HealthCheck check) {
        boolean isDown = NotificationType.DOWN.equals(type);
        String emoji = isDown ? "🔴" : "🟢";
        String status = isDown ? "FORA DO AR" : "RECUPERADO";

        String httpStatus = check.getHttpStatus() != null
                ? String.valueOf(check.getHttpStatus())
                : "N/A";

        String responseTime = check.getResponseTimeMs() != null
                ? check.getResponseTimeMs() + "ms"
                : "N/A";

        String errorLine = (check.getErrorMessage() != null && isDown)
                ? "\n⚠️ <b>Erro:</b> <code>" + escapeHtml(check.getErrorMessage()) + "</code>"
                : "";

        String text = """
                %s <b>API Health Monitor</b>
                
                📋 <b>Serviço:</b> %s
                🌐 <b>URL:</b> <code>%s</code>
                📊 <b>Status:</b> %s
                🔢 <b>HTTP:</b> %s
                ⚡ <b>Resposta:</b> %s%s
                🕐 <b>Horário:</b> %s
                """.formatted(
                        emoji, escapeHtml(monitor.getName()),
                        escapeHtml(monitor.getUrl()), status,
                        httpStatus, responseTime, errorLine,
                        FORMATTER.format(Instant.now()));

        return """
                {
                  "text": "%s",
                  "parse_mode": "HTML",
                  "disable_web_page_preview": true
                }
                """.formatted(text.replace("\"", "\\\"").replace("\n", "\\n"));
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
