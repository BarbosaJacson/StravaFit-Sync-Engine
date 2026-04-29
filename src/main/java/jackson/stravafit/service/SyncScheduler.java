package jackson.stravafit.service;

import jackson.stravafit.client.TelegramClient;
import jackson.stravafit.model.ActivityEntity;
import jackson.stravafit.model.StravaActivity;
import jackson.stravafit.model.TokenResponse;
import jackson.stravafit.repository.ActivityRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
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

    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        System.out.println("   [STARTUP] Iniciando motor e verificando pendências...");
        executarSincronizacao(this.accessToken);
        garantirEEnviarUltimoInsight();
    }

    // Agendamento principal: Ter, Qui, Sab (Horários de treino)
    @Scheduled(cron = "0 0,30 7,8 * * TUE,THU,SAT")
    public void scheduledSync() {
        System.out.println("\n=== [AGENDAMENTO AUTOMÁTICO DISPARADO] ===");
        executarSincronizacao(this.accessToken);
        garantirEEnviarUltimoInsight();
    }

    // TAREFA DE RECUPERAÇÃO: Tenta "curar" atividades sem insight a cada 1 hora
    @Scheduled(cron = "0 0 * * * *")
    public void recoveryTask() {
        System.out.println("   [RECOVERY] Verificando se há treinos pendentes de análise...");
        garantirEEnviarUltimoInsight();
    }

    private boolean executarSincronizacao(String tokenParaUsar) {
        try {
            ActivityService.ActivityPageResponse response = activityService.getActivitiesWithHeartRate(tokenParaUsar, 1);
            if (response.activities().isEmpty()) return false;

            StravaActivity activity = response.activities().get(0);
            if (activityRepository.existsById(activity.id())) return false;

            System.out.println("-> NOVO TREINO DETECTADO: " + activity.name());
            processarEEnviar(tokenParaUsar, activity);
            return true;

        } catch (HttpClientErrorException.Unauthorized e) {
            if (renovarToken()) {
                return executarSincronizacao(this.accessToken);
            } else {
                System.err.println("ERRO CRÍTICO: Falha na renovação do token. Sincronização abortada.");
                return false;
            }
        } catch (Exception e) {
            System.err.println("ERRO NA SINCRONIZAÇÃO: " + e.getMessage());
            return false;
        }
    }

    private void processarEEnviar(String token, StravaActivity activity) throws InterruptedException {
        List<StravaActivity.HeartRateZone> zones = activityService.getActivityZones(token, activity.id());
        List<StravaActivity.ActivityStream> streams = activityService.getActivityStreams(token, activity.id());
        List<Double> hrData = activityService.getHeartRateStream(streams);
        String zonaDominante = activityService.calculateDominantZoneSummary(hrData);
        List<StravaActivity.MinuteAnalysis> minuteAnalysis = activityService.aggregateStreamsByMinute(streams, zones);
        
        String insight = insightService.getActivityInsight(activity, minuteAnalysis);
        
        if (isValidInsight(insight)) {
            telegramClient.sendMessage("NOVO TREINO ANALISADO: " + activity.name() + "\n\n" + insight);
            activityService.saveActivity(activity, minuteAnalysis, zonaDominante, insight);
        } else {
            activityService.saveActivity(activity, minuteAnalysis, zonaDominante, null);
            System.err.println("   [GEMINI] Falha temporária. Atividade salva para análise posterior.");
        }
    }

    private void garantirEEnviarUltimoInsight() {
        activityRepository.findLastActivities(PageRequest.of(0, 1)).stream().findFirst().ifPresent(activity -> {
            String insight = activity.getGeminiInsight();
            
            if (!isValidInsight(insight)) {
                System.out.println("   [GEMINI] Tentando gerar análise para: " + activity.getName());
                insight = insightService.getActivityInsightFromEntity(activity);
                
                if (isValidInsight(insight)) {
                    activity.setGeminiInsight(insight);
                    activityRepository.save(activity);
                    telegramClient.sendMessage("FEEDBACK DO ÚLTIMO TREINO: " + activity.getName() + "\n\n" + insight);
                    System.out.println("   [TELEGRAM] Insight pendente enviado com sucesso.");
                } else {
                    System.out.println("   [GEMINI] Falha na tentativa de recuperação. Tentaremos novamente em breve.");
                }
            } else {
                String lembrete = "RELEMBRANDO ÚLTIMO TREINO: " + activity.getName() + "\n\n" + insight;
                telegramClient.sendMessage(lembrete);
                System.out.println("   [TELEGRAM] Lembrete enviado: " + activity.getName());
            }
        });
    }

    private boolean isValidInsight(String insight) {
        return insight != null && !insight.isEmpty() && !insight.startsWith("Erro");
    }

    private boolean renovarToken() {
        try {
            TokenResponse novoToken = authService.refreshToken(refreshToken);
            this.accessToken = novoToken.getAccessToken();
            System.out.println("Token renovado.");
            return true;
        } catch (Exception e) {
            System.err.println("ERRO CRÍTICO na renovação do token: " + e.getMessage());
            return false;
        }
    }
}
