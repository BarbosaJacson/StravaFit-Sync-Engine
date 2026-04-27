package jackson.stravafit.service;

import jackson.stravafit.client.TelegramClient;
import jackson.stravafit.model.StravaActivity;
import jackson.stravafit.model.TokenResponse;
import jackson.stravafit.repository.ActivityRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;

@Service
public class SyncScheduler {

    private final ActivityService activityService;
    private final StravaAuthService authService;
    private final InsightService insightService;
    private final TelegramClient telegramClient;
    private final ActivityRepository activityRepository;

    @Value("${strava.access.token}")
    private String accessToken;

    @Value("${strava.refresh.token}")
    private String refreshToken;

    public SyncScheduler(ActivityService activityService, 
                         StravaAuthService authService, 
                         InsightService insightService,
                         TelegramClient telegramClient,
                         ActivityRepository activityRepository) {
        this.activityService = activityService;
        this.authService = authService;
        this.insightService = insightService;
        this.telegramClient = telegramClient;
        this.activityRepository = activityRepository;
    }

    // Executa ao ligar o computador (para garantir que nada ficou para trás)
    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        System.out.println("   [STARTUP] Verificação inicial de treinos...");
        executarSincronizacao(this.accessToken);
    }

    // AGENDAMENTO: Terça, Quinta e Sábado às 07:00, 07:30 e 08:00
    // Cron format: "sec min hour day month day-of-week"
    @Scheduled(cron = "0 0,30 7,8 * * TUE,THU,SAT")
    public void scheduledSync() {
        System.out.println("\n=== [AGENDAMENTO AUTOMÁTICO DISPARADO] ===");
        executarSincronizacao(this.accessToken);
    }

    private void executarSincronizacao(String tokenParaUsar) {
        System.out.println("=== [MOTOR DE SINCRONIZAÇÃO INICIADO] ===");

        try {
            ActivityService.ActivityPageResponse response = activityService.getActivitiesWithHeartRate(tokenParaUsar, 1);

            if (response.activities().isEmpty()) {
                System.out.println("Nenhuma atividade compatível encontrada.");
                return;
            }

            StravaActivity activity = response.activities().get(0);

            if (activityRepository.existsById(activity.id())) {
                System.out.println("-> Última atividade (" + activity.name() + ") já analisada anteriormente.");
                return;
            }

            System.out.println("-> Detectado NOVO TREINO: " + activity.name());
            
            List<StravaActivity.HeartRateZone> zones = activityService.getActivityZones(tokenParaUsar, activity.id());
            List<StravaActivity.ActivityStream> streams = activityService.getActivityStreams(tokenParaUsar, activity.id());
            List<Double> hrData = activityService.getHeartRateStream(streams);
            String zonaDominante = activityService.calculateDominantZoneSummary(hrData);

            List<StravaActivity.MinuteAnalysis> minuteAnalysis = activityService.aggregateStreamsByMinute(streams, zones);
            
            System.out.println("   [GEMINI] Gerando análise de performance profissional...");
            String insight = insightService.getActivityInsight(activity, minuteAnalysis);
            
            System.out.println("   [TELEGRAM] Enviando feedback para o celular...");
            String telegramMessage = String.format("COACH STRAVAFIT: %s\n\n%s", activity.name(), insight);
            telegramClient.sendMessage(telegramMessage);

            activityService.saveActivity(activity, minuteAnalysis, zonaDominante, insight);
            System.out.println("   [DATABASE] Atividade salva com sucesso.");

        } catch (HttpClientErrorException.Unauthorized e) {
            System.out.println("AVISO: Token expirado. Renovando...");
            try {
                TokenResponse novoToken = authService.refreshToken(refreshToken);
                this.accessToken = novoToken.getAccessToken();
                executarSincronizacao(this.accessToken);
            } catch (Exception authError) {
                System.err.println("ERRO CRÍTICO na renovação: " + authError.getMessage());
            }
        } catch (Exception e) {
            System.err.println("ERRO NA CONEXÃO: " + e.getMessage());
        }
        
        System.out.println("=== [MOTOR EM STANDBY] ===\n");
    }
}
