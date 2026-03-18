# 🟢 API Health Monitor

> Sistema de monitoramento de disponibilidade de APIs com Java 21, Spring Boot, Virtual Threads e notificações em tempo real.

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-green.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue.svg)](https://www.postgresql.org/)
[![Docker](https://img.shields.io/badge/Docker-Compose-blue.svg)](https://docs.docker.com/compose/)

---

## 📋 Sumário

- [Visão Geral](#visão-geral)
- [Arquitetura](#arquitetura)
- [Stack Técnica](#stack-técnica)
- [Virtual Threads — A Decisão Central](#virtual-threads)
- [Estrutura de Pacotes](#estrutura-de-pacotes)
- [Modelagem de Dados](#modelagem-de-dados)
- [API REST](#api-rest)
- [Notificações](#notificações)
- [Observabilidade](#observabilidade)
- [Como Executar](#como-executar)
- [Roadmap](#roadmap)

---

## Visão Geral

O **API Health Monitor** verifica periodicamente a disponibilidade de URLs cadastradas, registra tempo de resposta e status HTTP, e envia alertas via webhook quando um serviço cai ou se recupera.

### Funcionalidades

| Feature | Detalhe |
|---|---|
| Monitoramento periódico | Intervalo configurável por URL (30s–1h) |
| Detecção inteligente | Failure threshold evita falsos positivos |
| Notificações | Discord e Telegram via Webhook |
| Histórico | Todos os checks registrados para análise |
| Uptime % | Calculado por janela de tempo configurável |
| Virtual Threads | Escala para milhares de monitors simultâneos |
| Circuit Breaker | Resiliência com Resilience4j |
| Métricas | Prometheus + Grafana ready |
| API REST | Documentada com OpenAPI 3 / Swagger UI |

---

## Arquitetura

O sistema segue uma **arquitetura em camadas clara**, com separação de responsabilidades bem definida:

```
┌─────────────────────────────────────────────────────────────────┐
│                         HTTP Clients                            │
│                    (REST API consumers)                         │
└──────────────────────────┬──────────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────────┐
│                    Controller Layer                             │
│              MonitorController (REST endpoints)                 │
│         Responsabilidade: HTTP in/out, validação de input       │
└──────────────────────────┬──────────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────────┐
│                     Service Layer                               │
│  ┌──────────────────┐  ┌──────────────────┐  ┌───────────────┐ │
│  │  MonitorService  │  │ MonitoringService │  │Notification   │ │
│  │  (CRUD)          │  │ (check logic)     │  │Service        │ │
│  └──────────────────┘  └────────┬─────────┘  └───────────────┘ │
│                                 │                               │
│                    ┌────────────▼────────────┐                  │
│                    │    HttpCheckClient      │                  │
│                    │    (WebClient + Netty)  │                  │
│                    └─────────────────────────┘                  │
└──────────────────────────┬──────────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────────┐
│                   Repository Layer                              │
│     MonitorRepository  HealthCheckRepository  Notification      │
│              (Spring Data JPA + PostgreSQL)                     │
└─────────────────────────────────────────────────────────────────┘

              ┌────────────────────────────────┐
              │       Scheduler Layer          │
              │  MonitoringScheduler           │
              │  @Scheduled + Virtual Threads  │
              │  → dispara MonitoringService   │
              └────────────────────────────────┘
```

### Fluxo de Execução Completo

```
[Scheduler tick — a cada 10s]
        │
        ▼
MonitorRepository.findDueMonitors(now)
        │
        ▼ (para cada monitor em paralelo)
[Virtual Thread criada]
        │
        ▼
MonitoringService.performCheck(monitor)
        │
        ├─── HttpCheckClient.check(monitor)
        │         │
        │         ▼
        │    WebClient.get(url)
        │    .timeout(monitor.timeoutMs)
        │    .block()  ← seguro em Virtual Thread
        │         │
        │         ▼
        │    HealthCheck (SUCCESS | HTTP_ERROR | TIMEOUT | CONNECTION_ERROR)
        │
        ├─── healthCheckRepository.save(check)
        │
        ├─── monitor.recordSuccess() ou monitor.recordFailure()
        │         │
        │         ▼ (se transição de estado)
        ├─── notificationService.notifyDown() ou notifyRecovered()
        │         │
        │         ▼
        │    Strategy selecionada (Discord/Telegram)
        │    webhookClient.post(payload)
        │    notificationRepository.save(notification)
        │
        └─── monitorRepository.save(monitor)
```

---

## Stack Técnica

| Tecnologia | Versão | Justificativa |
|---|---|---|
| Java | 21 | Virtual Threads (Project Loom) — concorrência massiva sem complexidade |
| Spring Boot | 3.2 | Autoconfiguração, suporte nativo a VT, ecossistema maduro |
| Spring WebFlux (WebClient) | 6.x | I/O não-bloqueante; mais eficiente que RestTemplate para alto volume |
| Spring Data JPA | 3.x | Produtividade + queries customizadas onde necessário |
| PostgreSQL | 16 | Confiabilidade, suporte a UUID nativo, particionamento futuro |
| Flyway | 9.x | Migrations versionadas e rastreáveis |
| Resilience4j | 2.x | Circuit Breaker + Retry com suporte reativo |
| Micrometer + Prometheus | 1.12 | Métricas de produção |
| Testcontainers | 1.19 | Testes de integração realistas com banco real |
| MockWebServer (OkHttp) | — | Simula endpoints HTTP nos testes |
| Docker + Compose | — | Portabilidade e ambiente reproduzível |

---

## Virtual Threads

### O Problema: Thread Pool Tradicional

Com um `ThreadPoolExecutor` de 200 threads e 10.000 monitors:

```
Thread Pool: [T1][T2]...[T200]
Queue:       [M201][M202]...[M10000]   ← 9.800 monitors esperando!

Problema: T1 está bloqueada aguardando resposta HTTP de M1.
          T1 não pode processar M201 enquanto espera I/O.
          Thread starvation em picos de carga.
```

### A Solução: Virtual Threads (Java 21)

```java
// Uma Virtual Thread por monitor. Sem limite prático.
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

monitors.forEach(monitor ->
    executor.submit(() -> monitoringService.performCheck(monitor))
);
```

```
Virtual Threads: [VT1][VT2][VT3]...[VT10000]
Platform Threads (carriers): [PT1][PT2][PT3][PT4]   ← apenas 4!

Quando VT1 faz block() no WebClient:
  → VT1 é "unmounted" do PT1
  → PT1 fica livre para executar VT201
  → VT1 "monta" de volta quando a resposta chega
```

### Comparação Numérica

| Métrica | Platform Threads (200) | Virtual Threads |
|---|---|---|
| Monitors simultâneos | ~200 | ~10.000+ |
| Memória por thread | ~1MB | ~10KB |
| Memória total (10k monitors) | ~10GB ❌ | ~100MB ✅ |
| Throughput (checks/min) | ~12.000 | ~600.000 |
| Código alterado | — | Zero (mesma API) |

---

## Estrutura de Pacotes

```
src/main/java/com/monitor/
│
├── ApiHealthMonitorApplication.java     ← Entry point
│
├── config/
│   └── AppConfig.java                   ← WebClient, VT Executor, OpenAPI
│
├── controller/
│   └── MonitorController.java           ← REST endpoints
│
├── domain/
│   ├── model/
│   │   ├── Monitor.java                 ← Entidade principal
│   │   ├── HealthCheck.java             ← Registro imutável de cada check
│   │   └── Notification.java           ← Log de notificações enviadas
│   └── enums/
│       ├── MonitorStatus.java           ← ACTIVE | PAUSED | DELETED
│       ├── CheckResult.java             ← SUCCESS | HTTP_ERROR | TIMEOUT | ...
│       ├── NotificationType.java        ← DOWN | RECOVERED
│       └── NotificationStatus.java      ← PENDING | SENT | FAILED
│
├── dto/
│   ├── request/
│   │   ├── CreateMonitorRequest.java    ← Java Record + Bean Validation
│   │   └── UpdateMonitorRequest.java    ← PATCH parcial
│   └── response/
│       ├── MonitorResponse.java
│       ├── HealthCheckResponse.java
│       ├── MonitorStatsResponse.java
│       └── PageResponse.java
│
├── exception/
│   ├── MonitorNotFoundException.java
│   └── GlobalExceptionHandler.java     ← RFC 7807 Problem Details
│
├── notification/
│   └── strategy/
│       ├── NotificationStrategy.java    ← Interface (Strategy Pattern)
│       ├── DiscordNotificationStrategy.java
│       └── TelegramNotificationStrategy.java
│
├── repository/
│   ├── MonitorRepository.java
│   ├── HealthCheckRepository.java
│   └── NotificationRepository.java
│
├── scheduler/
│   └── MonitoringScheduler.java        ← @Scheduled + Virtual Threads
│
└── service/
    ├── MonitorService.java              ← Interface
    ├── MonitoringService.java           ← Orquestra o check completo
    ├── HttpCheckClient.java             ← WebClient HTTP checker
    ├── NotificationService.java         ← Roteador de notificações
    └── impl/
        └── MonitorServiceImpl.java      ← CRUD + stats
```

---

## Modelagem de Dados

```
┌──────────────────────────────────────────────────────────┐
│                        monitors                          │
├──────────────────┬───────────────┬───────────────────────┤
│ id               │ UUID (PK)     │ uuid_generate_v4()    │
│ name             │ VARCHAR(200)  │ NOT NULL              │
│ url              │ VARCHAR(2048) │ NOT NULL              │
│ check_interval_s │ INTEGER       │ DEFAULT 60            │
│ timeout_ms       │ INTEGER       │ DEFAULT 5000          │
│ status           │ VARCHAR(20)   │ ACTIVE|PAUSED|DELETED │
│ is_up            │ BOOLEAN       │ DEFAULT TRUE          │
│ consecutive_fail │ INTEGER       │ DEFAULT 0             │
│ failure_threshold│ INTEGER       │ DEFAULT 2             │
│ next_check_at    │ TIMESTAMPTZ   │ NULL = check agora    │
│ last_checked_at  │ TIMESTAMPTZ   │                       │
│ webhook_url      │ VARCHAR(2048) │ NULLABLE              │
│ tags             │ VARCHAR(500)  │                       │
│ owner_id         │ VARCHAR(100)  │ multi-tenancy futuro  │
│ created_at       │ TIMESTAMPTZ   │ auto                  │
│ updated_at       │ TIMESTAMPTZ   │ auto (trigger)        │
└──────────────────┴───────────────┴───────────────────────┘
           │                            │
           │ 1:N                        │ 1:N
           ▼                            ▼
┌─────────────────────────┐   ┌──────────────────────────┐
│      health_checks      │   │      notifications       │
├──────────────┬──────────┤   ├────────────┬─────────────┤
│ id           │ UUID     │   │ id         │ UUID        │
│ monitor_id   │ UUID(FK) │   │ monitor_id │ UUID(FK)    │
│ result       │ VARCHAR  │   │ type       │ DOWN|RECOV  │
│ http_status  │ INTEGER  │   │ status     │ PEND|SENT.. │
│ response_ms  │ BIGINT   │   │ webhook_url│ VARCHAR     │
│ error_message│ VARCHAR  │   │ payload    │ TEXT        │
│ resolved_ip  │ VARCHAR  │   │ response   │ TEXT        │
│ checked_at   │ TSTMPTZ  │   │ error_msg  │ VARCHAR     │
└──────────────┴──────────┘   │ attempt_ct │ INTEGER     │
                              │ created_at │ TIMESTAMPTZ │
                              │ sent_at    │ TIMESTAMPTZ │
                              └────────────┴─────────────┘
```

**Índices estratégicos:**

```sql
-- Query mais crítica: monitors prontos para check (executa a cada 10s)
CREATE INDEX idx_monitors_status_next_check
    ON monitors (status, next_check_at ASC NULLS FIRST)
    WHERE status = 'ACTIVE';

-- Histórico por monitor (query mais comum da API)
CREATE INDEX idx_health_checks_monitor_checked
    ON health_checks (monitor_id, checked_at DESC);

-- Análise de falhas (apenas registros com erro)
CREATE INDEX idx_health_checks_result
    ON health_checks (result, checked_at DESC)
    WHERE result != 'SUCCESS';
```

---

## API REST

Base URL: `http://localhost:8080/api/v1`  
Swagger UI: `http://localhost:8080/swagger-ui.html`

### Endpoints

#### `POST /monitors` — Criar monitor

```json
// Request
{
  "name": "API de Pagamentos",
  "url": "https://api.pagamentos.com/health",
  "checkIntervalSeconds": 60,
  "timeoutMs": 5000,
  "webhookUrl": "https://discord.com/api/webhooks/xxx/yyy",
  "tags": "production,payment,critical",
  "failureThreshold": 2
}

// Response 201 Created
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "API de Pagamentos",
  "url": "https://api.pagamentos.com/health",
  "checkIntervalSeconds": 60,
  "timeoutMs": 5000,
  "status": "ACTIVE",
  "isUp": true,
  "consecutiveFailures": 0,
  "failureThreshold": 2,
  "webhookUrl": "https://discord.com/api/webhooks/xxx/yyy",
  "tags": "production,payment,critical",
  "nextCheckAt": null,
  "lastCheckedAt": null,
  "createdAt": "2024-01-15T10:30:00Z",
  "updatedAt": "2024-01-15T10:30:00Z"
}
```

#### `GET /monitors` — Listar monitors (paginado)

```
GET /monitors?page=0&size=20&sortBy=createdAt&direction=DESC
```

```json
{
  "content": [...],
  "page": 0,
  "size": 20,
  "totalElements": 47,
  "totalPages": 3,
  "first": true,
  "last": false
}
```

#### `GET /monitors/{id}/stats` — Estatísticas de uptime

```
GET /monitors/550e8400.../stats?days=30
```

```json
{
  "monitorId": "550e8400-e29b-41d4-a716-446655440000",
  "periodDays": 30,
  "uptimePercentage": 99.87,
  "averageResponseTimeMs": 142.5
}
```

#### `GET /monitors/{id}/health-checks` — Histórico

```json
{
  "content": [
    {
      "id": "...",
      "monitorId": "...",
      "result": "SUCCESS",
      "httpStatus": 200,
      "responseTimeMs": 145,
      "errorMessage": null,
      "checkedAt": "2024-01-15T10:35:00Z"
    },
    {
      "id": "...",
      "result": "TIMEOUT",
      "httpStatus": null,
      "responseTimeMs": 5001,
      "errorMessage": "Request timed out after 5001ms",
      "checkedAt": "2024-01-15T10:34:00Z"
    }
  ]
}
```

#### `POST /monitors/{id}/pause` e `POST /monitors/{id}/activate`

```
HTTP 200 OK (sem corpo)
```

#### Erros (RFC 7807)

```json
// 404 Not Found
{
  "type": "https://api.health-monitor.dev/errors/not-found",
  "title": "Resource Not Found",
  "status": 404,
  "detail": "Monitor with ID 550e8400... not found",
  "timestamp": "2024-01-15T10:30:00Z"
}

// 400 Validation Error
{
  "type": "https://api.health-monitor.dev/errors/validation",
  "title": "Validation Error",
  "status": 400,
  "detail": "Validation failed",
  "errors": {
    "url": "URL must start with http:// or https://",
    "checkIntervalSeconds": "Check interval must be at least 30 seconds"
  }
}
```

---

## Notificações

### Configurar Discord

1. No seu servidor Discord: **Server Settings → Integrations → Webhooks → New Webhook**
2. Copie a URL: `https://discord.com/api/webhooks/{id}/{token}`
3. Cadastre como `webhookUrl` no monitor

**Alert de serviço caído:**

```
🔴 Serviço Indisponível

O serviço API de Pagamentos está fora do ar!

🌐 URL: `https://api.pagamentos.com/health`
📊 Status: ❌ DOWN
🔢 HTTP:   503
⚡ Resposta: 5001ms
⚠️ Erro: Connection refused
```

**Alert de recuperação:**

```
🟢 Serviço Recuperado

O serviço API de Pagamentos voltou ao normal.

🌐 URL: `https://api.pagamentos.com/health`
📊 Status: ✅ UP
🔢 HTTP:   200
⚡ Resposta: 138ms
```

### Configurar Telegram

1. Abra o Telegram → busque `@BotFather` → `/newbot` → copie o TOKEN
2. Envie uma mensagem para o bot
3. Acesse `https://api.telegram.org/bot{TOKEN}/getUpdates` → copie o `chat_id`
4. `webhookUrl`: `https://api.telegram.org/bot{TOKEN}/sendMessage?chat_id={CHAT_ID}`

### Adicionar novo canal (Slack, PagerDuty, etc.)

```java
@Service
public class SlackNotificationStrategy implements NotificationStrategy {

    @Override
    public String getChannelType() { return "slack"; }

    @Override
    public boolean supports(String webhookUrl) {
        return webhookUrl != null && webhookUrl.contains("hooks.slack.com");
    }

    @Override
    public Notification send(Monitor monitor, NotificationType type, HealthCheck check) {
        // Implementar formato Slack Block Kit
    }
}
// Pronto! O sistema detecta automaticamente via injeção de lista.
```

---

## Observabilidade

### Métricas Prometheus disponíveis em `/actuator/prometheus`

| Métrica | Tipo | Descrição |
|---|---|---|
| `monitor.checks.total` | Counter | Total de verificações realizadas |
| `monitor.checks.success` | Counter | Verificações bem-sucedidas |
| `monitor.checks.failure` | Counter | Verificações com falha |
| `monitor.alerts.down` | Counter | Alertas DOWN enviados |
| `monitor.alerts.recovered` | Counter | Alertas RECOVERED enviados |
| `monitor.check.duration` | Timer | Duração de cada verificação (por monitor) |
| `monitor.scheduler.active_checks` | Gauge | Verificações em execução no momento |
| `resilience4j.circuitbreaker.*` | Mixed | Estado do circuit breaker |

### Health Check

```
GET /actuator/health

{
  "status": "UP",
  "components": {
    "db": { "status": "UP", "details": { "database": "PostgreSQL" } },
    "diskSpace": { "status": "UP" },
    "ping": { "status": "UP" }
  }
}
```

---

## Como Executar

### Pré-requisitos

- Java 21+
- Docker e Docker Compose
- Maven 3.8+ (ou use o `mvnw` incluído)

### 1. Apenas banco de dados (modo desenvolvimento)

```bash
# Sobe apenas o PostgreSQL
docker-compose up postgres -d

# Executa a aplicação localmente
./mvnw spring-boot:run
```

### 2. Stack completa

```bash
# Build + sobe tudo
docker-compose up --build -d

# Acompanhar logs
docker-compose logs -f app

# API disponível em: http://localhost:8080
# Swagger UI:        http://localhost:8080/swagger-ui.html
```

### 3. Com observabilidade (Prometheus + Grafana)

```bash
docker-compose --profile observability up -d

# Prometheus: http://localhost:9090
# Grafana:    http://localhost:3000 (admin/admin)
```

### 4. Testes

```bash
# Testes unitários + integração (requer Docker para Testcontainers)
./mvnw test

# Apenas testes unitários
./mvnw test -Dtest="*Unit*"
```

### Testando a API rapidamente

```bash
# Criar um monitor
curl -X POST http://localhost:8080/api/v1/monitors \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Google",
    "url": "https://www.google.com",
    "checkIntervalSeconds": 30,
    "webhookUrl": "https://discord.com/api/webhooks/SEU_WEBHOOK"
  }'

# Listar monitors
curl http://localhost:8080/api/v1/monitors

# Ver histórico de checks
curl http://localhost:8080/api/v1/monitors/{id}/health-checks

# Ver uptime dos últimos 7 dias
curl http://localhost:8080/api/v1/monitors/{id}/stats?days=7

# Pausar monitoramento
curl -X POST http://localhost:8080/api/v1/monitors/{id}/pause
```

---

## Roadmap

### Próximas features (ordem de impacto para portfólio)

#### 🔒 Autenticação JWT
```
Adicionar Spring Security + JWT para autenticação.
Cada usuário gerencia apenas seus próprios monitors.
Multi-tenancy via owner_id (campo já existe no schema).
```

#### 📊 Dashboard React
```
SPA com React + Recharts mostrando:
- Status em tempo real de todos os monitors
- Gráfico de response time (últimas 24h)
- Histórico de incidentes
- Uptime % com gauge chart
Comunicação via SSE (Server-Sent Events) para updates em tempo real.
```

#### 📈 SLA Tracking
```
Calcular e armazenar SLA mensal por monitor.
Relatório de SLA exportável em PDF.
Alertas de SLA em risco (ex: "você está em 99.5%, meta é 99.9%").
```

#### 🔔 Status Page pública
```
Página pública (sem login) mostrando status dos serviços.
Similar ao status.github.com ou status.openai.com.
```

#### 🌐 Multi-region checks
```
Verificar URLs a partir de múltiplas regiões geográficas.
Detectar problemas de DNS ou CDN específicos por região.
```

---

## Decisões Técnicas Documentadas

| Decisão | Alternativa considerada | Motivo da escolha |
|---|---|---|
| Virtual Threads | CompletableFuture / Reactor | Simplicidade de código síncrono com escala de código reativo |
| WebClient + `.block()` | RestTemplate / HTTP puro | I/O não-bloqueante internamente; bloco seguro em VT |
| Strategy Pattern p/ notificações | if/else ou switch | Open/Closed: novo canal sem modificar código existente |
| UUID como PK | BIGSERIAL | Evita enumeração de recursos; facilita sharding futuro |
| Soft delete | Hard delete | Preserva histórico de checks vinculados ao monitor |
| Flyway | Hibernate DDL auto | Migrations rastreáveis, reversíveis e auditáveis em produção |
| Records para DTOs | Classes com Lombok | Imutabilidade garantida pelo compilador; menos boilerplate |
| RFC 7807 para erros | Formato customizado | Padrão de mercado; facilita consumo por clientes genéricos |
| Testcontainers | H2 in-memory | Testa com PostgreSQL real; sem divergências de dialeto SQL |

---

*Projeto desenvolvido como demonstração de engenharia backend com Java 21 e Spring Boot.*
