package jackson.stravafit.service;

import jackson.stravafit.client.TelegramClient;
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

import java.util.List;

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
    // Removido: ConfigurableApplicationContext context

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
                return; // Retorno vazio
            }

            StravaActivity activity = response.activities().get(0);

            if (activityRepository.existsById(activity.getId())) {
                log.info("-> Treino do dia (" + activity.getName() + ") já analisado. Acionando fallback de reenvio...");
                enviarLembreteUltimoInsight();
                return; // Retorno vazio
            }

            log.info("-> NOVO TREINO DETECTADO: " + activity.getName());
            processarEEnviar(this.accessToken, activity);
            return; // Opcional ou remova

        } catch (HttpClientErrorException.Unauthorized e) {
            if (renovarToken()) {
                executarSincronizacao(); // Chamada recursiva assíncrona
                return;
            } else {
                log.error("ERRO CRÍTICO: Falha na renovação do token. Sincronização abortada.");
                return;
            }
        } catch (Exception e) {
            log.error("ERRO NA SINCRONIZAÇÃO: " + e.getMessage());
            return;
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

    // Removido por completo: método encerrarAplicacaoGraciosamente()

    private boolean isValidInsight(String insight) {
        return insight != null && !insight.isEmpty() && !insight.startsWith("Erro");
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

