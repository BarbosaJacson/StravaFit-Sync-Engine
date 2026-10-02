package jackson.stravafit.service;

import jackson.stravafit.client.TelegramClient;
import jackson.stravafit.model.ActivityEntity;
import jackson.stravafit.model.StravaActivity;
import jackson.stravafit.model.TokenResponse;
import jackson.stravafit.model.UserEntity;
import jackson.stravafit.repository.ActivityRepository;
import jackson.stravafit.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncScheduler {

    private final ActivityService activityService;
    private final StravaAuthService authService;
    private final InsightService insightService;
    private final TelegramClient telegramClient;
    private final ActivityRepository activityRepository;
    private final UserRepository userRepository;
    private final WeeklyPlannerService weeklyPlannerService;

    @Value("${strava.auto-start:false}")
    private boolean autoStart;

    @Value("${strava.access.token}")
    private String accessToken;

    @Value("${strava.refresh.token}")
    private String refreshToken;

    @EventListener(ApplicationReadyEvent.class)
    public void autoExecutarNoStartup() {
        if (autoStart) {
            log.info("[STARTUP] Aplicação iniciada com sucesso. Disparando motor automaticamente...");
            executarSincronizacao();
        } else {
            log.info("[STARTUP] Modo Webhook ativo. Aguardando requisições externas na porta 8080...");
        }
    }

    @Async
    public void executarSincronizacao() {
        log.info("\n=== [MOTOR] Sincronização sob demanda iniciada ===");
        try {
            ActivityService.ActivityPageResponse response = activityService.getActivitiesWithHeartRate(this.accessToken, 1);
            if (response.activities().isEmpty()) {
                log.warn("   [STRAVA] Nenhuma atividade compatível encontrada recentemente.");
                enviarLembreteUltimoInsight();
                return;
            }

            // 1. Ordena todas as atividades da API do Strava da mais recente para a mais antiga
            List<StravaActivity> atividadesOrdenadas = response.activities().stream()
                    .sorted(Comparator.comparing(
                            act -> parseDate(act.getStartDateLocal()),
                            Comparator.nullsLast(Comparator.reverseOrder())
                    ))
                    .toList();

            // 2. Seleciona a primeira atividade mais recente que seja elegível (nova ou recente sem insight)
            Optional<StravaActivity> treinoPendente = atividadesOrdenadas.stream()
                    .filter(activity -> {
                        Optional<ActivityEntity> entityOpt = activityRepository.findById(activity.getId());

                        // Se for uma atividade nova que ainda não foi salva no banco -> Processa!
                        if (entityOpt.isEmpty()) {
                            return true;
                        }

                        // Se já existe, reprocessa apenas se for dos últimos 7 dias e estiver sem insight válido
                        ActivityEntity entity = entityOpt.get();
                        boolean recente = isActivityRecent(activity.getStartDateLocal(), 7);
                        return recente && !isValidInsight(entity.getGeminiInsight());
                    })
                    .findFirst();
            if (treinoPendente.isPresent()) {
                StravaActivity activity = treinoPendente.get();
                log.info("-> NOVO TREINO DETECTADO PARA ANÁLISE: {} (ID: {})", activity.getName(), activity.getId());
                processarEEnviar(this.accessToken, activity);
            } else {
                log.info("-> Todos os treinos recentes do Strava já estão processados no MySQL. Acionando fallback...");
                enviarLembreteUltimoInsight();
            }

        } catch (HttpClientErrorException.Unauthorized e) {
            if (renovarToken()) {
                executarSincronizacao();
            } else {
                log.error("ERRO CRÍTICO: Falha na renovação do token. Sincronização abortada.");
            }
        } catch (Exception e) {
            log.error("ERRO NA SINCRONIZAÇÃO: {}", e.getMessage());
        }
    }

    private void processarEEnviar(String token, StravaActivity activity) {
        try {
            // 🎯 Busca as métricas do atleta no MySQL (ID 1)
            UserEntity user = userRepository.findById(1L)
                    .orElseThrow(() -> new IllegalStateException("Atleta principal não cadastrado no MySQL."));

            int hrMax = user.getHrMax();
            int hrResting = user.getHrResting();

            List<StravaActivity.ActivityStream> streams = activityService.getActivityStreams(token, activity.getId());

            // 🎯 Repassa hrMax e hrResting para a agregação por minuto
            List<StravaActivity.MinuteAnalysis> minuteAnalysis = activityService.aggregateStreamsByMinute(streams, null, hrMax, hrResting);

            String insight = insightService.getActivityInsight(activity, minuteAnalysis);

            // 🎯 Repassa hrMax e hrResting para o resumo da zona dominante
            String zonaDominante = activityService.calculateDominantZoneSummary(activityService.getHeartRateStream(streams), hrMax, hrResting);

            if (isValidInsight(insight)) {
                telegramClient.sendMessage("NOVO TREINO ANALISADO: " + activity.getName() + "\n\n" + insight);
                activityService.saveActivity(activity, minuteAnalysis, zonaDominante, insight);
                log.info("   [TELEGRAM] Análise do novo treino enviada.");

                // 🎯 GATILHO REATIVO: Se o treino for no sábado, dispara o planejamento da próxima semana
                try {
                    if (activity.getStartDateLocal() != null) {
                        String dateStr = activity.getStartDateLocal().replace("Z", "");
                        java.time.LocalDateTime startDate = java.time.LocalDateTime.parse(
                                dateStr, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);

                        if (startDate.getDayOfWeek() == java.time.DayOfWeek.SATURDAY) {
                            log.info("   [WEEKLY PLANNER] Treino de sábado detectado! Disparando planejamento para a próxima semana...");
                            weeklyPlannerService.gerenciarPlanejamentoSemanal();
                        }
                    }
                } catch (Exception e) {
                    log.error("   [WEEKLY PLANNER] Erro ao disparar planejamento semanal automático: {}", e.getMessage(), e);
                }

            } else {
                log.warn("   [GEMINI] Falha temporária. Atividade salva sem insight.");
                activityService.saveActivity(activity, minuteAnalysis, zonaDominante, null);
            }
        } catch (Exception e) {
            log.error("Erro ao processar e enviar atividade {}: {}", activity.getId(), e.getMessage(), e);
        }
    }

    private void enviarLembreteUltimoInsight() {
        activityRepository.findTopByOrderByStartDateDesc().ifPresentOrElse(activity -> {
            if (isValidInsight(activity.getGeminiInsight())) {
                String lembrete = "RELEMBRANDO ÚLTIMO TREINO: " + activity.getName() + "\n\n" + activity.getGeminiInsight();
                telegramClient.sendMessage(lembrete);
                log.info("   [TELEGRAM] Fallback executado: Último insight reenviado para o Telegram.");
            } else {
                log.info("   [FALLBACK] A última atividade no banco não possui um insight válido para reenvio.");
            }
        }, () -> log.warn("   [FALLBACK] Nenhum treino encontrado no banco de dados para reenvio."));
    }

    private boolean isValidInsight(String insight) {
        return insight != null && !insight.isEmpty() && !insight.startsWith("Erro");
    }

    private java.time.LocalDateTime parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return java.time.LocalDateTime.MIN;
        }
        try {
            String cleanDate = dateStr.replace("Z", "");
            return java.time.LocalDateTime.parse(cleanDate, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            log.warn("[SYNC] Falha ao fazer parse da data da atividade: {}", dateStr);
            return java.time.LocalDateTime.MIN;
        }
    }

    private boolean isActivityRecent(String dateStr, int maxDays) {
        java.time.LocalDateTime activityDate = parseDate(dateStr);
        if (activityDate.equals(java.time.LocalDateTime.MIN)) {
            return false;
        }
        java.time.LocalDateTime cutoffDate = java.time.LocalDateTime.now().minusDays(maxDays);
        return activityDate.isAfter(cutoffDate);
    }

    private boolean renovarToken() {
        try {
            TokenResponse novoToken = authService.refreshToken(refreshToken);
            this.accessToken = novoToken.getAccessToken();
            log.info("Token renovado com sucesso.");
            return true;
        } catch (Exception e) {
            log.error("ERRO CRÍTICO na renovação do token: " + e.getMessage());
            return false;
        }
    }
}