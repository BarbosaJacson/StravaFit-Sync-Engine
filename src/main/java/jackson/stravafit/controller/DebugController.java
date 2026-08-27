package jackson.stravafit.controller;

import jackson.stravafit.service.SyncScheduler;
import jackson.stravafit.service.WeeklyPlannerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/debug")
@RequiredArgsConstructor
public class DebugController {

    private final SyncScheduler syncScheduler;
    private final WeeklyPlannerService weeklyPlannerService;

    /**
     * Dispara a sincronização completa manualmente.
     * URL: https://sua-app.run.app/api/v1/debug/force-sync
     */
    @GetMapping("/force-sync")
    public ResponseEntity<Map<String, String>> forceSync() {
        log.info("[DEBUG] Gatilho manual disparado para sincronização geral.");

        // ✨ CORREÇÃO: Alinhado com o novo método único do SyncScheduler
        syncScheduler.executarSincronizacao();

        return ResponseEntity.ok(Map.of(
                "status", "Sincronização disparada",
                "message", "Verifique os logs do Cloud Run e o seu Telegram."
        ));
    }
    /**
     * Dispara o planejamento semanal manualmente para testes.
     * URL: GET /api/v1/debug/force-weekly-plan
     */
    @GetMapping("/force-weekly-plan")
    public ResponseEntity<Map<String, String>> forceWeeklyPlan() {
        log.info("[DEBUG] Gatilho manual disparado para geração do Plano Semanal.");

        // Executa o planejamento da semana sob demanda
        weeklyPlannerService.gerenciarPlanejamentoSemanal();

        return ResponseEntity.ok(Map.of(
                "status", "Planejamento Semanal Concluído",
                "message", "Prescrições geradas no MySQL e enviadas ao Telegram."
        ));
    }

    @GetMapping("/health")
    public ResponseEntity<String> healthCheck() {
        return ResponseEntity.ok("O motor StravaFit está vivo e rodando no Google Cloud!");
    }
}