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
        boolean novoProcessado = executarSincronizacao(this.accessToken);
        
        if (!novoProcessado) {
            log.info("[STARTUP] Nenhuma novidade no Strava. Enviando lembrete do último treino analisado.");
            enviarLembreteUltimoInsight();
        }
    }

    // Agendamento principal: Ter, Qui, Sab (Horários de treino)
    @Scheduled(cron = "0 0,30 7,8 * * TUE,THU,SAT")
    public void scheduledSync() {
        log.info("\n=== [AGENDAMENTO AUTOMÁTICO DISPARADO - PÓS-TREINO] ===");
        executarSincronizacao(this.accessToken);
    }

    // NOVO AGENDAMENTO: Ter, Qui, Sab às 05:05 (Checagem de Sono e Plano Pré-Treino)
    @Scheduled(cron = "0 5 5 * * TUE,THU,SAT")
    public void scheduledSleepCheck() {
        log.info("\n=== [AGENDAMENTO AUTOMÁTICO DISPARADO - PRÉ-TREINO] ===");
        log.info("   [SONO] Avaliando qualidade do sono para o treino de hoje...");
        
        String sleepQuality = simulateSleepQuality(); // Simula a qualidade do sono
        String preWorkoutRecommendation = insightService.getPreWorkoutRecommendation(sleepQuality);
        
        telegramClient.sendMessage("AVALIAÇÃO PRÉ-TREINO (" + LocalDate.now() + "):\n\n" + preWorkoutRecommendation);
        log.info("   [TELEGRAM] Recomendação pré-treino enviada com base no sono.");
    }

    // TAREFA DE RECUPERAÇÃO: Tenta "curar" atividades sem insight a cada 1 hora
    @Scheduled(cron = "0 0 * * * *")
    public void recoveryTask() {
        log.info("   [RECOVERY] Verificando se há treinos pendentes de análise...");
        garantirEEnviarUltimoInsight();
    }

    private boolean executarSincronizacao(String tokenParaUsar) {
        try {
            ActivityService.ActivityPageResponse response = activityService.getActivitiesWithHeartRate(tokenParaUsar, 1);
            if (response.activities().isEmpty()) {
                log.warn("   [STRAVA] Nenhuma atividade compatível encontrada recentemente.");
                return false;
            }

            StravaActivity activity = response.activities().get(0);
            LocalDate activityDate = ZonedDateTime.parse(activity.getStartDateLocal()).toLocalDate();
            LocalDate today = LocalDate.now();

            if (!activityDate.isEqual(today)) {
                log.info("-> Última atividade (" + activity.getName() + ") não é do dia vigente. Enviando mensagem de reprogramação.");
                telegramClient.sendMessage("ATENÇÃO: Não foi detectado treino no Strava para o dia " + today + ".\n\nPor favor, reprograme seu treino ou registre-o no Strava para análise.");
                return false;
            }

            if (activityRepository.existsById(activity.getId())) {
                log.info("-> Treino do dia (" + activity.getName() + ") já analisado. Sistema atualizado.");
                return false;
            }

            log.info("-> NOVO TREINO DETECTADO PARA O DIA: " + activity.getName());
            processarEEnviar(tokenParaUsar, activity);
            return true;

        } catch (HttpClientErrorException.Unauthorized e) {
            if (renovarToken()) {
                return executarSincronizacao(this.accessToken);
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
            List<StravaActivity.HeartRateZone> zones = activityService.getActivityZones(token, activity.getId());
            List<StravaActivity.ActivityStream> streams = activityService.getActivityStreams(token, activity.getId());
            List<Double> hrData = activityService.getHeartRateStream(streams);
            String zonaDominante = activityService.calculateDominantZoneSummary(hrData);
            List<StravaActivity.MinuteAnalysis> minuteAnalysis = activityService.aggregateStreamsByMinute(streams, zones);
            
            String insight = insightService.getActivityInsight(activity, minuteAnalysis);
            
            if (isValidInsight(insight)) {
                telegramClient.sendMessage("NOVO TREINO ANALISADO: " + activity.getName() + "\n\n" + insight);
                activityService.saveActivity(activity, minuteAnalysis, zonaDominante, insight);
            } else {
                activityService.saveActivity(activity, minuteAnalysis, zonaDominante, null);
                log.warn("   [GEMINI] Falha temporária. Atividade salva para análise posterior.");
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
                insight = insightService.getActivityInsightFromEntity(activity);
                
                if (isValidInsight(insight)) {
                    activity.setGeminiInsight(insight);
                    activityRepository.save(activity);
                    telegramClient.sendMessage("FEEDBACK DO ÚLTIMO TREINO: " + activity.getName() + "\n\n" + insight);
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
