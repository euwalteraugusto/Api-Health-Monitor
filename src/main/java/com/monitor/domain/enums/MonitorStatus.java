package com.monitor.domain.enums;

/**
 * Ciclo de vida de um Monitor.
 *
 * ACTIVE   → monitoramento em execução
 * PAUSED   → pausado pelo usuário, sem checagens
 * DELETED  → soft-delete, dados preservados para histórico
 */
public enum MonitorStatus {
    ACTIVE, PAUSED, DELETED
}
