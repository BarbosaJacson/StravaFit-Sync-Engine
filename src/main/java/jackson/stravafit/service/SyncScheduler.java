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

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
    private static final ZoneId ZONE_SP = ZoneId.of("America/Sao_Paulo");
    @Value("${strava.auto-start:false}")
    private boolean autoStart;
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
        List<UserEntity> usuarios = userRepository.findAll().stream()
                .filter(u -> u.getStravaAccessToken() != null && !u.getStravaAccessToken().isBlank())
                .toList();

        if (usuarios.isEmpty()) {
            log.warn("[MOTOR] Nenhum usuário com token Strava configurado no banco.");
            return;
        }

        for (UserEntity user : usuarios) {
            sincronizarUsuario(user);
        }
    }

    @Async
    public void executarSincronizacaoPorAtleta(Long stravaAthleteId) {
        log.info("\n=== [MOTOR] Sincronização por Webhook para atleta Strava ID: {} ===", stravaAthleteId);
        userRepository.findByStravaAthleteId(stravaAthleteId)
                .ifPresentOrElse(
                        this::sincronizarUsuario,
                        () -> log.warn("[WEBHOOK] Nenhum usuário cadastrado com stravaAthleteId: {}", stravaAthleteId)
                );
    }

    private void sincronizarUsuario(UserEntity user) {
        try {
            ActivityService.ActivityPageResponse response =
                    activityService.getActivitiesWithHeartRate(user.getStravaAccessToken(), 1);

            if (response.activities().isEmpty()) {
                log.warn("   [STRAVA] Nenhuma atividade compatível encontrada para {}.", user.getName());
                enviarLembreteUltimoInsight(user);
                return;
            }

            List<StravaActivity> atividadesOrdenadas = response.activities().stream()
                    .sorted(Comparator.comparing(
                            act -> parseDate(act.getStartDateLocal()),
                            Comparator.nullsLast(Comparator.reverseOrder())
                    ))
                    .toList();

            Optional<StravaActivity> treinoPendente = atividadesOrdenadas.stream()
                    .filter(activity -> {
                        Optional<ActivityEntity> entityOpt = activityRepository.findById(activity.getId());
                        if (entityOpt.isEmpty()) {
                            return true;
                        }
                        ActivityEntity entity = entityOpt.get();
                        boolean recente = isActivityRecent(activity.getStartDateLocal(), 7);
                        return recente && !isValidInsight(entity.getGeminiInsight());
                    })
                    .findFirst();

            if (treinoPendente.isPresent()) {
                StravaActivity activity = treinoPendente.get();
                log.info("-> NOVO TREINO DETECTADO PARA ANÁLISE: {} (ID: {}) | Atleta: {}", activity.getName(), activity.getId(), user.getName());
                processarEEnviar(user, activity);
            } else {
                log.info("-> Nenhum treino novo pendente para {}. Verificando último insight...", user.getName());
                enviarLembreteUltimoInsight(user);
            }

        } catch (HttpClientErrorException.Unauthorized e) {
            if (renovarToken(user)) {
                sincronizarUsuario(user);
            } else {
                log.error("ERRO CRÍTICO: Falha na renovação do token para {}. Sincronização abortada.", user.getName());
            }
        } catch (Exception e) {
            log.error("ERRO NA SINCRONIZAÇÃO de {}: {}", user.getName(), e.getMessage());
        }
    }

    private void processarEEnviar(UserEntity user, StravaActivity activity) {
        try {
            Integer hrMax = user.getHrMax();
            Integer hrResting = user.getHrResting();
            String hrZoneSource = user.getHrZoneSource();
            Integer lv1 = user.getZ2MaxBpm();
            Integer lv2 = user.getZ4MaxBpm();

            List<StravaActivity.ActivityStream> streams =
                    activityService.getActivityStreams(user.getStravaAccessToken(), activity.getId());

            List<StravaActivity.MinuteAnalysis> minuteAnalysis =
                    activityService.aggregateStreamsByMinute(streams, null, hrZoneSource, lv1, lv2, hrMax, hrResting);

            String insight = insightService.getActivityInsight(activity, minuteAnalysis);

            String zonaDominante = activityService.calculateDominantZoneSummary(
                    activityService.getHeartRateStream(streams), hrZoneSource, lv1, lv2, hrMax, hrResting);

            if (isValidInsight(insight)) {
                telegramClient.sendMessage("NOVO TREINO ANALISADO: " + activity.getName() + "\n\n" + insight);
                activityService.saveActivity(user, activity, minuteAnalysis, zonaDominante, insight);
                log.info("   [TELEGRAM] Análise do novo treino enviada.");

                try {
                    if (activity.getStartDateLocal() != null) {
                        String dateStr = activity.getStartDateLocal().replace("Z", "");
                        LocalDateTime startDate = LocalDateTime.parse(
                                dateStr, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);

                        if (startDate.getDayOfWeek() == java.time.DayOfWeek.SATURDAY) {
                            log.info("   [WEEKLY PLANNER] Treino de sábado detectado para {}! Disparando planejamento da próxima semana...", user.getName());
                            weeklyPlannerService.gerenciarPlanejamentoSemanalParaUsuario(user);
                        }
                    }
                } catch (Exception e) {
                    log.error("   [WEEKLY PLANNER] Erro ao disparar planejamento semanal automático: {}", e.getMessage(), e);
                }

            } else {
                log.warn("   [GEMINI] Falha temporária. Atividade salva sem insight.");
                activityService.saveActivity(user, activity, minuteAnalysis, zonaDominante, null);
            }
        } catch (Exception e) {
            log.error("Erro ao processar e enviar atividade {}: {}", activity.getId(), e.getMessage(), e);
        }
    }

    private void enviarLembreteUltimoInsight(UserEntity user) {
        activityRepository.findTopByUserIdOrderByStartDateDesc(user.getId()).ifPresentOrElse(activity -> {
            if (isValidInsight(activity.getGeminiInsight())) {
                String lembrete = "RELEMBRANDO ÚLTIMO TREINO: " + activity.getName() + "\n\n" + activity.getGeminiInsight();
                telegramClient.sendMessage(lembrete);
                log.info("   [TELEGRAM] Fallback executado para {}: Último insight reenviado.", user.getName());
            } else {
                log.info("   [FALLBACK] A última atividade de {} não possui um insight válido para reenvio.", user.getName());
            }
        }, () -> log.warn("   [FALLBACK] Nenhum treino encontrado no banco para {}.", user.getName()));
    }
    private boolean isActivityRecent(String dateStr, int maxDays) {
        java.time.LocalDateTime activityDate = parseDate(dateStr);
        if (activityDate.equals(java.time.LocalDateTime.MIN)) {
            return false;
        }
        java.time.LocalDateTime cutoffDate = java.time.LocalDateTime.now().minusDays(maxDays);
        return activityDate.isAfter(cutoffDate);
    }

    private boolean renovarToken(UserEntity user) {
        try {
            TokenResponse novoToken = authService.refreshToken(user.getStravaRefreshToken());
            user.setStravaAccessToken(novoToken.getAccessToken());
            if (novoToken.getRefreshToken() != null && !novoToken.getRefreshToken().isBlank()) {
                user.setStravaRefreshToken(novoToken.getRefreshToken());
            }
            user.setStravaTokenExpiresAt(novoToken.getExpiresAt());
            userRepository.save(user);
            log.info("Token Strava renovado e salvo no MySQL para {}.", user.getName());
            return true;
        } catch (Exception e) {
            log.error("ERRO CRÍTICO na renovação do token de {}: {}", user.getName(), e.getMessage());
            return false;
        }
    }
    private boolean isValidInsight(String insight) {
        return insight != null && !insight.isEmpty() && !insight.startsWith("Erro");
    }

    private LocalDateTime parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return LocalDateTime.MIN;
        }
        try {
            String normalized = dateStr.replace("Z", "");

            boolean hasOffset = normalized.contains("+") ||
                    (normalized.lastIndexOf("-") > 10);

            if (hasOffset) {
                return OffsetDateTime.parse(dateStr)
                        .atZoneSameInstant(ZONE_SP)
                        .toLocalDateTime();
            }
            return LocalDateTime.parse(normalized, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            log.warn("[SYNC-PARSER] Falha ao converter data '{}': {}", dateStr, e.getMessage());
            return LocalDateTime.MIN;
        }
    }
}