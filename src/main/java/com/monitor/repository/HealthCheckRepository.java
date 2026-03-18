package com.monitor.repository;

import com.monitor.domain.enums.CheckResult;
import com.monitor.domain.model.HealthCheck;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HealthCheckRepository extends JpaRepository<HealthCheck, UUID> {

    /**
     * Histórico paginado de um monitor específico.
     */
    Page<HealthCheck> findByMonitorIdOrderByCheckedAtDesc(UUID monitorId, Pageable pageable);

    /**
     * Último check realizado para um monitor.
     * Usado para exibir status atual.
     */
    Optional<HealthCheck> findTopByMonitorIdOrderByCheckedAtDesc(UUID monitorId);

    /**
     * Calcula uptime % em uma janela de tempo.
     *
     * Decisão: Fazer o cálculo no banco é mais eficiente do que trazer
     * todos os registros e calcular na aplicação.
     */
    @Query("""
        SELECT
            COUNT(CASE WHEN hc.result = 'SUCCESS' THEN 1 END) * 100.0 / COUNT(hc)
        FROM HealthCheck hc
        WHERE hc.monitor.id = :monitorId
        AND hc.checkedAt >= :since
        """)
    Optional<Double> calculateUptimePercentage(
        @Param("monitorId") UUID monitorId,
        @Param("since") Instant since
    );

    /**
     * Tempo médio de resposta em uma janela de tempo.
     */
    @Query("""
        SELECT AVG(hc.responseTimeMs)
        FROM HealthCheck hc
        WHERE hc.monitor.id = :monitorId
        AND hc.result = 'SUCCESS'
        AND hc.checkedAt >= :since
        """)
    Optional<Double> calculateAverageResponseTime(
        @Param("monitorId") UUID monitorId,
        @Param("since") Instant since
    );

    /**
     * Contagem de checks por resultado em uma janela de tempo.
     * Usado para gráficos de distribuição de erros.
     */
    @Query("""
        SELECT hc.result, COUNT(hc)
        FROM HealthCheck hc
        WHERE hc.monitor.id = :monitorId
        AND hc.checkedAt >= :since
        GROUP BY hc.result
        """)
    List<Object[]> countByResultSince(
        @Param("monitorId") UUID monitorId,
        @Param("since") Instant since
    );

    /**
     * Série temporal para gráfico de response time.
     * Retorna médias agrupadas por hora.
     */
    @Query(value = """
        SELECT
            date_trunc('hour', checked_at) as hour,
            AVG(response_time_ms) as avg_ms,
            COUNT(*) as total,
            COUNT(CASE WHEN result = 'SUCCESS' THEN 1 END) as successes
        FROM health_checks
        WHERE monitor_id = :monitorId
        AND checked_at >= :since
        GROUP BY date_trunc('hour', checked_at)
        ORDER BY hour ASC
        """, nativeQuery = true)
    List<Object[]> getResponseTimeTimeSeries(
        @Param("monitorId") UUID monitorId,
        @Param("since") Instant since
    );

    /**
     * Deleta registros antigos para manutenção do tamanho do banco.
     * Executado por job de limpeza periódico.
     */
    @Query("DELETE FROM HealthCheck hc WHERE hc.checkedAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
