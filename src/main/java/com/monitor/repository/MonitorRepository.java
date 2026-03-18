package com.monitor.repository;

import com.monitor.domain.enums.MonitorStatus;
import com.monitor.domain.model.Monitor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositório de Monitors.
 *
 * Decisão: Usamos Spring Data JPA com queries customizadas onde necessário.
 * Para queries de alta frequência (findDueMonitors), usamos índices específicos.
 */
@Repository
public interface MonitorRepository extends JpaRepository<Monitor, UUID> {

    /**
     * Busca todos os monitors ativos que estão prontos para checagem.
     *
     * Esta query é executada a cada tick do scheduler (default: 10s).
     * O índice idx_monitors_next_check é essencial para performance aqui.
     *
     * @param now Timestamp atual para comparação
     * @return Lista de monitors prontos para verificação
     */
    @Query("""
        SELECT m FROM Monitor m
        WHERE m.status = 'ACTIVE'
        AND (m.nextCheckAt IS NULL OR m.nextCheckAt <= :now)
        ORDER BY m.nextCheckAt ASC NULLS FIRST
        """)
    List<Monitor> findDueMonitors(@Param("now") Instant now);

    /**
     * Lista monitors com paginação, excluindo deletados.
     */
    Page<Monitor> findByStatusNot(MonitorStatus status, Pageable pageable);

    /**
     * Busca por owner para multi-tenancy futuro.
     */
    Page<Monitor> findByOwnerIdAndStatusNot(String ownerId, MonitorStatus status, Pageable pageable);

    /**
     * Soft delete: apenas muda o status para DELETED.
     */
    @Modifying
    @Query("UPDATE Monitor m SET m.status = 'DELETED' WHERE m.id = :id")
    int softDelete(@Param("id") UUID id);

    /**
     * Contagem de monitors ativos por status de saúde.
     * Usado para métricas do dashboard.
     */
    @Query("""
        SELECT COUNT(m) FROM Monitor m
        WHERE m.status = 'ACTIVE' AND m.isUp = :isUp
        """)
    long countByActiveAndHealth(@Param("isUp") Boolean isUp);

    Optional<Monitor> findByIdAndStatusNot(UUID id, MonitorStatus status);
}
