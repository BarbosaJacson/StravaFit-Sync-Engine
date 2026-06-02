package jackson.stravafit.service;

import jackson.stravafit.client.TelegramClient;
import jackson.stravafit.model.ActivityEntity;
import jackson.stravafit.model.StravaActivity;
import jackson.stravafit.model.TokenResponse;
import jackson.stravafit.repository.ActivityRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.web.client.HttpClientErrorException;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class SyncScheduler {

    private final ActivityService activityService;
    private final StravaAuthService authService;
    private final InsightService insightService;
    private final TelegramClient telegramClient;
    private final ActivityRepository activityRepository;
    
    private static final ZoneId ZONE_SP = ZoneId.of("America/Sao_Paulo");

    private volatile String accessToken; // Garante visibilidade entre múltiplas threads
    private final String refreshToken;

    @Value("${sync.on-startup:false}")
    private boolean syncOnStartup;

    // Cache em memória para evitar que duas threads processem a mesma atividade simultaneamente
    private final Set<Long> atividadesEmProcessamento = ConcurrentHashMap.newKeySet();

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

    /**
     * Dispara a sincronização ao iniciar, apenas se a propriedade sync.on-startup for true.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (syncOnStartup) {
            log.info("[STARTUP] Gatilho de inicialização ativado.");
            scheduledSync();
        }
    }

    /**
     * Método mantido para chamadas via Webhook ou DebugController.
     */
    @Transactional
    public void scheduledSync() {
        log.info("=== [SINCRONIZAÇÃO DISPARADA] ===");
        executarSincronizacao(this.accessToken);
    }

    @Transactional
    public boolean executarSincronizacao(String tokenParaUsar) {
        boolean geminiDisponivel = true;
        try {
            ActivityService.ActivityPageResponse response = activityService.getActivitiesWithHeartRate(tokenParaUsar, 1);
            if (response.activities().isEmpty()) {
                log.info("[STRAVA] Nenhuma atividade compatível encontrada recentemente.");
                garantirEEnviarUltimoInsight(); // Tenta recuperar pendências mesmo sem treino novo
                return false;
            }

            LocalDate today = LocalDate.now(ZONE_SP);
            boolean treinoHojeDetectado = false;

            for (StravaActivity activity : response.activities()) {
                if (activityRepository.existsById(activity.getId())) {
                    continue; // Pula o que já está no banco para economizar API
                }

                // Impede que Webhook e Schedule processem a mesma atividade ao mesmo tempo
                if (!atividadesEmProcessamento.add(activity.getId())) {
                    log.warn("[SYNC] Atividade {} já está sendo processada por outra thread.", activity.getId());
                    continue;
                }

                try {
                    // Parse da data da atividade
                    String dateStr = activity.getStartDateLocal();
                    LocalDate activityDate = (dateStr.contains("Z") || dateStr.contains("+")) 
                            ? ZonedDateTime.parse(dateStr).withZoneSameInstant(ZONE_SP).toLocalDate() 
                            : LocalDate.parse(dateStr.substring(0, 10)); // Formato simplificado yyyy-MM-dd

                    if (activityDate.isEqual(today)) {
                        log.info("-> NOVO TREINO DETECTADO PARA O DIA: {}", activity.getName());
                        geminiDisponivel = processarEEnviar(tokenParaUsar, activity);
                        treinoHojeDetectado = true;
                        break;// Se processamos o treino de hoje, paramos por aqui.
                } else {
                        // CARGA HISTÓRICA: Apenas persiste sem disparar notificações
                        log.info("-> Carregando atividade histórica para o banco: {} ({})", activity.getName(), activityDate);
                        persistirSemNotificar(tokenParaUsar, activity);
                    }
                } catch (Exception e) {
                    log.error("[ERRO] Falha ao processar atividade individual {}: {}", activity.getId(), e.getMessage());
                    // Se houver erro de pagamento (402) ou token (401), interrompemos para evitar loop de erros
                    if (e.getMessage().contains("402") || e.getMessage().contains("401")) {
                        log.error("[CRÍTICO] Falha de permissão no Strava. Interrompendo ciclo.");
                        return false;
                    }
                } finally {
                    atividadesEmProcessamento.remove(activity.getId());
                }
            }

            // Se terminamos de olhar a lista e ninguém foi "hoje"
            if (!treinoHojeDetectado) {
                log.info("-> Nenhum treino detectado para hoje ({}). Enviando recomendação matinal.", today);
                enviarRecomendacaoPreTreino(today);
            }

            // REGRA DE OURO: Só forçamos a reanálise se NÃO detectamos um treino novo agora.
            // Se 'treinoHojeDetectado' for true, o insight já foi enviado no 'processarEEnviar'.
            if (geminiDisponivel && !treinoHojeDetectado) {
                log.info("[RECOVERY] Nenhum treino novo hoje. Verificando pendências ou atualizando última análise...");
                garantirEEnviarUltimoInsight();
            }
            log.info("[SYNC] Ciclo de sincronização finalizado com sucesso. Aguardando ociosidade.");
            return true;

        } catch (HttpClientErrorException.Unauthorized e) {
            if (renovarToken()) {
                return executarSincronizacao(this.accessToken);
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
            log.warn("[GEMINI] Falha temporária. Atividade salva para análise posterior.");
        }
        return success;
    }

    private void persistirSemNotificar(String token, StravaActivity activity) {
        try {
            DataProcessamento dados = buscarDadosCompletosAtividade(token, activity); // activity.getId()
            activityService.saveActivity(activity, dados.minuteAnalysis(), dados.zonaDominante(), null);
        } catch (Exception e) {
            log.error("Erro ao persistir atividade histórica {}: {}", activity.getId(), e.getMessage());
        }
    }

    private void enviarRecomendacaoPreTreino(LocalDate today) {
        telegramClient.sendMessage("BOM DIA! 🌅\nNão detectei treino hoje (" + today + ").\n\nSe for treinar mais tarde, registre no Strava para análise!");
        log.info("[TELEGRAM] Mensagem matinal enviada.");
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

    @Transactional
    public void garantirEEnviarUltimoInsight() {
        activityRepository.findTop10ByOrderByStartDateDesc().stream().findFirst().ifPresent(activity -> { // activity is ActivityEntity
            log.info("[GEMINI] Forçando nova análise técnica para o último treino: {}", activity.getName());
            
            // Ignoramos o insight antigo e pedimos um novo para garantir a leitura dos studySettings atuais
            String novoInsight = insightService.getActivityInsightFromEntity(activity);
            
            if (isValidInsight(novoInsight)) {
                activity.setGeminiInsight(novoInsight);
                // saveAndFlush garante que o insight seja gravado antes do container entrar em ociosidade
                activityRepository.saveAndFlush(activity);
                telegramClient.sendMessage("ANÁLISE ATUALIZADA DO ÚLTIMO TREINO: " + activity.getName() + "\n\n" + novoInsight);
                log.info("[TELEGRAM] Nova análise enviada com sucesso.");
            } else {
                log.warn("[GEMINI] Falha ao gerar nova análise. Mantendo registro anterior.");
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

    private synchronized boolean renovarToken() { // Sincronizado para evitar múltiplas renovações ao mesmo tempo
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
}
