package com.monitor.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Configuração central da aplicação.
 *
 * Beans definidos aqui:
 * - WebClient (com pool de conexões otimizado para alto volume)
 * - Virtual Thread Executor (para o scheduler de tarefas Spring)
 * - OpenAPI/Swagger
 */
@Configuration
public class AppConfig {

    @Value("${monitor.http.connection-timeout-ms:3000}")
    private int connectionTimeoutMs;

    @Value("${monitor.http.max-connections:2000}")
    private int maxConnections;

    @Value("${monitor.http.pending-acquire-timeout-ms:10000}")
    private int pendingAcquireTimeoutMs;

    /**
     * WebClient configurado para alto volume de requisições simultâneas.
     *
     * ConnectionProvider customizado:
     * - maxConnections: pool total de conexões TCP ao mesmo host
     * - pendingAcquireTimeout: quanto tempo esperar por uma conexão do pool
     * - maxIdleTime: fecha conexões ociosas para liberar recursos
     *
     * Decisão: Um único WebClient compartilhado é suficiente e mais eficiente
     * do que criar instâncias por requisição. O WebClient é thread-safe.
     */
    @Bean
    public WebClient webClient() {
        // Pool de conexões configurado para suportar muitos monitors simultâneos
        ConnectionProvider connectionProvider = ConnectionProvider.builder("monitor-pool")
                .maxConnections(maxConnections)
                .pendingAcquireTimeout(Duration.ofMillis(pendingAcquireTimeoutMs))
                .maxIdleTime(Duration.ofSeconds(30))
                .maxLifeTime(Duration.ofMinutes(5))
                .evictInBackground(Duration.ofSeconds(120))
                .build();

        HttpClient httpClient = HttpClient.create(connectionProvider)
                // Timeout de estabelecimento de conexão TCP
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectionTimeoutMs)
                // Não seguir redirects automaticamente (registramos o 3xx)
                .followRedirect(false)
                // User-Agent identificando o sistema
                .headers(h -> h.add("User-Agent", "API-Health-Monitor/1.0"))
                .doOnConnected(conn -> conn
                        // Timeout de leitura de dados do socket
                        .addHandlerLast(new ReadTimeoutHandler(30, TimeUnit.SECONDS))
                        // Timeout de escrita (para o caso de slow start)
                        .addHandlerLast(new WriteTimeoutHandler(10, TimeUnit.SECONDS))
                );

        // Aumenta o limite do buffer de resposta (default é 256KB, muito pequeno)
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(1024 * 1024)) // 1MB
                .build();

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .build();
    }

    /**
     * Configura o executor padrão do Spring para usar Virtual Threads.
     *
     * ─────────────────────────────────────────────────────────────────────
     * VIRTUAL THREADS NO SPRING BOOT — COMO FUNCIONA:
     * ─────────────────────────────────────────────────────────────────────
     * Spring MVC (com Tomcat) usa um thread pool para processar requests.
     * Ao substituir pelo Virtual Thread executor, cada request HTTP
     * recebe sua própria Virtual Thread.
     *
     * Isso permite:
     * - Requisições simultâneas praticamente ilimitadas
     * - Código síncrono simples sem risco de thread starvation
     * - Stack traces legíveis (ao contrário de código reativo)
     *
     * Nota: Com Spring WebFlux (reativo), o benefício é menor pois o
     * modelo já é event-loop. Com Spring MVC, o ganho é máximo.
     * ─────────────────────────────────────────────────────────────────────
     */
    @Bean
    public Executor applicationTaskExecutor() {
        // Java 21: newVirtualThreadPerTaskExecutor() cria uma VT por tarefa
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Documentação OpenAPI 3.0
     */
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("API Health Monitor")
                        .description("""
                                Sistema de monitoramento de disponibilidade de APIs.
                                
                                **Funcionalidades:**
                                - Cadastro e gerenciamento de URLs monitoradas
                                - Verificações periódicas com Virtual Threads (Java 21)
                                - Notificações via Discord/Telegram Webhook
                                - Histórico de uptime e tempo de resposta
                                - Métricas Prometheus
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("API Health Monitor")
                                .url("https://github.com/your-org/api-health-monitor"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")));
    }
}
