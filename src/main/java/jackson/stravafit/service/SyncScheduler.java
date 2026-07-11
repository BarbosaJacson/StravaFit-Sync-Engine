package jackson.stravafit.service;

import jackson.stravafit.client.TelegramClient;
import jackson.stravafit.model.ActivityEntity;
import jackson.stravafit.model.StravaActivity;
import jackson.stravafit.model.TokenResponse;
import jackson.stravafit.repository.ActivityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@Service
@RequiredArgsConstructor
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

    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        log.info("[STARTUP] Iniciando motor e verificando pendências...");
        boolean novoProcessado = executarSincronizacao();
        
        if (!novoProcessado) {
            log.info("[STARTUP] Nenhuma novidade no Strava. Enviando lembrete do último treino analisado.");
            enviarLembreteUltimoInsight();
        }
    }

    @Scheduled(cron = "0 0,30 7,8 * * TUE,THU,SAT")
    public void scheduledSync() {
        log.info("\n=== [AGENDAMENTO AUTOMÁTICO DISPARADO - PÓS-TREINO] ===");
        executarSincronizacao();
    }

    @Scheduled(cron = "0 5 5 * * TUE,THU,SAT")
    public void scheduledSleepCheck() {
        log.info("\n=== [AGENDAMENTO AUTOMÁTICO DISPARADO - PRÉ-TREINO] ===");
        log.info("   [SONO] Avaliando qualidade do sono para o treino de hoje...");
        
        String sleepQuality = simulateSleepQuality();
        String preWorkoutRecommendation = insightService.getPreWorkoutRecommendation(sleepQuality);
        
        telegramClient.sendMessage("AVALIAÇÃO PRÉ-TREINO (" + LocalDate.now() + "):\n\n" + preWorkoutRecommendation);
        log.info("   [TELEGRAM] Recomendação pré-treino enviada com base no sono.");
    }

    @Scheduled(cron = "0 0 * * * *")
    public void recoveryTask() {
        log.info("   [RECOVERY] Verificando se há treinos pendentes de análise...");
        garantirEEnviarUltimoInsight();
    }

    public boolean executarSincronizacao() {
        try {
            ActivityService.ActivityPageResponse response = activityService.getActivitiesWithHeartRate(this.accessToken, 1);
            if (response.activities().isEmpty()) {
                log.warn("   [STRAVA] Nenhuma atividade compatível encontrada recentemente.");
                return false;
            }

            StravaActivity activity = response.activities().get(0);
            
            if (activityRepository.existsById(activity.getId())) {
                log.info("-> Treino do dia (" + activity.getName() + ") já analisado. Sistema atualizado.");
                return false;
            }

            log.info("-> NOVO TREINO DETECTADO: " + activity.getName());
            processarEEnviar(this.accessToken, activity);
            return true;

        } catch (HttpClientErrorException.Unauthorized e) {
            if (renovarToken()) {
                return executarSincronizacao();
            } else {
                log.error("ERRO CRÍTICO: Falha na renovação do token. Sincronização abortada.");
                return false;
            }
        } catch (Exception e) {
            log.error("ERRO NA SINCRONIZAÇÃO: " + e.getMessage());
            return false;
        }
    }

    private void processarEEnviar(String token, StravaActivity activity) {
        try {
            List<StravaActivity.ActivityStream> streams = activityService.getActivityStreams(token, activity.getId());
            List<StravaActivity.MinuteAnalysis> minuteAnalysis = activityService.aggregateStreamsByMinute(streams, null);
            
            String insight = insightService.getActivityInsight(activity, minuteAnalysis, streams);
            
            if (isValidInsight(insight)) {
                telegramClient.sendMessage("NOVO TREINO ANALISADO: " + activity.getName() + "\n\n" + insight);
                String zonaDominante = activityService.calculateDominantZoneSummary(activityService.getHeartRateStream(streams));
                activityService.saveActivity(activity, minuteAnalysis, zonaDominante, insight);
            } else {
                log.warn("   [GEMINI] Falha temporária. Atividade será salva sem insight para análise posterior.");
                String zonaDominante = activityService.calculateDominantZoneSummary(activityService.getHeartRateStream(streams));
                activityService.saveActivity(activity, minuteAnalysis, zonaDominante, null);
            }
        } catch (Exception e) {
            log.error("Erro ao processar e enviar atividade {}: {}", activity.getId(), e.getMessage());
        }
    }

    private void garantirEEnviarUltimoInsight() {
        activityRepository.findTopByOrderByStartDateDesc().ifPresent(activity -> {
            String insight = activity.getGeminiInsight();
            
            if (!isValidInsight(insight)) {
                log.info("   [GEMINI] Tentando gerar análise para: " + activity.getName());
                // Passamos 'null' para os streams, pois não os temos aqui. O InsightService usará o fallback.
                String newInsight = insightService.getActivityInsightFromEntity(activity);
                
                if (isValidInsight(newInsight)) {
                    activity.setGeminiInsight(newInsight);
                    activityRepository.save(activity);
                    telegramClient.sendMessage("FEEDBACK DO ÚLTIMO TREINO: " + activity.getName() + "\n\n" + newInsight);
                    log.info("   [TELEGRAM] Insight pendente enviado com sucesso.");
                } else {
                    log.warn("   [GEMINI] Falha na tentativa de recuperação. Tentaremos novamente em breve.");
                }
            }
        });
    }

    private void enviarLembreteUltimoInsight() {
        activityRepository.findTopByOrderByStartDateDesc().ifPresent(activity -> {
            if (isValidInsight(activity.getGeminiInsight())) {
                String lembrete = "RELEMBRANDO ÚLTIMO TREINO: " + activity.getName() + "\n\n" + activity.getGeminiInsight();
                telegramClient.sendMessage(lembrete);
                log.info("   [TELEGRAM] Lembrete enviado: " + activity.getName());
            } else {
                log.info("   [STARTUP] Última atividade ainda não possui um insight válido para enviar como lembrete.");
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
            log.info("Token renovado.");
            return true;
        } catch (Exception e) {
            log.error("ERRO CRÍTICO na renovação do token: " + e.getMessage());
            return false;
        }
    }

    private String simulateSleepQuality() {
        Random random = new Random();
        int chance = random.nextInt(100);
        if (chance < 20) return "muito ruim";
        if (chance < 50) return "ruim";
        if (chance < 80) return "razoável";
        return "excelente";
    }
}
