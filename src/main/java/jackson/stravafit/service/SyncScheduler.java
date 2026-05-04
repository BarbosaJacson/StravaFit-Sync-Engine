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

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Random;

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
        System.out.println("\n=== [AGENDAMENTO AUTOMÁTICO DISPARADO - PÓS-TREINO] ===");
        executarSincronizacao(this.accessToken);
        garantirEEnviarUltimoInsight();
    }

    // NOVO AGENDAMENTO: Ter, Qui, Sab às 05:05 (Checagem de Sono e Plano Pré-Treino)
    @Scheduled(cron = "0 5 5 * * TUE,THU,SAT")
    public void scheduledSleepCheck() {
        System.out.println("\n=== [AGENDAMENTO AUTOMÁTICO DISPARADO - PRÉ-TREINO] ===");
        System.out.println("   [SONO] Avaliando qualidade do sono para o treino de hoje...");
        
        String sleepQuality = simulateSleepQuality(); // Simula a qualidade do sono
        String preWorkoutRecommendation = insightService.getPreWorkoutRecommendation(sleepQuality);
        
        telegramClient.sendMessage("AVALIAÇÃO PRÉ-TREINO (" + LocalDate.now() + "):\n\n" + preWorkoutRecommendation);
        System.out.println("   [TELEGRAM] Recomendação pré-treino enviada com base no sono.");
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
            if (response.activities().isEmpty()) {
                System.out.println("   [STRAVA] Nenhuma atividade compatível encontrada recentemente.");
                return false;
            }

            StravaActivity activity = response.activities().get(0);
            LocalDate activityDate = ZonedDateTime.parse(activity.startDateLocal()).toLocalDate();
            LocalDate today = LocalDate.now();

            // 1º Ajuste: Verifica se a atividade é do dia vigente
            if (!activityDate.isEqual(today)) {
                System.out.println("-> Última atividade (" + activity.name() + ") não é do dia vigente. Enviando mensagem de reprogramação.");
                telegramClient.sendMessage("ATENÇÃO: Não foi detectado treino no Strava para o dia " + today + ".\n\nPor favor, reprograme seu treino ou registre-o no Strava para análise.");
                return false;
            }

            // Se a atividade é do dia vigente, verifica se já foi processada
            if (activityRepository.existsById(activity.id())) {
                System.out.println("-> Treino do dia (" + activity.name() + ") já analisado. Sistema atualizado.");
                return false;
            }

            System.out.println("-> NOVO TREINO DETECTADO PARA O DIA: " + activity.name());
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

    // Simula a qualidade do sono (para fins de demonstração)
    private String simulateSleepQuality() {
        Random random = new Random();
        int chance = random.nextInt(100);
        if (chance < 20) return "muito ruim"; // 20% de chance de sono muito ruim
        if (chance < 50) return "ruim";      // 30% de chance de sono ruim (total 50%)
        if (chance < 80) return "razoável";  // 30% de chance de sono razoável (total 80%)
        return "excelente";                  // 20% de chance de sono excelente
    }
}
