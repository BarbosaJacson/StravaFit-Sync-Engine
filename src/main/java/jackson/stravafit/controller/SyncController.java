package jackson.stravafit.controller;

import jackson.stravafit.service.SyncScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SyncController {

    private final SyncScheduler syncScheduler;

    @PostMapping("/sync")
    public ResponseEntity<Map<String, String>> triggerSync() {
        log.info("[API] Gatilho manual recebido via controller. Disparando sincronização assíncrona...");

        // 🎯 Dispara o processo em background de forma assíncrona (Método agora retorna void)
        syncScheduler.executarSincronizacao();

        // 🎯 Retorna imediatamente em milissegundos sem esperar o processamento acabar!
        return ResponseEntity.ok(Map.of(
                "status", "Sincronização iniciada",
                "message", "O motor do StravaFit começou a processar os dados em segundo plano. Verifique os logs e o Telegram."
        ));
    }
}