package com.monitor.service;

import com.monitor.domain.model.Monitor;
import com.monitor.repository.HealthCheckRepository;
import com.monitor.repository.MonitorRepository;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de integração usando Testcontainers.
 *
 * Sobe um PostgreSQL real em Docker durante os testes.
 * Isso elimina mocks frágeis de banco de dados e testa o sistema como um todo.
 *
 * MockWebServer simula endpoints HTTP externos com respostas configuráveis.
 *
 * Estes testes validam o fluxo completo:
 * Monitor → HttpCheckClient → MonitoringService → DB → Estado atualizado
 */
@SpringBootTest
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MonitoringServiceIntegrationTest {

    // ── Infraestrutura de teste ───────────────────────────────────────────────

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("healthmonitor_test")
            .withUsername("test")
            .withPassword("test");

    // Servidor HTTP fake para simular APIs externas
    static MockWebServer mockWebServer;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeAll
    static void setupMockServer() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
    }

    @AfterAll
    static void teardownMockServer() throws IOException {
        mockWebServer.shutdown();
    }

    // ── Dependências ─────────────────────────────────────────────────────────

    @Autowired MonitoringService monitoringService;
    @Autowired MonitorRepository monitorRepository;
    @Autowired HealthCheckRepository healthCheckRepository;

    // ── Testes ───────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("Should record SUCCESS when endpoint returns 200")
    void shouldRecordSuccessWhenEndpointReturns200() {
        // Arrange: configura servidor fake para retornar 200
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"status\":\"ok\"}")
                .addHeader("Content-Type", "application/json"));

        Monitor monitor = monitorRepository.save(Monitor.builder()
                .name("Test Monitor")
                .url(mockWebServer.url("/health").toString())
                .checkIntervalSeconds(60)
                .timeoutMs(5000)
                .build());

        // Act
        monitoringService.performCheck(monitor);

        // Assert
        Monitor updated = monitorRepository.findById(monitor.getId()).orElseThrow();
        assertThat(updated.getIsUp()).isTrue();
        assertThat(updated.getConsecutiveFailures()).isZero();
        assertThat(updated.getLastCheckedAt()).isNotNull();

        var checks = healthCheckRepository.findByMonitorIdOrderByCheckedAtDesc(
                monitor.getId(), org.springframework.data.domain.PageRequest.of(0, 1));
        assertThat(checks.getContent()).hasSize(1);
        assertThat(checks.getContent().get(0).getHttpStatus()).isEqualTo(200);
    }

    @Test
    @Order(2)
    @DisplayName("Should record HTTP_ERROR when endpoint returns 500")
    void shouldRecordHttpErrorWhenEndpointReturns500() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));

        Monitor monitor = monitorRepository.save(Monitor.builder()
                .name("Failing Monitor")
                .url(mockWebServer.url("/failing").toString())
                .checkIntervalSeconds(60)
                .timeoutMs(5000)
                .failureThreshold(1) // DOWN na primeira falha
                .build());

        // Act
        monitoringService.performCheck(monitor);

        // Assert
        Monitor updated = monitorRepository.findById(monitor.getId()).orElseThrow();
        assertThat(updated.getIsUp()).isFalse();
        assertThat(updated.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    @Order(3)
    @DisplayName("Should transition from DOWN to UP and mark as recovered")
    void shouldTransitionFromDownToUp() {
        // Setup: monitor já está DOWN
        Monitor monitor = monitorRepository.save(Monitor.builder()
                .name("Recovering Monitor")
                .url(mockWebServer.url("/recovering").toString())
                .checkIntervalSeconds(60)
                .timeoutMs(5000)
                .isUp(false)
                .consecutiveFailures(3)
                .build());

        // Endpoint volta a responder
        mockWebServer.enqueue(new MockResponse().setResponseCode(200));

        // Act
        monitoringService.performCheck(monitor);

        // Assert: monitor voltou para UP
        Monitor updated = monitorRepository.findById(monitor.getId()).orElseThrow();
        assertThat(updated.getIsUp()).isTrue();
        assertThat(updated.getConsecutiveFailures()).isZero();
    }

    @Test
    @Order(4)
    @DisplayName("Should not trigger DOWN alert before reaching failure threshold")
    void shouldNotTriggerAlertBeforeThreshold() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(503));

        Monitor monitor = monitorRepository.save(Monitor.builder()
                .name("Threshold Monitor")
                .url(mockWebServer.url("/threshold").toString())
                .checkIntervalSeconds(60)
                .timeoutMs(5000)
                .failureThreshold(3) // precisa de 3 falhas
                .build());

        monitoringService.performCheck(monitor);

        Monitor updated = monitorRepository.findById(monitor.getId()).orElseThrow();
        // Com threshold=3, ainda não é DOWN após 1 falha
        assertThat(updated.getIsUp()).isTrue();
        assertThat(updated.getConsecutiveFailures()).isEqualTo(1);
    }
}
