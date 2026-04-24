package jackson.stravafit.service;

import jackson.stravafit.client.StravaClient;
import jackson.stravafit.model.StravaActivity;
import jackson.stravafit.model.TokenResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;

@Service
public class SyncScheduler {

    private final StravaClient stravaClient;
    private final StravaAuthService authService; // Injetando o novo serviço

    @Value("${strava.access-token}")
    private String accessToken;

    @Value("${strava.refresh-token}")
    private String refreshToken; // Precisamos do refresh para renovar

    public SyncScheduler(StravaClient stravaClient, StravaAuthService authService) {
        this.stravaClient = stravaClient;
        this.authService = authService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        executarSincronizacao(this.accessToken);
    }

    private void executarSincronizacao(String tokenParaUsar) {
        System.out.println("\n=== [MOTOR DE SINCRONIZAÇÃO INICIADO] ===");
        System.out.println("Buscando atividades recentes de Jackson...");

        try {
            List<StravaActivity> activities = stravaClient.getActivities(tokenParaUsar);

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
        } catch (HttpClientErrorException.Unauthorized e) {
            // Se cair aqui, o token expirou!
            System.out.println("AVISO: Access Token expirado. Tentando renovação automática...");

            try {
                TokenResponse novoToken = authService.refreshToken(refreshToken);
                this.accessToken = novoToken.getAccessToken(); // Atualiza para a próxima vez

                System.out.println("Token renovado com sucesso! Reiniciando busca...");

                // Chamada recursiva: tenta de novo com o novo token
                executarSincronizacao(this.accessToken);

            } catch (Exception authError) {
                System.err.println("ERRO CRÍTICO na renovação do token: " + authError.getMessage());
            }

        } catch (Exception e) {
            System.err.println("ERRO NA CONEXÃO: " + e.getMessage());
        }

        System.out.println("==========================================\n");
    }
}