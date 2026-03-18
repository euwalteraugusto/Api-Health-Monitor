package com.monitor.domain.enums;

/**
 * Resultado de uma checagem de saúde.
 *
 * SUCCESS          → HTTP 2xx/3xx dentro do tempo limite
 * HTTP_ERROR       → HTTP 4xx/5xx
 * TIMEOUT          → Sem resposta dentro do timeout configurado
 * CONNECTION_ERROR → Falha de DNS, conexão recusada, SSL inválido, etc.
 */
public enum CheckResult {
    SUCCESS, HTTP_ERROR, TIMEOUT, CONNECTION_ERROR
}
