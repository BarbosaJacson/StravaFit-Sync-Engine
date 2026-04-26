package jackson.stravafit.service;

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

    private final ActivityService activityService;
    private final StravaAuthService authService;
    private final InsightService insightService;

    @Value("${strava.access-token}")
    private String accessToken;

    @Value("${strava.refresh-token}")
    private String refreshToken;

    public SyncScheduler(ActivityService activityService, StravaAuthService authService, InsightService insightService) {
        this.activityService = activityService;
        this.authService = authService;
        this.insightService = insightService;
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
                ActivityService.ActivityPageResponse response = activityService.getActivitiesWithHeartRate(tokenParaUsar, pagina);

                if (response.rawCount() == 0) {
                    temMaisDados = false;
                } else {
                    response.activities().forEach(activity -> {
                        try {
                            List<StravaActivity.HeartRateZone> zones = activityService.getActivityZones(tokenParaUsar, activity.id());
                            List<StravaActivity.ActivityStream> streams = activityService.getActivityStreams(tokenParaUsar, activity.id());
                            List<Double> hrData = activityService.getHeartRateStream(streams);
                            String zonaDominante = activityService.calculateDominantZoneSummary(hrData);

                            System.out.printf("-> Atividade: %s | Data: %s | Distância: %.2f km | BPM Médio: %.0f | BPM Máx: %.0f | Tempo Total: %d min | Intensidade: %s%n",
                                    activity.name(),
                                    activity.startDateLocal(),
                                    activity.distanceKm(),
                                    activity.averageHeartRate(),
                                    activity.maxHeartRate(),
                                    activity.elapsedTimeMinutes(),
                                    zonaDominante);

                            List<StravaActivity.MinuteAnalysis> minuteAnalysis = activityService.aggregateStreamsByMinute(streams, zones);
                            
                            // Geração de Insight com Gemini
                            System.out.println("   [GEMINI] Gerando análise de performance...");
                            String insight = insightService.getActivityInsight(activity, minuteAnalysis);
                            System.out.println("   [INSIGHT]: " + insight);

                            // SALVAMENTO NO BANCO DE DADOS (Agora com Insight)
                            activityService.saveActivity(activity, minuteAnalysis, zonaDominante, insight);
                            System.out.println("   [DATABASE] Atividade persistida no MySQL com sucesso.");

                            // Aumentado para 5 segundos para respeitar o Rate Limit do Gemini (Quota 429)
                            Thread.sleep(5000); 

                        } catch (Exception e) {
                            System.err.println("   [ERRO] Falha ao processar atividade " + activity.id() + ": " + e.getMessage());
                        }
                    });

                    totalProcessado += response.activities().size();
                    System.out.println("--- Página " + pagina + " processada (" + response.activities().size() + " válidos de " + response.rawCount() + ") ---");
                    pagina++;
                }
            }

            System.out.println("\nSincronização finalizada! Total de atividades: " + totalProcessado);

        } catch (HttpClientErrorException.Unauthorized e) {
            System.out.println("AVISO: Access Token expirado. Tentando renovação automática...");
            try {
                TokenResponse novoToken = authService.refreshToken(refreshToken);
                this.accessToken = novoToken.getAccessToken();
                System.out.println("Token renovado! Reiniciando carga completa...");
                executarSincronizacao(this.accessToken);
            } catch (Exception authError) {
                System.err.println("ERRO CRÍTICO na renovação: " + authError.getMessage());
            }
        } catch (Exception e) {
            System.err.println("ERRO NA CONEXÃO: " + e.getMessage());
        }

        System.out.println("==========================================\n");
    }
}
