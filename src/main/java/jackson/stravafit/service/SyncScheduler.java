package jackson.stravafit.service;

import jackson.stravafit.client.TelegramClient;
import jackson.stravafit.model.ActivityEntity;
import jackson.stravafit.model.StravaActivity;
import jackson.stravafit.model.TokenResponse;
import jackson.stravafit.repository.ActivityRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

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
        System.out.println("   [STARTUP] Iniciando motor e verificando pendências...");
        executarSincronizacao(this.accessToken);
    }

    // AGENDAMENTO ÚNICO: Ter, Qui, Sab às 07:00 (Relatório Consolidado)
    @Scheduled(cron = "0 0 7 * * TUE,THU,SAT")
    public void scheduledSync() {
        System.out.println("=== [RELATÓRIO MATINAL 07:00] ===");
        executarSincronizacao(this.accessToken);
    }

    // NOVO AGENDAMENTO: Ter, Qui, Sab às 05:05 (Checagem de Sono e Plano Pré-Treino)
    @Scheduled(cron = "0 5 5 * * TUE,THU,SAT")
    public void scheduledSleepCheck() {
        System.out.println("=== [AGENDAMENTO 05:05] Checagem pré-treino disparada. ===");
    }

    // TAREFA DE RECUPERAÇÃO: Tenta "curar" atividades sem insight a cada 1 hora
    @Scheduled(cron = "0 0 * * * *")
    public void recoveryTask() {
        System.out.println("   [RECOVERY] Verificando se há treinos pendentes de análise...");
        garantirEEnviarUltimoInsight();
    }

    private boolean executarSincronizacao(String tokenParaUsar) {
        boolean geminiDisponivel = true;
        try {
            ActivityService.ActivityPageResponse response = activityService.getActivitiesWithHeartRate(tokenParaUsar, 1);
            if (response.activities().isEmpty()) {
                System.out.println("   [STRAVA] Nenhuma atividade compatível encontrada recentemente.");
                garantirEEnviarUltimoInsight(); // Tenta recuperar pendências mesmo sem treino novo
                return false;
            }

            LocalDate today = LocalDate.now();
            boolean treinoHojeDetectado = false;

            for (StravaActivity activity : response.activities()) {
                if (activityRepository.existsById(activity.getId())) {
                    continue; // Pula o que já está no banco para economizar API
                }

                // Parse da data da atividade
                String dateStr = activity.getStartDateLocal();
                LocalDate activityDate = (dateStr.contains("Z") || dateStr.contains("+")) 
                        ? ZonedDateTime.parse(dateStr).toLocalDate() 
                        : LocalDate.parse(dateStr.substring(0, 10));

                if (activityDate.isEqual(today)) {
                    System.out.println("-> NOVO TREINO DETECTADO PARA O DIA: " + activity.getName());
                    geminiDisponivel = processarEEnviar(tokenParaUsar, activity);
                    treinoHojeDetectado = true;
                } else {
                    // CARGA HISTÓRICA: Se não é hoje e não está no banco, apenas persiste os dados
                    // Isso vai carregar suas 59 atividades gradualmente sem disparar 59 notificações
                    System.out.println("-> Carregando atividade histórica para o banco: " + activity.getName() + " (" + activityDate + ")");
                    persistirSemNotificar(tokenParaUsar, activity);
                }
            }

            // Se terminamos de olhar a lista e ninguém foi "hoje"
            if (!treinoHojeDetectado) {
                System.out.println("-> Nenhum treino detectado para hoje (" + today + "). Enviando recomendação matinal.");
                enviarRecomendacaoPreTreino(today);
            }

            if (geminiDisponivel) {
                garantirEEnviarUltimoInsight();
            }
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

    private boolean processarEEnviar(String token, StravaActivity activity) {
        DataProcessamento dados = buscarDadosCompletosAtividade(token, activity); // activity.getId()
        List<StravaActivity.MinuteAnalysis> minuteAnalysis = dados.minuteAnalysis();
        String zonaDominante = dados.zonaDominante();
        String insight = insightService.getActivityInsight(activity, minuteAnalysis);
        boolean success = isValidInsight(insight);
        
        if (success) {
            telegramClient.sendMessage("NOVO TREINO ANALISADO: " + activity.getName() + "\n\n" + insight);
            activityService.saveActivity(activity, minuteAnalysis, zonaDominante, insight);
        } else {
            activityService.saveActivity(activity, minuteAnalysis, zonaDominante, null);
            System.err.println("   [GEMINI] Falha temporária. Atividade salva para análise posterior.");
        }
        return success;
    }

    private void persistirSemNotificar(String token, StravaActivity activity) {
        try {
            DataProcessamento dados = buscarDadosCompletosAtividade(token, activity); // activity.getId()
            activityService.saveActivity(activity, dados.minuteAnalysis(), dados.zonaDominante(), null);
        } catch (Exception e) {
            System.err.println("Erro ao persistir atividade histórica " + activity.getId() + ": " + e.getMessage());
        }
    }

    private void enviarRecomendacaoPreTreino(LocalDate today) {
        telegramClient.sendMessage("BOM DIA! 🌅\nNão detectei treino hoje (" + today + ").\n\nSe for treinar mais tarde, registre no Strava para análise!");
        System.out.println("   [TELEGRAM] Mensagem matinal enviada.");
    }

    /**
     * Método auxiliar para centralizar a busca e agregação de dados da atividade.
     */
    private DataProcessamento buscarDadosCompletosAtividade(String token, StravaActivity activity) {
        List<StravaActivity.HeartRateZone> zones = activityService.getActivityZones(token, activity.getId());
        List<StravaActivity.ActivityStream> streams = activityService.getActivityStreams(token, activity.getId());
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
        activityRepository.findTop10ByOrderByStartDateDesc().stream().findFirst().ifPresent(activity -> { // activity is ActivityEntity
            System.out.println("   [GEMINI] Forçando nova análise técnica para o último treino: " + activity.getName());
            
            // Ignoramos o insight antigo e pedimos um novo para garantir a leitura dos studySettings atuais
            String novoInsight = insightService.getActivityInsightFromEntity(activity);
            
            if (isValidInsight(novoInsight)) {
                activity.setGeminiInsight(novoInsight);
                activityRepository.save(activity);
                telegramClient.sendMessage("ANÁLISE ATUALIZADA DO ÚLTIMO TREINO: " + activity.getName() + "\n\n" + novoInsight);
                System.out.println("   [TELEGRAM] Nova análise enviada com sucesso.");
            } else {
                System.out.println("   [GEMINI] Falha ao gerar nova análise. Mantendo registro anterior.");
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
            System.out.println("Token renovado.");
            return true;
        } catch (Exception e) {
            System.err.println("ERRO CRÍTICO na renovação do token: " + e.getMessage());
            return false;
        }
    }
}
