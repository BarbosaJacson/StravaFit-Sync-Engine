package jackson.stravafit.controller;

import jackson.stravafit.service.SyncScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SyncController {

    private final SyncScheduler syncScheduler;

    @PostMapping("/sync")
    public ResponseEntity<String> triggerSync() {
        // A lógica de execução será movida para o SyncScheduler
        // para manter a organização.
        boolean success = syncScheduler.executarSincronizacao();
        if (success) {
            return ResponseEntity.ok("Sincronização executada com sucesso.");
        } else {
            return ResponseEntity.status(500).body("Falha na sincronização.");
        }
    }
}
