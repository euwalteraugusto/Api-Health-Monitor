package com.monitor.controller;

import com.monitor.dto.request.CreateMonitorRequest;
import com.monitor.dto.request.UpdateMonitorRequest;
import com.monitor.dto.response.*;
import com.monitor.repository.HealthCheckRepository;
import com.monitor.service.MonitorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Controller REST para gerenciamento de monitors.
 *
 * Responsabilidade exclusiva: HTTP layer.
 * - Deserializar requests
 * - Validar entrada (via @Valid)
 * - Delegar para o Service
 * - Serializar resposta com status HTTP correto
 *
 * NUNCA coloque lógica de negócio aqui.
 */
@RestController
@RequestMapping("/api/v1/monitors")
@RequiredArgsConstructor
@Tag(name = "Monitors", description = "Gerenciamento de URLs monitoradas")
public class MonitorController {

    private final MonitorService monitorService;
    private final HealthCheckRepository healthCheckRepository;

    // ─── CRUD ────────────────────────────────────────────────────────────────

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Criar monitor",
        description = "Cadastra uma nova URL para monitoramento. O monitoramento inicia imediatamente.",
        responses = {
            @ApiResponse(responseCode = "201", description = "Monitor criado com sucesso"),
            @ApiResponse(responseCode = "400", description = "Request inválida")
        }
    )
    public ResponseEntity<MonitorResponse> create(@Valid @RequestBody CreateMonitorRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(monitorService.create(request));
    }

    @GetMapping
    @Operation(
        summary = "Listar monitors",
        description = "Retorna lista paginada de todos os monitors (exceto deletados)."
    )
    public ResponseEntity<PageResponse<MonitorResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String direction) {

        Sort sort = Sort.by(Sort.Direction.fromString(direction), sortBy);
        PageRequest pageable = PageRequest.of(page, size, sort);
        return ResponseEntity.ok(monitorService.findAll(pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Buscar monitor por ID")
    public ResponseEntity<MonitorResponse> getById(
            @PathVariable UUID id) {
        return ResponseEntity.ok(monitorService.findById(id));
    }

    @PatchMapping("/{id}")
    @Operation(
        summary = "Atualizar monitor",
        description = "Atualização parcial — apenas os campos enviados são atualizados."
    )
    public ResponseEntity<MonitorResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateMonitorRequest request) {
        return ResponseEntity.ok(monitorService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        summary = "Deletar monitor",
        description = "Soft delete — dados históricos são preservados."
    )
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        monitorService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ─── Ações de estado ─────────────────────────────────────────────────────

    @PostMapping("/{id}/activate")
    @Operation(summary = "Ativar monitoramento", description = "Retoma checagens de um monitor pausado.")
    public ResponseEntity<Void> activate(@PathVariable UUID id) {
        monitorService.activate(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/pause")
    @Operation(summary = "Pausar monitoramento", description = "Suspende checagens sem deletar o monitor.")
    public ResponseEntity<Void> pause(@PathVariable UUID id) {
        monitorService.pause(id);
        return ResponseEntity.ok().build();
    }

    // ─── Histórico e estatísticas ─────────────────────────────────────────────

    @GetMapping("/{id}/health-checks")
    @Operation(
        summary = "Histórico de verificações",
        description = "Retorna o histórico paginado de verificações realizadas para o monitor."
    )
    public ResponseEntity<PageResponse<HealthCheckResponse>> getHealthChecks(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        // Valida que monitor existe
        monitorService.findById(id);

        var pageable = PageRequest.of(page, size);
        var checks = healthCheckRepository
                .findByMonitorIdOrderByCheckedAtDesc(id, pageable)
                .map(HealthCheckResponse::from);

        return ResponseEntity.ok(PageResponse.from(checks));
    }

    @GetMapping("/{id}/stats")
    @Operation(
        summary = "Estatísticas de uptime",
        description = "Retorna uptime % e tempo médio de resposta para o período especificado."
    )
    public ResponseEntity<MonitorStatsResponse> getStats(
            @PathVariable UUID id,
            @Parameter(description = "Janela de análise em dias")
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(monitorService.getStats(id, days));
    }
}
