package com.monitor.domain.model;

import com.monitor.domain.enums.MonitorStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes unitários puros (sem Spring, sem banco, sem Docker) das regras de negócio de {@link Monitor}.
 */
class MonitorTest {

    private Monitor monitor() {
        return Monitor.builder().name("API").url("https://exemplo.com").build();
    }

    @Test
    void valoresPadraoDoBuilder() {
        Monitor m = monitor();

        assertThat(m.getCheckIntervalSeconds()).isEqualTo(60);
        assertThat(m.getFailureThreshold()).isEqualTo(2);
        assertThat(m.getStatus()).isEqualTo(MonitorStatus.ACTIVE);
        assertThat(m.getIsUp()).isTrue();
        assertThat(m.getConsecutiveFailures()).isZero();
    }

    @Test
    void primeiraFalhaNaoMarcaComoDown() {
        Monitor m = monitor();

        boolean justWentDown = m.recordFailure();

        assertThat(justWentDown).isFalse();
        assertThat(m.getIsUp()).isTrue();
        assertThat(m.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    void atingirOLimiarMarcaDownEUmaUnicaVezNotifica() {
        Monitor m = monitor();

        m.recordFailure();
        boolean noLimiar = m.recordFailure();
        boolean aposLimiar = m.recordFailure();

        assertThat(noLimiar).isTrue();
        assertThat(aposLimiar).isFalse();
        assertThat(m.getIsUp()).isFalse();
    }

    @Test
    void sucessoRecuperaEZeraContador() {
        Monitor m = monitor();
        m.recordFailure();
        m.recordFailure();
        boolean estavaDown = !m.getIsUp();

        m.recordSuccess();

        assertThat(m.getIsUp()).isTrue();
        assertThat(m.getConsecutiveFailures()).isZero();
        assertThat(m.justRecovered(estavaDown)).isTrue();
        assertThat(m.justRecovered(false)).isFalse();
    }

    @Test
    void checagemAgendaProximaExecucaoPeloIntervalo() {
        Monitor m = monitor();
        m.setCheckIntervalSeconds(30);
        Instant antes = Instant.now();

        m.recordSuccess();

        assertThat(m.getNextCheckAt()).isAfterOrEqualTo(antes.plusSeconds(30));
        assertThat(m.isDue()).isFalse();
    }

    @Test
    void monitorSemProximaChecagemEstaVencido() {
        assertThat(monitor().isDue()).isTrue();
    }

    @Test
    void apenasStatusActiveEhAtivo() {
        Monitor m = monitor();
        assertThat(m.isActive()).isTrue();

        m.setStatus(MonitorStatus.PAUSED);
        assertThat(m.isActive()).isFalse();

        m.setStatus(MonitorStatus.DELETED);
        assertThat(m.isActive()).isFalse();
    }
}
