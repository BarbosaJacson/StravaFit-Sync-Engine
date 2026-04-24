package jackson.stravafit.service;

import jackson.stravafit.client.StravaClient;
import jackson.stravafit.model.StravaActivity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SyncScheduler {

    private final StravaClient stravaClient;

    // O Spring vai buscar isso no application.properties,
    // que por sua vez busca nas Environment Variables do IntelliJ
    @Value("${strava.access-token}")
    private String accessToken;

    public SyncScheduler(StravaClient stravaClient) {
        this.stravaClient = stravaClient;
    }

    // Este método roda automaticamente assim que o projeto termina de subir
    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        System.out.println("\n=== [MOTOR DE SINCRONIZAÇÃO INICIADO] ===");
        System.out.println("Buscando atividades recentes de Jackson...");

        try {
            List<StravaActivity> activities = stravaClient.getActivities(accessToken);

            if (activities.isEmpty()) {
                System.out.println("Nenhuma atividade encontrada recentemente.");
            } else {
                activities.forEach(activity -> {
                    System.out.printf("-> Atividade: %s | Distância: %.2f km | BPM Médio: %.0f%n",
                            activity.name(),
                            activity.distance() / 1000,
                            activity.averageHeartRate());
                });
            }
        } catch (Exception e) {
            System.err.println("ERRO NA CONEXÃO: " + e.getMessage());
            System.out.println("Dica: Verifique se o seu Access Token ainda é válido!");
        }

        System.out.println("==========================================\n");
    }
}