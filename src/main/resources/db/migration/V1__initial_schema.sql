-- ============================================================================
-- V1__initial_schema.sql
-- API Health Monitor — Schema inicial
--
-- Decisões de modelagem:
-- - UUID como PK: evita enumeração, facilita distribuição futura
-- - Índices estratégicos: cobrem as queries mais críticas do sistema
-- - health_checks append-only: nunca atualizamos, apenas inserimos
-- - soft-delete em monitors: preserva histórico de checks e notificações
-- ============================================================================

-- Extensão para suporte nativo a UUID
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ─── TABLE: monitors ─────────────────────────────────────────────────────────
CREATE TABLE monitors (
    id                      UUID        DEFAULT uuid_generate_v4() PRIMARY KEY,
    name                    VARCHAR(200) NOT NULL,
    url                     VARCHAR(2048) NOT NULL,
    check_interval_seconds  INTEGER     NOT NULL DEFAULT 60
                            CHECK (check_interval_seconds BETWEEN 30 AND 3600),
    timeout_ms              INTEGER     NOT NULL DEFAULT 5000
                            CHECK (timeout_ms BETWEEN 1000 AND 30000),
    status                  VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                            CHECK (status IN ('ACTIVE', 'PAUSED', 'DELETED')),
    is_up                   BOOLEAN     DEFAULT TRUE,
    consecutive_failures    INTEGER     NOT NULL DEFAULT 0,
    failure_threshold       INTEGER     NOT NULL DEFAULT 2
                            CHECK (failure_threshold BETWEEN 1 AND 10),
    next_check_at           TIMESTAMPTZ,
    last_checked_at         TIMESTAMPTZ,
    webhook_url             VARCHAR(2048),
    tags                    VARCHAR(500),
    owner_id                VARCHAR(100),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Índices para o scheduler (query mais frequente do sistema)
CREATE INDEX idx_monitors_status_next_check
    ON monitors (status, next_check_at ASC NULLS FIRST)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_monitors_owner
    ON monitors (owner_id)
    WHERE owner_id IS NOT NULL;

-- ─── TABLE: health_checks ────────────────────────────────────────────────────
-- Tabela append-only. Potencial para centenas de milhões de registros.
-- Candidata a particionamento por range (checked_at) em produção.
CREATE TABLE health_checks (
    id               UUID        DEFAULT uuid_generate_v4() PRIMARY KEY,
    monitor_id       UUID        NOT NULL REFERENCES monitors(id) ON DELETE CASCADE,
    result           VARCHAR(30) NOT NULL
                     CHECK (result IN ('SUCCESS', 'HTTP_ERROR', 'TIMEOUT', 'CONNECTION_ERROR')),
    http_status      INTEGER,
    response_time_ms BIGINT,
    error_message    VARCHAR(1000),
    resolved_ip      VARCHAR(45),
    checked_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Índice composto: suporta queries históricas por monitor (mais comum)
CREATE INDEX idx_health_checks_monitor_checked
    ON health_checks (monitor_id, checked_at DESC);

-- Índice para análise de falhas
CREATE INDEX idx_health_checks_result
    ON health_checks (result, checked_at DESC)
    WHERE result != 'SUCCESS';

-- ─── TABLE: notifications ────────────────────────────────────────────────────
CREATE TABLE notifications (
    id            UUID        DEFAULT uuid_generate_v4() PRIMARY KEY,
    monitor_id    UUID        NOT NULL REFERENCES monitors(id) ON DELETE CASCADE,
    type          VARCHAR(20) NOT NULL CHECK (type IN ('DOWN', 'RECOVERED')),
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    webhook_url   VARCHAR(2048),
    payload       TEXT,
    response_body TEXT,
    error_message VARCHAR(1000),
    attempt_count INTEGER     NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sent_at       TIMESTAMPTZ
);

CREATE INDEX idx_notifications_monitor
    ON notifications (monitor_id, created_at DESC);

CREATE INDEX idx_notifications_status
    ON notifications (status)
    WHERE status IN ('PENDING', 'FAILED');

-- ─── FUNCTION: auto-update updated_at ────────────────────────────────────────
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER trigger_monitors_updated_at
    BEFORE UPDATE ON monitors
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ─── VIEWS ───────────────────────────────────────────────────────────────────
-- View de resumo para o dashboard
CREATE OR REPLACE VIEW monitor_summary AS
SELECT
    m.id,
    m.name,
    m.url,
    m.status,
    m.is_up,
    m.consecutive_failures,
    m.last_checked_at,
    m.next_check_at,
    -- Uptime nos últimos 30 dias
    ROUND(
        (COUNT(hc.id) FILTER (WHERE hc.result = 'SUCCESS' AND hc.checked_at >= NOW() - INTERVAL '30 days') * 100.0 /
        NULLIF(COUNT(hc.id) FILTER (WHERE hc.checked_at >= NOW() - INTERVAL '30 days'), 0))::numeric,
        2
    ) AS uptime_30d_pct,
    -- Tempo médio de resposta (últimas 24h)
    ROUND(AVG(hc.response_time_ms) FILTER (
        WHERE hc.result = 'SUCCESS' AND hc.checked_at >= NOW() - INTERVAL '24 hours'
    )::numeric, 0) AS avg_response_ms_24h
FROM monitors m
LEFT JOIN health_checks hc ON hc.monitor_id = m.id
WHERE m.status != 'DELETED'
GROUP BY m.id;

-- ─── SEED DATA (apenas para desenvolvimento) ─────────────────────────────────
-- Remova em ambiente de produção!
INSERT INTO monitors (name, url, check_interval_seconds, timeout_ms, tags)
VALUES
    ('Google', 'https://www.google.com', 60, 5000, 'example,external'),
    ('GitHub API', 'https://api.github.com', 120, 5000, 'example,api,external'),
    ('Cloudflare DNS', 'https://1.1.1.1', 30, 3000, 'example,dns');
