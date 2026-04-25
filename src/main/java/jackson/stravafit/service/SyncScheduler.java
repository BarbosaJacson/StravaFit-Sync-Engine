package jackson.stravafit.service;

import jackson.stravafit.model.StravaActivity;
import jackson.stravafit.model.TokenResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.util.ArrayList;
import java.util.List;

@Service
public class SyncScheduler {

    private final ActivityService activityService;
    private final StravaAuthService authService;

    @Value("${strava.access-token}")
    private String accessToken;

    @Value("${strava.refresh-token}")
    private String refreshToken;

    public SyncScheduler(ActivityService activityService, StravaAuthService authService) {
        this.activityService = activityService;
        this.authService = authService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        executarSincronizacao(this.accessToken);
    }

    private void executarSincronizacao(String tokenParaUsar) {
        System.out.println("\n=== [MOTOR DE SINCRONIZAÇÃO INICIADO] ===");
        System.out.println("Buscando histórico completo de Jackson...");

        int pagina = 1;
        boolean temMaisDados = true;
        int totalProcessado = 0;

        try {
            while (temMaisDados) {
                // Chamamos o serviço que já retorna os dados filtrados (BPM > 0)
                ActivityService.ActivityPageResponse response = activityService.getActivitiesWithHeartRate(tokenParaUsar, pagina);

                if (response.rawCount() == 0) {
                    temMaisDados = false;
                } else {
                    response.activities().forEach(activity -> {
                        System.out.printf("-> Atividade: %s | Data: %s | Distância: %.2f km | BPM Médio: %.0f | Tempo Total: %d min%n",
                                activity.name(),
                                activity.startDateLocal(),
                                activity.distanceKm(),
                                activity.averageHeartRate(),
                                activity.elapsedTimeMinutes());

                        // Coleta de dados detalhados (pós-filtro)
                        try {
                            List<StravaActivity.HeartRateZone> zones = activityService.getActivityZones(tokenParaUsar, activity.id());
                            List<StravaActivity.ActivityStream> streams = activityService.getActivityStreams(tokenParaUsar, activity.id());

                            // Processamento inteligente: Transforma segundos em minutos com zonas
                            List<StravaActivity.MinuteAnalysis> minuteAnalysis = activityService.aggregateStreamsByMinute(streams, zones);
                            
                            System.out.println("   [Análise Minuto a Minuto]");
                            minuteAnalysis.forEach(m -> {
                                System.out.printf("      Min %02d: %.0f BPM (Zona %d) | Alt: %.1fm | Cad: %.0f rpm%n", 
                                        m.minute(), m.averageHeartRate(), m.zone(), m.averageElevation(), m.averageCadence());
                            });
                            
                            // Essencial para evitar o erro de I/O (Rate Limit do Strava: 100 req / 15 min)
                            // 2000ms (2 segundos) garantem que as ~120 requisições levem mais de 15 min, 
                            // mantendo o fluxo seguro e estável.
                            Thread.sleep(2000); 

                        } catch (Exception e) {
                            System.err.println("   [ERRO] Falha ao buscar detalhes da atividade " + activity.id() + ": " + e.getMessage());
                        }
                    });

                    totalProcessado += response.activities().size();
                    System.out.println("--- Página " + pagina + " processada (" + response.activities().size() + " válidos de " + response.rawCount() + ") ---");
                    pagina++; // Incrementa para buscar a próxima página no próximo loop
                }
            }

            System.out.println("\nSincronização finalizada! Total de atividades: " + totalProcessado);

        } catch (HttpClientErrorException.Unauthorized e) {
            System.out.println("AVISO: Access Token expirado. Tentando renovação automática...");
            try {
                TokenResponse novoToken = authService.refreshToken(refreshToken);
                this.accessToken = novoToken.getAccessToken();
                System.out.println("Token renovado! Reiniciando carga completa...");

                // Reinicia do zero com o token novo
                executarSincronizacao(this.accessToken);
                return; // Encerra a execução atual para não duplicar logs
            } catch (Exception authError) {
                System.err.println("ERRO CRÍTICO na renovação: " + authError.getMessage());
            }
        } catch (Exception e) {
            System.err.println("ERRO NA CONEXÃO: " + e.getMessage());
        }

        System.out.println("==========================================\n");
    }
}