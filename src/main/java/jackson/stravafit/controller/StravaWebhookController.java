package jackson.stravafit.controller;

import jackson.stravafit.service.SyncScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/strava/webhook")
@RequiredArgsConstructor
public class StravaWebhookController {

    private final SyncScheduler syncScheduler;

    @Value("${strava.webhook.verify-token}") // Removido o valor padrão para forçar a definição via ambiente
    private String verifyToken;

    /**
     * Validação do Webhook (GET) - Exigido pelo Strava para criar a assinatura.
     */
    @GetMapping
    public ResponseEntity<Map<String, String>> validateWebhook(
            @RequestParam("hub.mode") String mode,
            @RequestParam("hub.challenge") String challenge,
            @RequestParam("hub.verify_token") String token) {

        log.info("[WEBHOOK] Recebido pedido de validação do Strava...");

        if ("subscribe".equals(mode) && verifyToken.equals(token)) {
            log.info("[WEBHOOK] Validação bem-sucedida!");
            return ResponseEntity.ok(Map.of("hub.challenge", challenge));
        }

        log.warn("[WEBHOOK] Falha na validação: Token incorreto.");
        return ResponseEntity.status(403).build();
    }

    /**
     * Recebimento de Eventos (POST) - Chamado quando você salva uma atividade.
     */
    @PostMapping
    public ResponseEntity<Void> handleStravaEvent(@RequestBody Map<String, Object> payload) {
        log.info("[WEBHOOK] Evento recebido: {}", payload);

        // Verifica se o evento é de uma nova atividade
        String objectType = (String) payload.get("object_type");
        String aspectType = (String) payload.get("aspect_type"); // create, update, delete
        Object objectId = payload.get("object_id");

        if ("activity".equals(objectType) && "create".equals(aspectType)) {
            log.info("[WEBHOOK] Nova atividade detectada (ID: {})! Disparando sincronização...", objectId);
            syncScheduler.scheduledSync();
        }

        return ResponseEntity.ok().build();
    }
}