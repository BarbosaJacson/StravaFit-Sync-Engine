package jackson.stravafit.controller;

import jackson.stravafit.service.SyncScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sync")
@RequiredArgsConstructor
public class SyncController {

    private final SyncScheduler syncScheduler;

    /**
     * Endpoint para disparar a sincronização manualmente via URL.
     * Útil para testes no Google Cloud.
     */
    @GetMapping("/trigger")
    public ResponseEntity<String> triggerSync() {
        System.out.println("   [MANUAL] Gatilho de sincronização acionado via API.");
        // Usamos o token atual da classe (o scheduler cuidará da renovação se necessário)
        syncScheduler.scheduledSync();
        return ResponseEntity.ok("Sincronização disparada com sucesso! Verifique os logs e o Telegram.");
    }
}