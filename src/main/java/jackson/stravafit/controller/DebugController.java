package jackson.stravafit.controller;

import jackson.stravafit.service.SyncScheduler;
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

    /**
     * Dispara a sincronização completa manualmente.
     * URL: https://sua-app.run.app/api/v1/debug/force-sync
     */
    @GetMapping("/force-sync")
    public ResponseEntity<Map<String, String>> forceSync() {
        log.info("[DEBUG] Gatilho manual disparado para sincronização geral.");
        
        // Chamamos o método que já faz a lógica de verificar treinos novos ou pendentes
        syncScheduler.scheduledSync();
        
        return ResponseEntity.ok(Map.of(
            "status", "Sincronização disparada",
            "message", "Verifique os logs do Cloud Run e o seu Telegram."
        ));
    }

    @GetMapping("/health")
    public ResponseEntity<String> healthCheck() {
        return ResponseEntity.ok("O motor StravaFit está vivo e rodando no Google Cloud!");
    }
}