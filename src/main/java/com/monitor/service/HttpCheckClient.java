package com.monitor.service;

import com.monitor.domain.model.HealthCheck;
import com.monitor.domain.model.Monitor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

/**
 * Cliente HTTP para verificação de saúde de URLs.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * POR QUE WebClient E NÃO RestTemplate?
 * ────────────────────────────────────────────────────────────────────────────
 * RestTemplate é síncrono e bloqueante: cada requisição ocupa uma thread
 * durante todo o tempo de I/O. Com 1000 monitors, precisaríamos de 1000 threads
 * bloqueadas aguardando resposta.
 *
 * WebClient é não-bloqueante: usa o event loop do Netty internamente.
 * Quando combinado com Virtual Threads do Java 21, obtemos:
 * 1. I/O assíncrono (WebClient/Netty)
 * 2. Simplicidade de código síncrono (Virtual Threads)
 * 3. Sem bloqueio de platform threads
 *
 * O resultado: capacidade de lidar com milhares de monitors simultâneos
 * com footprint de memória mínimo.
 * ────────────────────────────────────────────────────────────────────────────
 *
 * FLUXO: monitor → WebClient.get() → timeout → response → HealthCheck
 */
@Service
@Slf4j
public class HttpCheckClient {

    private final WebClient webClient;
    private final MeterRegistry meterRegistry;

    public HttpCheckClient(WebClient webClient, MeterRegistry meterRegistry) {
        this.webClient = webClient;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Executa uma verificação HTTP para o monitor especificado.
     *
     * O método é síncrono do ponto de vista do chamador (bloqueia até
     * ter resultado), mas internamente usa I/O reativo não-bloqueante.
     * Isso funciona de forma eficiente com Virtual Threads.
     *
     * @param monitor Monitor a ser verificado
     * @return HealthCheck com resultado da verificação
     */
    public HealthCheck check(Monitor monitor) {
        Instant start = Instant.now();
        Timer.Sample timerSample = Timer.start(meterRegistry);

        log.debug("Starting health check for monitor={} url={}", monitor.getId(), monitor.getUrl());

        try {
            HealthCheck result = executeCheck(monitor, start);

            // Registra métrica de tempo de resposta
            timerSample.stop(Timer.builder("monitor.check.duration")
                    .tag("monitor_id", monitor.getId().toString())
                    .tag("result", result.getResult().name())
                    .description("HTTP health check duration")
                    .register(meterRegistry));

            return result;

        } catch (Exception e) {
            long elapsedMs = Duration.between(start, Instant.now()).toMillis();
            log.error("Unexpected error checking monitor={} url={} error={}",
                    monitor.getId(), monitor.getUrl(), e.getMessage());
            return HealthCheck.connectionError(monitor, e.getMessage(), elapsedMs);
        }
    }

    /**
     * Executa a requisição HTTP com timeout e tratamento de erros.
     *
     * Decisão: Usamos .block() aqui pois estamos em uma Virtual Thread.
     * Em uma Platform Thread tradicional, isso bloquearia o SO thread.
     * Em uma Virtual Thread, apenas "desmonta" a VT do carrier thread
     * durante o wait, liberando o carrier thread para outras VTs.
     */
    private HealthCheck executeCheck(Monitor monitor, Instant start) {
        Duration timeout = Duration.ofMillis(monitor.getTimeoutMs());

        return webClient
                .get()
                .uri(monitor.getUrl())
                .retrieve()
                // Não lança exceção em erros HTTP (tratamos nós mesmos)
                .onStatus(HttpStatusCode::isError, clientResponse ->
                        Mono.empty()
                )
                .toBodilessEntity()
                .timeout(timeout)
                .map(response -> {
                    long elapsedMs = Duration.between(start, Instant.now()).toMillis();
                    int statusCode = response.getStatusCode().value();

                    log.debug("Check completed monitor={} status={} responseTime={}ms",
                            monitor.getId(), statusCode, elapsedMs);

                    if (response.getStatusCode().is2xxSuccessful()
                            || response.getStatusCode().is3xxRedirection()) {
                        return HealthCheck.success(monitor, statusCode, elapsedMs);
                    } else {
                        return HealthCheck.httpError(monitor, statusCode, elapsedMs);
                    }
                })
                .onErrorResume(error -> {
                    long elapsedMs = Duration.between(start, Instant.now()).toMillis();
                    return Mono.just(handleError(monitor, error, elapsedMs));
                })
                .block(); // Safe com Virtual Threads — não bloqueia carrier thread
    }

    /**
     * Converte exceções do WebClient em HealthChecks tipados.
     */
    private HealthCheck handleError(Monitor monitor, Throwable error, long elapsedMs) {
        if (error instanceof java.util.concurrent.TimeoutException
                || error.getMessage() != null && error.getMessage().contains("timeout")) {
            log.warn("Timeout checking monitor={} url={} after={}ms",
                    monitor.getId(), monitor.getUrl(), elapsedMs);
            return HealthCheck.timeout(monitor, elapsedMs);
        }

        if (error instanceof WebClientRequestException requestException) {
            String message = extractConnectionErrorMessage(requestException);
            log.warn("Connection error checking monitor={} url={} error={}",
                    monitor.getId(), monitor.getUrl(), message);
            return HealthCheck.connectionError(monitor, message, elapsedMs);
        }

        log.error("Unknown error checking monitor={} url={}", monitor.getId(), monitor.getUrl(), error);
        return HealthCheck.connectionError(monitor, error.getMessage(), elapsedMs);
    }

    private String extractConnectionErrorMessage(WebClientRequestException e) {
        Throwable cause = e.getCause();
        if (cause != null) {
            return cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
        return e.getMessage();
    }
}
