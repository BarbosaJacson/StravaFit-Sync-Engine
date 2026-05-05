package jackson.stravafit.service;

import jackson.stravafit.client.TelegramClient;
import jackson.stravafit.model.ActivityEntity;
import jackson.stravafit.model.StravaActivity;
import jackson.stravafit.model.TokenResponse;
import jackson.stravafit.repository.ActivityRepository;
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
public class SyncScheduler {

    private final ActivityService activityService;
    private final StravaAuthService authService;
    private final InsightService insightService;
    private final TelegramClient telegramClient;
    private final ActivityRepository activityRepository;
    private String accessToken;
    private final String refreshToken;

    public SyncScheduler(ActivityService activityService, 
                         StravaAuthService authService, 
                         InsightService insightService,
                         TelegramClient telegramClient,
                         ActivityRepository activityRepository,
                         @Value("${strava.access.token}") String accessToken,
                         @Value("${strava.refresh.token}") String refreshToken) {
        this.activityService = activityService;
        this.authService = authService;
        this.insightService = insightService;
        this.telegramClient = telegramClient;
        this.activityRepository = activityRepository;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        log.info("[STARTUP] Conexão estabelecida. Sincronizando atividades e verificando pendências...");
        String sleepQuality = simulateSleepQuality();
        executarSincronizacao(this.accessToken, sleepQuality);
    }

    // AGENDAMENTO ÚNICO: Ter, Qui, Sab às 07:00 (Relatório Consolidado)
    @Scheduled(cron = "0 0 7 * * TUE,THU,SAT")
    public void scheduledSync() {
        log.info("=== [RELATÓRIO MATINAL 07:00] ===");
        String sleepQuality = simulateSleepQuality();
        executarSincronizacao(this.accessToken, sleepQuality);
    }

    private boolean executarSincronizacao(String tokenParaUsar, String sleepQuality) {
        boolean geminiDisponivel = true;
        try {
            ActivityService.ActivityPageResponse response = activityService.getActivitiesWithHeartRate(tokenParaUsar, 1);
            if (response.activities().isEmpty()) {
                log.warn("[STRAVA] Nenhuma atividade compatível encontrada recentemente.");
                garantirEEnviarUltimoInsight(); // Tenta recuperar pendências mesmo sem treino novo
                return false;
            }

            LocalDate today = LocalDate.now();
            boolean treinoHojeDetectado = false;

            for (StravaActivity activity : response.activities()) {
                if (activityRepository.existsById(activity.id())) {
                    continue; // Pula o que já está no banco para economizar API
                }

                // Parse da data da atividade
                String dateStr = activity.startDateLocal();
                LocalDate activityDate = (dateStr.contains("Z") || dateStr.contains("+")) 
                        ? ZonedDateTime.parse(dateStr).toLocalDate() 
                        : LocalDate.parse(dateStr.substring(0, 10));

                if (activityDate.isEqual(today)) {
                    log.info("-> NOVO TREINO DETECTADO PARA O DIA: {}", activity.name());
                    geminiDisponivel = processarEEnviar(tokenParaUsar, activity);
                    treinoHojeDetectado = true;
                } else {
                    // CARGA HISTÓRICA: Se não é hoje e não está no banco, apenas persiste os dados
                    // Isso vai carregar suas 59 atividades gradualmente sem disparar 59 notificações
                    log.info("-> Carregando atividade histórica para o banco: {} ({})", activity.name(), activityDate);
                    persistirSemNotificar(tokenParaUsar, activity);
                }
            }

            // Se terminamos de olhar a lista e ninguém foi "hoje"
            if (!treinoHojeDetectado) {
                log.info("-> Nenhum treino detectado para hoje ({}). Enviando recomendação matinal.", today);
                enviarRecomendacaoPreTreino(today, sleepQuality);
            }

            if (geminiDisponivel) {
                garantirEEnviarUltimoInsight();
            }
            return true;

        } catch (HttpClientErrorException.Unauthorized e) {
            if (renovarToken()) {
                return executarSincronizacao(this.accessToken, sleepQuality);
            } else {
                log.error("ERRO CRÍTICO: Falha na renovação do token. Sincronização abortada.");
                return false;
            }
        } catch (Exception e) {
            log.error("ERRO NA SINCRONIZAÇÃO: {}", e.getMessage());
            return false;
        }
    }

    private boolean processarEEnviar(String token, StravaActivity activity) {
        DataProcessamento dados = buscarDadosCompletosAtividade(token, activity);
        List<StravaActivity.MinuteAnalysis> minuteAnalysis = dados.minuteAnalysis();
        String zonaDominante = dados.zonaDominante();
        String insight = insightService.getActivityInsight(activity, minuteAnalysis);
        boolean success = isValidInsight(insight);
        
        if (success) {
            telegramClient.sendMessage("NOVO TREINO ANALISADO: " + activity.name() + "\n\n" + insight);
            activityService.saveActivity(activity, minuteAnalysis, zonaDominante, insight);
        } else {
            activityService.saveActivity(activity, minuteAnalysis, zonaDominante, null);
            log.warn("[GEMINI] Falha temporária (503). Atividade salva para análise posterior.");
        }
        return success;
    }

    private void persistirSemNotificar(String token, StravaActivity activity) {
        try {
            DataProcessamento dados = buscarDadosCompletosAtividade(token, activity);
            activityService.saveActivity(activity, dados.minuteAnalysis(), dados.zonaDominante(), null);
        } catch (Exception e) {
            log.error("Erro ao persistir atividade histórica {}: {}", activity.id(), e.getMessage());
        }
    }

    private void enviarRecomendacaoPreTreino(LocalDate today, String sleepQuality) {
        String preWorkout = insightService.getPreWorkoutRecommendation(sleepQuality);
        String mensagem = "BOM DIA! 🌅\nNão detectei treino hoje (" + today + ").\n\n" +
                          "AVALIAÇÃO DO SONO: " + sleepQuality.toUpperCase() + "\n" + preWorkout + 
                          "\n\nSe for treinar mais tarde, registre no Strava para análise!";
        telegramClient.sendMessage(mensagem);
        log.info("[TELEGRAM] Recomendação matinal enviada.");
    }

    /**
     * Método auxiliar para centralizar a busca e agregação de dados da atividade.
     */
    private DataProcessamento buscarDadosCompletosAtividade(String token, StravaActivity activity) {
        List<StravaActivity.HeartRateZone> zones = activityService.getActivityZones(token, activity.id());
        List<StravaActivity.ActivityStream> streams = activityService.getActivityStreams(token, activity.id());
        List<Double> hrData = activityService.getHeartRateStream(streams);
        String zonaDominante = activityService.calculateDominantZoneSummary(hrData);
        List<StravaActivity.MinuteAnalysis> minuteAnalysis = activityService.aggregateStreamsByMinute(streams, zones);
        
        return new DataProcessamento(minuteAnalysis, zonaDominante);
    }

    /**
     * Record auxiliar para transporte de dados processados.
     */
    private record DataProcessamento(
            List<StravaActivity.MinuteAnalysis> minuteAnalysis,
            String zonaDominante
    ) {}

    private void garantirEEnviarUltimoInsight() {
        activityRepository.findLastActivities(PageRequest.of(0, 1)).stream().findFirst().ifPresent(activity -> {
            String insight = activity.getGeminiInsight();
            
            if (!isValidInsight(insight)) {
                log.info("[GEMINI] Tentando recuperar análise pendente para: {}", activity.getName());
                insight = insightService.getActivityInsightFromEntity(activity);
                
                if (isValidInsight(insight)) {
                    activity.setGeminiInsight(insight);
                    activityRepository.save(activity);
                    telegramClient.sendMessage("FEEDBACK DO ÚLTIMO TREINO: " + activity.getName() + "\n\n" + insight);
                    log.info("[TELEGRAM] Insight recuperado e enviado com sucesso.");
                } else {
                    log.warn("[GEMINI] O modelo ainda está indisponível. A recuperação será tentada no próximo ciclo.");
                }
            } else {
                String lembrete = "RELEMBRANDO ÚLTIMO TREINO: " + activity.getName() + "\n\n" + insight;
                telegramClient.sendMessage(lembrete);
                log.info("[TELEGRAM] Lembrete de treino anterior enviado: {}", activity.getName());
            }
        });
    }

    private boolean isValidInsight(String insight) {
        if (insight == null || insight.trim().isEmpty()) return false;
        
        // Validação aprimorada para detectar mensagens de erro técnicas da API do Gemini
        boolean isJsonError = insight.contains("\"error\"") || insight.contains("\"code\": 503");
        boolean containsTechnicalError = insight.toLowerCase().contains("service unavailable") || 
                                         insight.toLowerCase().contains("high demand");
        boolean startsWithError = insight.startsWith("Erro") || insight.startsWith("Error");
        
        return !isJsonError && !startsWithError && !containsTechnicalError;
    }

    private boolean renovarToken() {
        try {
            TokenResponse novoToken = authService.refreshToken(refreshToken);
            this.accessToken = novoToken.getAccessToken();
            log.info("Token Strava renovado com sucesso.");
            return true;
        } catch (Exception e) {
            log.error("ERRO CRÍTICO na renovação do token: {}", e.getMessage());
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
