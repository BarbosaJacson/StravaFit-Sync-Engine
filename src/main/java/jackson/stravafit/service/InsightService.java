package jackson.stravafit.service;

import jackson.stravafit.client.GeminiClient;
import jackson.stravafit.model.ActivityEntity;
import jackson.stravafit.model.ActivitySummaryEntity;
import jackson.stravafit.model.StravaActivity;
import jackson.stravafit.model.WorkoutPrescriptionEntity;
import jackson.stravafit.repository.ActivityRepository;
import jackson.stravafit.repository.ActivitySummaryRepository;
import jackson.stravafit.repository.WorkoutPrescriptionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class InsightService {

    private final GeminiClient geminiClient;
    private final ActivityRepository activityRepository;
    private final WorkoutPrescriptionRepository workoutPrescriptionRepository;
    private final ActivityService activityService;
    private final ActivitySummaryRepository activitySummaryRepository;
    private final KnowledgeService knowledgeService;
    private final InsightService self;
    private final String nomeAtleta;

    private static final ZoneId ZONE_SP = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter BRAZIL_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter NEXT_WORKOUT_FORMATTER = DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy");

    public InsightService(GeminiClient geminiClient,
                          ActivityRepository activityRepository,
                          WorkoutPrescriptionRepository workoutPrescriptionRepository,
                          ActivitySummaryRepository activitySummaryRepository,
                          ActivityService activityService,
                          @Lazy InsightService self,
                          KnowledgeService knowledgeService,
                          @Value("${atleta.nome:Jacson}") String nomeAtleta) {
        this.geminiClient = geminiClient;
        this.activityRepository = activityRepository;
        this.workoutPrescriptionRepository = workoutPrescriptionRepository;
        this.activitySummaryRepository = activitySummaryRepository;
        this.activityService = activityService;
        this.self = self;
        this.knowledgeService = knowledgeService;
        this.nomeAtleta = nomeAtleta;
    }

    public record ClassificacaoResultado(String tipoEstimulo, int janelasInstaveis) {}

    @Transactional
    public String getActivityInsight(StravaActivity activity, List<StravaActivity.MinuteAnalysis> analysis) {
        return generateInsight(
                activity.getId(),
                activity.getName(),
                activity.getDistance() != null ? activity.getDistance() / 1000.0 : 0.0,
                activity.getStartDateLocal(),
                activity.getAverageSpeed() != null ? activity.getAverageSpeed() * 3.6 : 0.0,
                analysis);
    }

    private String generateInsight(Long activityId, String name, Double distance, String dateStr, Double averageSpeed, List<StravaActivity.MinuteAnalysis> analysis) {
        // 1. Primeiro calculamos as métricas essenciais e as datas
        ZonedDateTime activityDate = parseToZonedDateTime(dateStr);
        String proximoTreinoData = calcularProximaDataTreino(activityDate);
        SessionMetrics metrics = calcularMetricasSessao(analysis, distance, averageSpeed);
        Optional<WorkoutPrescriptionEntity> prescricaoAnterior = workoutPrescriptionRepository.findTopByScheduledDateOrderByCreatedAtDesc(activityDate.toLocalDate());

        // 2. Classificamos o estímulo para saber qual cenário a sessão atual pertence
        ClassificacaoResultado resultado = classificarEstimuloFisiologico(analysis, metrics.stdDev(), metrics.fcMax(), metrics.fcMedia());
        String tipoEstimuloReal = resultado.tipoEstimulo();
        int janelasInstaveis = resultado.janelasInstaveis();
        boolean ehTiro = tipoEstimuloReal.contains("TIROS");

        // 3. Criamos a entidade da sessão atual em memória para garantir inclusão imediata no histórico do prompt
        ActivitySummaryEntity treinoAtualVirtual = new ActivitySummaryEntity();
        treinoAtualVirtual.setActivityId(activityId);
        treinoAtualVirtual.setStartDate(activityDate.toLocalDateTime());
        treinoAtualVirtual.setDistanceKm(metrics.safeDistance());
        treinoAtualVirtual.setTotalTimeMinutes(metrics.duracao());
        treinoAtualVirtual.setAverageHeartRate(metrics.fcMedia());
        treinoAtualVirtual.setEfficiencyIndex(metrics.efficiencyIndex());

        // 4. Buscamos os históricos existentes no MySQL por Cenário
        List<ActivitySummaryEntity> listaTirosOriginal = activitySummaryRepository
                .findTop10ByDetectedScenarioOrderByStartDateDesc(2);

        List<ActivitySummaryEntity> listaCenario1Original = activitySummaryRepository
                .findTop10ByDetectedScenarioOrderByStartDateDesc(1);

        // 5. Injetamos a atividade atual na memória limpando duplicatas de data ou ID
        LocalDate dataHoje = activityDate.toLocalDate();

        List<ActivitySummaryEntity> listaTiros = listaTirosOriginal.stream()
                .filter(a -> a.getStartDate() == null || !a.getStartDate().toLocalDate().equals(dataHoje))
                .filter(a -> a.getActivityId() == null || !a.getActivityId().equals(activityId))
                .collect(Collectors.toList());

        List<ActivitySummaryEntity> listaCenario1 = listaCenario1Original.stream()
                .filter(a -> a.getStartDate() == null || !a.getStartDate().toLocalDate().equals(dataHoje))
                .filter(a -> a.getActivityId() == null || !a.getActivityId().equals(activityId))
                .collect(Collectors.toList());

        // Adiciona a sessão virtual única no topo da lista correspondente
        if (ehTiro) {
            listaTiros.add(0, treinoAtualVirtual);
            listaTiros.sort(Comparator.comparing(ActivitySummaryEntity::getStartDate).reversed());
        } else {
            listaCenario1.add(0, treinoAtualVirtual);
            listaCenario1.sort(Comparator.comparing(ActivitySummaryEntity::getStartDate).reversed());
        }

        // 6. Separação precisa por dia da semana na memória
        List<ActivitySummaryEntity> listaTercas = listaCenario1.stream()
                .filter(a -> a.getStartDate().getDayOfWeek() == DayOfWeek.TUESDAY)
                .toList();

        List<ActivitySummaryEntity> listaSabados = listaCenario1.stream()
                .filter(a -> a.getStartDate().getDayOfWeek() == DayOfWeek.SATURDAY)
                .toList();

        // 7. Cálculo das médias de eficiência incluindo o treino de hoje
        double mediaEficienciaZ2Curto = calcularMediaEficienciaDaLista(listaTercas, 5);
        double mediaEficienciaZ2Longo = calcularMediaEficienciaDaLista(listaSabados, 5);
        double mediaEficienciaTiros = calcularMediaEficienciaDaLista(listaTiros, 5);

        // 8. Calculamos os níveis dinâmicos com base no histórico atualizado
        int nivelCenario1 = calcularNivelDinamicoCenario1(listaTercas, listaSabados, proximoTreinoData);
        int nivelCenario2 = calcularNivelDinamicoCenario2(listaTiros, mediaEficienciaTiros);

        // 9. Mapeamos o cenário e o nível detectado de forma dinâmica para a sessão atual
        int cenarioDetectado = ehTiro ? 2 : 1;
        int nivelDetectado = (cenarioDetectado == 2) ? nivelCenario2 : nivelCenario1;

        // 10. Formatamos os históricos em texto para o prompt
        String historicoTirosPrompt = formatarHistoricoParaPrompt(listaTiros, "Tiros de Quinta-Feira (Cenário 2, Nível " + nivelCenario2 + ")");
        String historicoTercasPrompt = formatarHistoricoParaPrompt(listaTercas, "Rodagem Curta de Terça-Feira (Cenário 1, Nível 1)");
        String historicoSabadosPrompt = formatarHistoricoParaPrompt(listaSabados, "Longão de Sábado (Cenário 1, Nível " + nivelCenario1 + ")");
        String historicosUnificados = historicoTirosPrompt + "\n" + historicoTercasPrompt + "\n" + historicoSabadosPrompt;

        // 🎯 10.1 LINHA DO TEMPO CRONOLÓGICA GLOBAL
        List<ActivitySummaryEntity> historicoGlobalFiltrado = new ArrayList<>();
        historicoGlobalFiltrado.addAll(listaTiros);
        historicoGlobalFiltrado.addAll(listaCenario1);

        historicoGlobalFiltrado.sort(Comparator.comparing(ActivitySummaryEntity::getStartDate).reversed());

        StringBuilder sbHistoricoGlobal = new StringBuilder();
        for (ActivitySummaryEntity act : historicoGlobalFiltrado.stream().limit(9).toList()) {
            String tipoTreino = (act.getDetectedScenario() != null && act.getDetectedScenario() == 2) ? "TIROS" : "Z2 / RODAGEM";
            double ef = (act.getEfficiencyIndex() != null) ? act.getEfficiencyIndex() : metrics.efficiencyIndex();
            double fc = (act.getAverageHeartRate() != null) ? act.getAverageHeartRate() : metrics.fcMedia();

            sbHistoricoGlobal.append(String.format("• Data: %s (%s) | Efficiency: %.3f | FC Méd: %.0f bpm\n",
                    act.getStartDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                    tipoTreino,
                    ef,
                    fc));

            // 🎯 Injeta o resumo textual do diagnóstico anterior se existir no MySQL
            if (act.getAiAnalysisSummary() != null && !act.getAiAnalysisSummary().isBlank()) {
                sbHistoricoGlobal.append("  [Diagnóstico Anterior]: ")
                        .append(act.getAiAnalysisSummary().replaceAll("\n", " "))
                        .append("\n");
            }
            sbHistoricoGlobal.append("\n");
        }

        String historicoPerformanceGlobal = sbHistoricoGlobal.toString();

        // 11. Passamos o prompt estruturado com todas as variáveis instanciadas
        String prompt = buildProfessionalPrompt(name, metrics, activityDate, proximoTreinoData, prescricaoAnterior.orElse(null),
                nivelCenario1, nivelCenario2, tipoEstimuloReal, janelasInstaveis, historicosUnificados,
                mediaEficienciaTiros, mediaEficienciaZ2Curto, mediaEficienciaZ2Longo, historicoPerformanceGlobal);

        String rawAiResponse = geminiClient.getInsight(prompt);
        String cleanResult = removeXmlBlock(rawAiResponse);

        // 12. Persiste os dados técnicos calculados oficialmente no MySQL
        self.persistirDadosTecnicos(activityId, activityDate, metrics, cleanResult, rawAiResponse, tipoEstimuloReal, cenarioDetectado, nivelDetectado);

        return sanitizeOutput(cleanResult);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistirDadosTecnicos(Long activityId, ZonedDateTime activityDate, SessionMetrics metrics,
                                       String cleanResult, String rawAiResponse,
                                       String tipoEstimuloReal, int cenarioDetectado, int nivelDetectado) {
        try {
            ActivitySummaryEntity summary = activitySummaryRepository.findById(activityId).orElse(new ActivitySummaryEntity());
            summary.setActivityId(activityId);
            summary.setStartDate(activityDate.toLocalDateTime());
            summary.setDistanceKm(metrics.safeDistance());
            summary.setTotalTimeMinutes(metrics.duracao());
            summary.setAverageHeartRate(metrics.fcMedia());
            summary.setMaxHeartRate((int) metrics.fcMax());
            summary.setDominantZone(metrics.zonaPredominante());
            summary.setEfficiencyIndex(metrics.efficiencyIndex());
            summary.setAiAnalysisSummary(sanitizeOutput(cleanResult));
            summary.setRealStimulusType(tipoEstimuloReal);
            summary.setDetectedScenario(cenarioDetectado);
            summary.setDetectedLevel(nivelDetectado);

            activitySummaryRepository.saveAndFlush(summary);
            log.info("[DB] Sumário de performance persistido com classificação para atividade: {}", activityId);

            self.extractAndSavePrescription(activityId, rawAiResponse);
        } catch (Exception e) {
            log.error("[DB] Falha ao persistir dados técnicos: {}", e.getMessage(), e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void extractAndSavePrescription(Long activityId, String rawAiResponse) {
        try {
            String xml = extractXmlBlock(rawAiResponse);
            if (xml == null) {
                log.warn("[PRESCRIPTION] Bloco XML <prescription_data> não encontrado na resposta da IA para a atividade {}", activityId);
                return;
            }

            String scheduledDateStr = extractTagValue(xml, "scheduled_date");
            if (scheduledDateStr == null) {
                log.error("[PRESCRIPTION] Tag <scheduled_date> não encontrada no bloco XML para a atividade {}", activityId);
                return;
            }

            LocalDate scheduledDate = LocalDate.parse(scheduledDateStr.replaceAll("[^0-9-]", ""));
            WorkoutPrescriptionEntity prescription = workoutPrescriptionRepository.findByActivityId(activityId).orElse(new WorkoutPrescriptionEntity());

            prescription.setActivityId(activityId);
            prescription.setScheduledDate(scheduledDate);
            prescription.setType(extractTagValue(xml, "type"));
            prescription.setDuration(extractTagValue(xml, "duration"));
            prescription.setIntensity(extractTagValue(xml, "intensity"));
            prescription.setFocus(extractTagValue(xml, "focus"));
            prescription.setMethod(extractTagValue(xml, "method"));
            prescription.setRawGeminiResponse(rawAiResponse);

            int targetScenario = (scheduledDate.getDayOfWeek() == DayOfWeek.THURSDAY) ? 2 : 1;

            int targetLevel;
            if (targetScenario == 2) {
                List<ActivitySummaryEntity> historicoTirosEntidades = activitySummaryRepository
                        .findTop10ByDetectedScenarioOrderByStartDateDesc(2);
                double mediaEficienciaTiros = calcularMediaEficienciaDaLista(historicoTirosEntidades, 5);
                targetLevel = calcularNivelDinamicoCenario2(historicoTirosEntidades, mediaEficienciaTiros);
            } else {
                List<ActivitySummaryEntity> listaCenario1 = activitySummaryRepository
                        .findTop10ByDetectedScenarioOrderByStartDateDesc(1);

                List<ActivitySummaryEntity> listaTercas = listaCenario1.stream()
                        .filter(a -> a.getStartDate().getDayOfWeek() == DayOfWeek.TUESDAY)
                        .toList();

                List<ActivitySummaryEntity> listaSabados = listaCenario1.stream()
                        .filter(a -> a.getStartDate().getDayOfWeek() == DayOfWeek.SATURDAY)
                        .toList();

                String proximoTreinoDataStr = scheduledDate.format(DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy"));
                targetLevel = calcularNivelDinamicoCenario1(listaTercas, listaSabados, proximoTreinoDataStr);
            }

            prescription.setTargetScenario(targetScenario);
            prescription.setTargetLevel(targetLevel);

            workoutPrescriptionRepository.saveAndFlush(prescription);
            log.info("[DB] Prescrição salva com sucesso para a data: {} | Cenário Alvo Prescrito: {}", prescription.getScheduledDate(), targetScenario);

        } catch (Exception e) {
            log.error("[PRESCRIPTION] Falha ao extrair ou salvar a prescrição: {}", e.getMessage(), e);
        }
    }

    private int calcularNivelDinamicoCenario1(List<ActivitySummaryEntity> listaTercas, List<ActivitySummaryEntity> listaSabados, String proximoTreinoData) {
        boolean proximoTreinoEhSabado = proximoTreinoData.toUpperCase().contains("SÁBADO") || proximoTreinoData.toUpperCase().contains("SATURDAY");

        if (proximoTreinoEhSabado) {
            if (listaSabados.isEmpty()) {
                return 2;
            }

            ActivitySummaryEntity ultimoSabado = listaSabados.get(0);
            double ultimaDistanciaKm = ultimoSabado.getDistanceKm() != null ? ultimoSabado.getDistanceKm() : 0.0;

            double mediaSabados = listaSabados.stream()
                    .limit(5)
                    .mapToDouble(ActivitySummaryEntity::getEfficiencyIndex)
                    .average()
                    .orElse(0.0);

            log.info("[PROGRESSÃO SÁBADO] Última distância realizada: {} km | Média de Eficiência: {}",
                    String.format("%.2f", ultimaDistanciaKm), String.format("%.3f", mediaSabados));

            if (ultimaDistanciaKm >= 11.5 && ultimaDistanciaKm < 13.5) {
                if (mediaSabados >= 1.08 && listaSabados.size() >= 4) {
                    log.info("[PROGRESSÃO SÁBADO] Promovido do Nível 2 para o Nível 3 (14km). Média: {} >= 1.08", String.format("%.3f", mediaSabados));
                    return 3;
                }
                log.info("[PROGRESSÃO SÁBADO] Mantido no Nível 2 (12km). Média de eficiência atual ({}) ainda não atingiu a meta de 1.08.", String.format("%.3f", mediaSabados));
                return 2;
            } else if (ultimaDistanciaKm >= 13.5 && ultimaDistanciaKm < 14.5) {
                if (mediaSabados >= 1.06 && listaSabados.size() >= 4) {
                    log.info("[PROGRESSÃO SÁBADO] Promovido do Nível 3 para o Nível 4 (15km). Média: {} >= 1.06", String.format("%.3f", mediaSabados));
                    return 4;
                }
                return 3;
            } else if (ultimaDistanciaKm >= 14.5 && ultimaDistanciaKm < 15.5) {
                if (mediaSabados >= 1.04 && listaSabados.size() >= 4) {
                    log.info("[PROGRESSÃO SÁBADO] Promovido do Nível 4 para o Nível 5 (16km). Média: {} >= 1.04", String.format("%.3f", mediaSabados));
                    return 5;
                }
                return 4;
            } else if (ultimaDistanciaKm >= 15.5) {
                log.info("[PROGRESSÃO SÁBADO] Atleta estabilizado no teto do ciclo (Nível 5 - 16km).");
                return 5;
            }

            return 2;
        } else {
            log.info("[PROGRESSÃO TERÇA] Retornando Nível 1 Fixo (Âncora de Manutenção Aeróbica).");
            return 1;
        }
    }

    private int calcularNivelDinamicoCenario2(List<ActivitySummaryEntity> historicoTiros, double mediaEficienciaTiros) {
        if (historicoTiros.isEmpty() || historicoTiros.size() < 5) {
            log.info("[PROGRESSÃO] Atleta mantido no Nível 1. Histórico insuficiente de tiros (Possui: {} de 5 necessários).", historicoTiros.size());
            return 1;
        }

        log.info("[PROGRESSÃO] Média de Eficiência Realizada (Últimos 5 Tiros): {}", String.format("%.3f", mediaEficienciaTiros));

        if (mediaEficienciaTiros >= 1.15) {
            log.info("[PROGRESSÃO CENÁRIO 2] Promovido/Mantido no Nível 5 (Teto de Densidade Máxima). Média: {} >= 1.15", String.format("%.3f", mediaEficienciaTiros));
            return 5;
        } else if (mediaEficienciaTiros >= 1.13) {
            log.info("[PROGRESSÃO CENÁRIO 2] Promovido para o Nível 4 (10 repetições). Média: {} >= 1.13", String.format("%.3f", mediaEficienciaTiros));
            return 4;
        } else if (mediaEficienciaTiros >= 1.10) {
            log.info("[PROGRESSÃO CENÁRIO 2] Promovido para o Nível 3 (8 repetições - 1min30s). Média: {} >= 1.10", String.format("%.3f", mediaEficienciaTiros));
            return 3;
        } else if (mediaEficienciaTiros >= 1.06) {
            log.info("[PROGRESSÃO CENÁRIO 2] Promovido para o Nível 2 (8 repetições - 1min00s). Média: {} >= 1.06", String.format("%.3f", mediaEficienciaTiros));
            return 2;
        }

        log.info("[PROGRESSÃO CENÁRIO 2] Mantido no Nível 1 (6 repetições). Média: {} < 1.06", String.format("%.3f", mediaEficienciaTiros));
        return 1;
    }

    private ClassificacaoResultado classificarEstimuloFisiologico(List<StravaActivity.MinuteAnalysis> analysis, double stdDevGlobal, double fcMax, double fcMedia) {
        if (analysis == null || analysis.size() < 5) {
            return new ClassificacaoResultado("NÃO IDENTIFICADO (DADOS INSUFICIENTES)", 0);
        }

        int contagemJanelasInstaveis = 0;
        int tamanhoJanela = 3;

        List<Double> frequencias = analysis.stream()
                .map(StravaActivity.MinuteAnalysis::getAverageHeartRate)
                .filter(Objects::nonNull)
                .toList();

        if (frequencias.size() < tamanhoJanela) {
            return new ClassificacaoResultado("CONTÍNUO / ESTÁVEL (RODAGEM OU LONGÃO)", 0);
        }

        for (int i = 0; i <= frequencias.size() - tamanhoJanela; i++) {
            List<Double> subLista = frequencias.subList(i, i + tamanhoJanela);

            double mediaJanela = subLista.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double varianciaJanela = subLista.stream()
                    .mapToDouble(fc -> Math.pow(fc - mediaJanela, 2))
                    .average()
                    .orElse(0.0);

            if (Math.sqrt(varianciaJanela) >= 8.0) {
                contagemJanelasInstaveis++;
            }
        }

        double amplitudeCardiaca = fcMax - fcMedia;

        log.info("[CLASSIFICADOR] Picos instáveis (>8bpm): {} | Amplitude (Max - Média): {} bpm | StdDev Global: {} bpm",
                contagemJanelasInstaveis,
                String.format("%.1f", amplitudeCardiaca),
                String.format("%.1f", stdDevGlobal));

        if (contagemJanelasInstaveis >= 6 || (amplitudeCardiaca >= 20.0 && contagemJanelasInstaveis >= 3)) {
            return new ClassificacaoResultado("INTENSO / INTERVALADO (TIROS)", contagemJanelasInstaveis);
        }

        return new ClassificacaoResultado("CONTÍNUO / ESTÁVEL (RODAGEM OU LONGÃO)", contagemJanelasInstaveis);
    }

    private String buildProfessionalPrompt(String name, SessionMetrics metrics, ZonedDateTime date, String proximoTreinoData,
                                           WorkoutPrescriptionEntity prescricaoAnterior,
                                           int nivelCenario1,
                                           int nivelCenario2,
                                           String tipoEstimuloReal,
                                           int janelasInstaveis,
                                           String historicosUnificados,
                                           double mediaEficienciaTiros,
                                           double mediaEficienciaZ2Curto,
                                           double mediaEficienciaZ2Longo,
                                           String historicoPerformanceGlobal) {
        String dataFormatada = date.format(BRAZIL_FORMATTER);
        String scientificContext = knowledgeService.getScientificContext();
        List<ActivityEntity> historico = activityRepository.findTop10ByOrderByStartDateDesc();

        int hrMax = activityService.getAthleteConfig().getHrMax();
        int hrRest = activityService.getAthleteConfig().getHrResting();
        boolean proximoEhSabado = proximoTreinoData.toUpperCase().contains("SÁBADO") || proximoTreinoData.toUpperCase().contains("SATURDAY");

        double histVo2Medio = historico.stream().mapToDouble(a -> 15.3 * ((a.getMaxHeartRate() != null ? a.getMaxHeartRate() : hrMax) / (double) hrRest)).average().orElse(0.0);
        double histFcMaxMedia = historico.stream().mapToDouble(a -> a.getMaxHeartRate() != null ? a.getMaxHeartRate() : 0).average().orElse(0.0);
        double histFcMediaGeral = historico.stream().mapToDouble(a -> a.getAverageHeartRate() != null ? a.getAverageHeartRate() : 0).average().orElse(0.0);
        double histPaceMedioSegundos = historico.stream().filter(a -> a.getDistanceKm() != null && a.getDistanceKm() > 0 && a.getTotalTimeMinutes() != null && a.getTotalTimeMinutes() > 0).mapToDouble(a -> (a.getTotalTimeMinutes() * 60.0) / a.getDistanceKm()).average().orElse(0.0);
        double histEfficiencyIndex = historico.stream().filter(a -> a.getDistanceKm() != null && a.getDistanceKm() > 0 && a.getAverageHeartRate() != null && a.getAverageHeartRate() > 0 && a.getTotalTimeMinutes() != null && a.getTotalTimeMinutes() > 0).mapToDouble(a -> (a.getDistanceKm() * 1000) / (a.getAverageHeartRate() * a.getTotalTimeMinutes())).average().orElse(0.0);

        StringBuilder sb = new StringBuilder();

        sb.append("VOCÊ É UM ANALISTA DE PERFORMANCE DE ELITE E FISIOLOGISTA DE CORRIDA DO PROJETO STRAVAFIT.\n");
        sb.append("SUA MISSÃO É ANALISAR O TREINO ATUAL, EMITIR UM DIAGNÓSTICO FISIOLÓGICO SEGUINDO A MATRIZ DE CONHECIMENTO, PRESCREVER A RECUPERAÇÃO TÉCNICA E GERAR A PRÓXIMA PRESCRIÇÃO.\n\n");

        sb.append("REGRA DE FORMATAÇÃO: GERE A RESPOSTA USANDO APENAS TEXTO PURO, TÍTULOS EM MAIÚSCULAS E QUEBRAS DE LINHA. É ESTRITAMENTE PROIBIDO O USO DE MARKDOWN.\n\n");

        sb.append("--- CLASSIFICAÇÃO FISIOLÓGICA REAL DA ATIVIDADE (CÁLCULO MATEMÁTICO DO SISTEMA) ---\n");
        sb.append("ATENÇÃO IA, NÃO TENTE ADIVINHAR O TIPO DE TREINO. O SISTEMA ANALISOU OS DADOS TEMPORAIS MINUTO A MINUTO E CONSOLIDOU O SEGUINTE FATO:\n");
        sb.append("- TIPO DE ESTÍMULO EXECUTADO HOJE: ").append(tipoEstimuloReal).append("\n");
        sb.append("- DESVIO PADRÃO DA FC DO TREINO: ").append(String.format("%.2f", metrics.stdDev())).append(" bpm\n");
        sb.append("- JANELAS MÓVEIS INSTÁVEIS (3m, Desvio >= 8.0 bpm): ").append(janelasInstaveis).append(" disparos identificados\n");
        sb.append("- INSTRUÇÃO DE ANÁLISE: Se o estímulo foi classificado como 'INTENSO / INTERVALADO (TIROS)', você deve obrigatoriamente acionar o 'CENÁRIO 2' para o diagnóstico e justificar utilizando o desvio padrão e o fato de ter encontrado ").append(janelasInstaveis).append(" janelas móveis com forte oscilação cardíaca. Se foi 'CONTÍNUO / ESTÁVEL', utilize obrigatoriamente o 'CENÁRIO 1'.\n\n");

        if (scientificContext != null && !scientificContext.isBlank()) {
            sb.append("--- BASE DE CONHECIMENTO CIENTÍFICO E DIRETRIZES DO MONGODB ---\n");
            sb.append(scientificContext).append("\n\n");
        }

        sb.append("--- DADOS DO TREINO ATUAL ---\n");
        sb.append("DADOS DO TREINO: ").append(name).append(" | ").append(String.format("%.1f km", metrics.safeDistance())).append(" | Pace Médio: ").append(metrics.paceFormatted()).append("\n");
        sb.append("DURACAO: ").append(metrics.duracao()).append(" min | FC Méd: ").append(String.format("%.0f", metrics.fcMedia())).append(" bpm | FC Max: ").append(String.format("%.0f", metrics.fcMax())).append(" bpm | Zona Pred: Z").append(metrics.zonaPredominante()).append(" | Efic: ").append(String.format("%.3f", metrics.efficiencyIndex())).append(" | VO2: ").append(String.format("%.1f", metrics.vo2MaxEstimado())).append("\n");
        sb.append("DESVIO PADRÃO DA FC: ").append(String.format("%.1f", metrics.stdDev())).append(" bpm | COMPORTAMENTO CARDÍACO: ").append(metrics.comportamento()).append("\n\n");

        sb.append("--- INSTRUÇÕES CRUCIAIS PARA A IA ---\n");
        sb.append("1. IDENTIFICAÇÃO DO CENÁRIO:\n");
        sb.append("   - Leia as regras de aplicabilidade do 'CENARIO 1' (Eficiência Metabólica) e 'CENARIO 2' (Intensidade - Tiros).\n");
        sb.append("   - Identifique qual cenário se aplica ao treino executado hoje. Use o título exato do cenário no campo '📌 Cenário Detectado'.\n\n");

        sb.append("2. DIAGNÓSTICO TÉCNICO FISIOLÓGICO (SEÇÃO 2.0):\n");
        sb.append("   - Identifique o nível atual do atleta no cenário mapeado (por exemplo, correlacione o volume/tempo com os níveis descritos no Cenário).\n");
        sb.append("   - Avalie o 'Efficiency Index' atual de ").append(String.format("%.3f", metrics.efficiencyIndex())).append(" com base na tabela de 'legendas_eficiencia' do cenário identificado.\n");
        sb.append("   - Use o texto e a argumentação dos 'diagnosticos_clinicos' do cenário identificado no MongoDB como base direta para redigir o diagnóstico técnico, citando as referências científicas do arquivo (ex: San-Millán & Brooks, Seiler ou Casanova et al.).\n\n");

        sb.append("--- NÍVEIS DE PROGRESSÃO ATUAIS DO ATLETA (CÁLCULO MATEMÁTICO MANDATÓRIO) ---\n");
        sb.append("ATENÇÃO IA: O sistema analisou o histórico no MySQL e determinou os seguintes níveis mandatórios:\n");
        sb.append("- Nível Atual no Cenário 1 (Rodagens/Longão): NÍVEL ").append(nivelCenario1).append("\n");
        sb.append("- Nível Atual no Cenário 2 (Tiros de Quinta): NÍVEL ").append(nivelCenario2).append("\n");
        sb.append("REGRA INVIOLÁVEL: Você está ESTRITAMENTE PROIBIDA de recalcular, alterar, promover ou rebaixar o nível determinado acima. Sua única tarefa é ler as regras do NÍVEL indicado e usá-las na Seção 6.0.\n\n");

        sb.append("3. NUTRIÇÃO E DESCANSO (SEÇÃO 5.0):\n");
        sb.append("   - Consulte os documentos de 'NUTRITION' e 'REST' do MongoDB.\n");
        sb.append("   - Se o treino de hoje foi de baixa intensidade (Z2), use estritamente as diretrizes de 'light_moderated_days'.\n");
        sb.append("   - Se o treino de hoje foi de alta intensidade (Tiros/HIIT), use estritamente as diretrizes de 'high_intensity_days' (incluindo o alerta crítico de evitar megadoses de antioxidantes sintéticos para preservar a adaptação hormética pela via NOX2) e os protocolos de regeneração do documento 'REST'.\n\n");

        sb.append("4. 📅 PRESCRIÇÃO STRAFIT PREDICT (SEÇÃO 6.0):\n");
        sb.append("   - Identifique o dia da semana correspondente ao próximo treino agendado em: ").append(proximoTreinoData.toUpperCase()).append("\n");
        sb.append("   - SE O PRÓXIMO TREINO FOR UMA QUINTA-FEIRA (THURSDAY):\n");
        sb.append("     * Você deve obrigatoriamente prescrever o 'CENÁRIO 2' (Tiros / VO2máx).\n");
        sb.append("     * DADO REAL DO SISTEMA: A média real do Efficiency Index do atleta nas últimas sessões de tiros é: ")
                .append(String.format("%.3f", mediaEficienciaTiros)).append("\n");
        sb.append("     * INSTRUÇÃO DE SELEÇÃO E PROGRESSÃO DINÂMICA (JSON CENÁRIO 2):\n");
        sb.append("       1. Acesse o NÍVEL ATUAL do atleta no JSON do 'CENÁRIO 2' (Nível ").append(nivelCenario2).append(").\n");
        sb.append("       2. SELEÇÃO DA VARIAÇÃO INTERNA: Dentro do Nível ").append(nivelCenario2).append(", analise a lista de 'variacoes_estimulo' (V1, V2, V3, V4) e escolha EXATAMENTE a variação cujo 'criterio_ia' contemple a média real do atleta (")
                .append(String.format("%.3f", mediaEficienciaTiros)).append("). Prescreva a quantidade exata de repetições, tempo_tiro e tempo_recuperacao indicados nessa variação.\n");
        sb.append("       3. AVALIAÇÃO DE PROMOÇÃO DE NÍVEL: Verifique as regras de 'gatilho_promocao' no JSON. Se o atleta sustentar a média real >= 'min_sustentada' (conforme o critério de sessões consecutivas no histórico), informe a promoção do atleta para o Nível subsequente no texto do diagnóstico.\n");
        sb.append("       4. Justifique explicitamente na Seção 6.0 o motivo técnico da escolha da Variação (V1, V2, V3 ou V4) com base na faixa do Efficiency Index de ").append(String.format("%.3f", mediaEficienciaTiros)).append(".\n\n");

        sb.append("   - SE O PRÓXIMO TREINO FOR UMA TERÇA (TUESDAY) OU SÁBADO (SATURDAY):\n");
        sb.append("     * Você deve obrigatoriamente prescrever o 'CENÁRIO 1' (Corrida Aeróbica Contínua / Eficiência Metabólica).\n\n");

        sb.append("     * DADOS REAIS DE CONTEXTO METABÓLICO DO ATLETA:\n");
        sb.append("       - Última Distância Realizada em Longão de Sábado: ").append(String.format("%.2f km", metrics.safeDistance())).append("\n");
        sb.append("       - Média Real de Eficiência nas RODAGENS DE TERÇA (Últimos 5 treinos): ").append(String.format("%.3f", mediaEficienciaZ2Curto)).append("\n");
        sb.append("       - Média Real de Eficiência nos LONGÕES DE SÁBADO (Últimos 5 treinos): ").append(String.format("%.3f", mediaEficienciaZ2Longo)).append("\n\n");

        sb.append("     * REGRA DE PRESCRIÇÃO MANDATÓRIA BASEADA NO DIA DA SEMANA:\n");
        if (proximoEhSabado) {
            sb.append("       - O próximo treino é SÁBADO (LONGÃO). Você deve obrigatoriamente prescrever o NÍVEL ").append(nivelCenario1).append(".\n");
            sb.append("       - Use a média específica dos longões (")
                    .append(String.format("%.3f", mediaEficienciaZ2Longo))
                    .append(") para analisar a proximidade do atleta com o gatilho de promoção do Nível ").append(nivelCenario1)
                    .append("\n       - Explique fisiologicamente ao atleta o seu estado atual dentro deste nível, confrontando seu Efficiency Index com o 'valor_estavel' exigido no JSON do MongoDB para este volume específico, reforçando que a progressão de distância é estritamente sequencial.\n");
        } else {
            sb.append("       - O próximo treino é TERÇA-FEIRA (RODAGEM CURTA). Você deve obrigatoriamente prescrever o NÍVEL ").append(nivelCenario1).append(".\n");
            sb.append("       - IMPORTANTE: As terças-feiras são âncoras de recuperação e controle de carga semanal. Por isso, o ideal é manter o treino no Nível 1 (7 a 10 km) para evitar fadiga crônica residual.\n");
            sb.append("       - Use a média específica das terças (")
                    .append(String.format("%.3f", mediaEficienciaZ2Curto))
                    .append(") para validar como a eficiência está mantida de forma consistente e estável acima da meta do MongoDB.\n");
        }

        sb.append("     * REGRA DE NÍVEL E VOLUME (CONSULTE O MONGO):\n");
        sb.append("       1. Vá até o JSON do 'CENÁRIO 1' no MongoDB, localize o NÍVEL ").append(nivelCenario1).append(".\n");
        sb.append("       2. Extraia e prescreva exatamente o 'volume_km', 'tempo_min_minutos' e 'tempo_max_minutos' definidos para o Nível ").append(nivelCenario1).append(" no JSON.\n\n");

        sb.append("     * DIFERENCIAÇÃO DO TOM DE PRESCRIÇÃO E ANÁLISE:\n");
        sb.append("       - SE FOR TERÇA-FEIRA: Trate o treino como uma 'Rodagem de Desenvolvimento/Manutenção'. Foque em consistência de ritmo e controle de intensidade.\n");
        sb.append("       - SE FOR SÁBADO: Trate o treino como o seu 'Longão do Microciclo' (foco em endurance). Adapte a abordagem psicológica para a sustentação do esforço prolongado e controle de fadiga (Pace Drift).\n");
        sb.append("       - COMPARE AS DUAS MÉDIAS: Explique fisiologicamente ao atleta por que a eficiência média dele de terça-feira (")
                .append(String.format("%.3f", mediaEficienciaZ2Curto))
                .append(") tende a ser mais alta do que a eficiência do longão de sábado (")
                .append(String.format("%.3f", mediaEficienciaZ2Longo))
                .append("), relacionando diretamente isso ao maior tempo total sob esforço e à fadiga acumulada gerada pela depleção gradual do glicogênio nos treinos longos.\n\n");

        sb.append("--- FORMATO DE SAÍDA OBRIGATÓRIO (NÃO USE MARKDOWN) ---\n");
        sb.append("🏃‍♂️ StravaFit IA - Análise de Eficiência Metabólica\n\n");
        sb.append(dataFormatada).append("\n");
        sb.append("📌 Cenário Detectado: [Título Exato do Cenário do MongoDB]\n\n");
        sb.append("⚡ Intensidade do Estímulo: [Mapear Intensidade baseada na Zona Predominante] | Estabilidade Fisiológica: ").append(String.format("%.1f", metrics.stdDev())).append(" bpm (Desvio Padrão)\n\n");
        sb.append("📊 Métricas: ").append(String.format("%.1f km", metrics.safeDistance())).append(" | ").append(metrics.duracao()).append(" min | FC Méd: ").append(String.format("%.0f", metrics.fcMedia())).append(" bpm | FC Max: ").append(String.format("%.0f", metrics.fcMax())).append(" bpm | Zona Pred: Z").append(metrics.zonaPredominante()).append(" | Efic: ").append(String.format("%.3f", metrics.efficiencyIndex())).append(" | VO2: ").append(String.format("%.1f", metrics.vo2MaxEstimado())).append(" | Pace: ").append(metrics.paceFormatted()).append("\n\n");

        sb.append("📊 Distribuição de Esforço por Zona Cardíaca:\n");

        java.util.Map<Integer, Integer> minBpms = new java.util.HashMap<>();
        java.util.Map<Integer, Integer> maxBpms = new java.util.HashMap<>();
        for (int bpm = hrRest; bpm <= hrMax; bpm++) {
            int z = activityService.calculateKarvonenZone(bpm);
            if (z > 0) {
                minBpms.putIfAbsent(z, bpm);
                maxBpms.put(z, bpm);
            }
        }
        Map<Integer, Double> zonePercentages = metrics.zonePercentages();
        for (int zona = 1; zona <= 5; zona++) {
            double pct = zonePercentages.getOrDefault(zona, 0.0);
            int bpmMin = minBpms.getOrDefault(zona, 0);
            int bpmMax = maxBpms.getOrDefault(zona, 0);

            String descricaoZona = switch (zona) {
                case 1 -> String.format("Z-1 (Ativa / Recuperação) [%d - %d bpm]", bpmMin, bpmMax);
                case 2 -> String.format("Z-2 (Aeróbica / FatMax) [%d - %d bpm]", bpmMin, bpmMax);
                case 3 -> String.format("Z-3 (Tempo / Ritmo) [%d - %d bpm]", bpmMin, bpmMax);
                case 4 -> String.format("Z-4 (Limiar Anaeróbico) [%d - %d bpm]", bpmMin, bpmMax);
                case 5 -> String.format("Z-5 (Capacidade Anaeróbica / Pico) [%d - %d bpm]", bpmMin, bpmMax);
                default -> "Zona " + zona;
            };
            sb.append(String.format("  - %s: %.1f%%\n", descricaoZona, pct));
        }
        sb.append("\n\n");

        sb.append("--- HISTÓRICO DE PERFORMANCE SEGMENTADO (ÚLTIMOS 5 RESULTADOS) ---\n");
        sb.append("Use estes dados reais do MySQL para avaliar a proximidade do atleta com o gatilho de promoção:\n");
        sb.append(historicosUnificados).append("\n");

        sb.append("📊 Histórico Médio (Últimos 10 treinos):\n");
        sb.append("- VO2 Máx Médio: ").append(String.format("%.1f", histVo2Medio)).append(" ml/kg/min\n");
        sb.append("- FC Máxima Média: ").append(String.format("%.0f", histFcMaxMedia)).append(" bpm\n");
        sb.append("- FC Média Geral: ").append(String.format("%.0f", histFcMediaGeral)).append(" bpm\n");
        sb.append("- Pace Médio: ").append(formatSecondsToPace(histPaceMedioSegundos)).append(" min/km\n");
        sb.append("- Eficiência Média: ").append(String.format("%.3f", histEfficiencyIndex)).append(" (m/bpm*min)\n\n");

        if (prescricaoAnterior != null) {
            sb.append("📋 Referência (Treino Anterior Prescrito):\n");
            sb.append("- Tipo Planejado: ").append(prescricaoAnterior.getType()).append("\n");
            sb.append("- Duração/Volume: ").append(prescricaoAnterior.getDuration()).append("\n");
            sb.append("- Intensidade Alvo: ").append(prescricaoAnterior.getIntensity()).append("\n");
            sb.append("- Foco Técnico: ").append(prescricaoAnterior.getFocus()).append("\n\n");
        }
        sb.append("1.0 - 📋 STATUS DO TREINO (CUMPRIMENTO DO PLANO):\n");
        sb.append("REGRA DE STATUS: Analise se o atleta cumpriu o volume (tempo/distância) e a intensidade (zona de FC) planejados no treino anterior.\n");
        sb.append("Comece a seção obrigatoriamente imprimindo uma destas três classificações em maiúsculas:\n");
        sb.append("- [STATUS: CUMPRIDO] (Se cumpriu volume e intensidade dentro de uma margem de 10%)\n");
        sb.append("- [STATUS: CUMPRIDO PARCIALMENTE] (Se errou o volume por mais de 10% mas manteve a intensidade correta, ou vice-versa)\n");
        sb.append("- [STATUS: NÃO CUMPRIDO] (Se errou severamente tanto a intensidade quanto o volume)\n");
        sb.append("[Após o status, escreva em texto corrido a justificativa técnica fisiológica do cumprimento ou desvio do plano]\n\n");

        sb.append("2.0 - 👨‍⚕️ DIAGNÓSTICO TÉCNICO FISIOLÓGICO PARA ").append(nomeAtleta).append(":\n");
        sb.append("[Determine e descreva a faixa de eficiência baseada no Efficiency Index (Ex: Eficiente, Excelente). Insira o texto técnico fisiológico embasando os processos celulares com base nos 'diagnosticos_clinicos' do cenário ativo no MongoDB, referenciando formalmente as pesquisas do arquivo]\n\n");

        sb.append("3.0 - 🫀 ANÁLISE DE RITMO E COMPORTAMENTO CARDÍACO (").append(nomeAtleta).append("):\n");
        sb.append("[Analise a economia de corrida e o comportamento de fadiga (Pace Drift) do treino atual]\n\n");

        sb.append("4.0 - 🎯 CONCLUSÃO E PRÓXIMO PASSO PARA ").append(nomeAtleta).append(":\n");
        sb.append("--- CONTEXTO HISTÓRICO DE LEITURA (ÚLTIMAS ATIVIDADES GLOBAIS) ---\n");
        sb.append(historicoPerformanceGlobal).append("\n");
        sb.append("--- INSTRUÇÃO DE ANÁLISE PARA A SEÇÃO 4.0 ---\n");
        sb.append("• Utilize o histórico cronológico de atividades e diagnósticos acima APENAS como base de conhecimento interna para avaliar a evolução ou involução do atleta.\n");
        sb.append("• NÃO imprima a lista de treinos acima na resposta final do Telegram.\n");
        sb.append("• Escreva em texto corrido um parecer técnico discricionário comparando o rendimento de HOJE com o histórico recente (mencionando a assimilação de carga, controle de fadiga e a interação entre Z2 e Tiros).\n");
        sb.append("• Encerre com uma mensagem encorajadora e alinhada à preparação para a Meia Maratona de 01/11/2026.\n\n");

        sb.append("5.0 - 🍽️ NUTRIÇÃO / DESCANSO 💤\n");
        sb.append("[Prescreva a estratégia detalhada de alimentação pré e pós treino com as opções sugeridas no MongoDB (incluindo as fontes alimentares específicas, o alerta de antioxidantes sintéticos na NOX2 se for tiros, e os tempos de repouso muscular de 4h e de transição/intervalos entre estímulos)]\n\n");

        sb.append("--- INSTRUÇÃO TÉCNICA DO SISTEMA ---\n");
        sb.append("Ao final do relatório, adicione OBRIGATORIAMENTE o bloco XML com os dados da prescrição criada:\n");
        sb.append("<prescription_data>\n");
        sb.append("  <scheduled_date>").append(parseNextWorkoutDate(proximoTreinoData).format(DateTimeFormatter.ISO_LOCAL_DATE)).append("</scheduled_date>\n");
        sb.append("  <type>[Tipo do treino]</type>\n");
        sb.append("  <duration>[Duração]</duration>\n");
        sb.append("  <intensity>[Intensidade]</intensity>\n");
        sb.append("  <focus>[Foco]</focus>\n");
        sb.append("  <method>[Método]</method>\n");
        sb.append("</prescription_data>\n");

        return sb.toString();
    }

    public record SessionMetrics(double fcMedia, double fcMax, int duracao, double stdDev, int zonaPredominante, double z2Percent, String comportamento, double fcMaxPercentage, double vo2MaxEstimado, double ganhoAlt, double efficiencyIndex, double safeDistance, String paceFormatted, Map<Integer, Double> zonePercentages) {}

    private SessionMetrics calcularMetricasSessao(List<StravaActivity.MinuteAnalysis> analysis, Double distance, Double averageSpeed) {
        double fcMedia = analysis.stream().map(StravaActivity.MinuteAnalysis::getAverageHeartRate).filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(0.0);
        double fcMax = analysis.stream().map(StravaActivity.MinuteAnalysis::getMaxHeartRate).filter(Objects::nonNull).mapToDouble(Double::doubleValue).max().orElse(0.0);
        int duracao = analysis.size();

        double variance = analysis.stream().map(StravaActivity.MinuteAnalysis::getAverageHeartRate).filter(Objects::nonNull).mapToDouble(m -> Math.pow(m - fcMedia, 2)).average().orElse(0.0);
        double stdDev = Math.sqrt(variance);

        int hrMax = activityService.getAthleteConfig().getHrMax();
        int hrRest = activityService.getAthleteConfig().getHrResting();

        List<Double> hrData = analysis.stream()
                .map(StravaActivity.MinuteAnalysis::getAverageHeartRate)
                .filter(Objects::nonNull)
                .toList();
        Map<Integer, Double> zonePercentages = activityService.calculateZonePercentages(hrData);

        int zonaPredominante = zonePercentages.entrySet().stream()
                .filter(entry -> entry.getKey() > 0)
                .max((entry1, entry2) -> {
                    int compare = entry1.getValue().compareTo(entry2.getValue());
                    if (compare == 0) {
                        return entry1.getKey().compareTo(entry2.getKey());
                    }
                    return compare;
                })
                .map(Map.Entry::getKey).orElse(0);

        double z2Percent = zonePercentages.getOrDefault(2, 0.0);

        double firstHalf = analysis.stream().limit(duracao / 2).map(StravaActivity.MinuteAnalysis::getAverageHeartRate).filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(fcMedia);
        double secondHalf = analysis.stream().skip(duracao / 2).map(StravaActivity.MinuteAnalysis::getAverageHeartRate).filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(fcMedia);
        String comportamento = (secondHalf > firstHalf * 1.05) ? "subindo gradualmente (drift)" : "predominantemente estável";

        double fcMaxPercentage = (fcMax / hrMax) * 100;
        double vo2MaxEstimado = 15.3 * (fcMax / (double) hrRest);

        DoubleSummaryStatistics elevStats = analysis.stream().map(StravaActivity.MinuteAnalysis::getAverageElevation).filter(Objects::nonNull).mapToDouble(Double::doubleValue).summaryStatistics();
        double ganhoAlt = elevStats.getMax() - elevStats.getMin();
        if (ganhoAlt < 0 || elevStats.getCount() == 0) ganhoAlt = 0.0;

        double safeDistance = distance != null ? distance : 0.0;
        double efficiencyIndex = (fcMedia > 0 && duracao > 0) ? (safeDistance * 1000.0) / (fcMedia * duracao) : 0.0;
        String paceFormatted = formatSpeedToPace(averageSpeed);

        return new SessionMetrics(fcMedia, fcMax, duracao, stdDev, zonaPredominante, z2Percent, comportamento, fcMaxPercentage, vo2MaxEstimado, ganhoAlt, efficiencyIndex, safeDistance, paceFormatted, zonePercentages);
    }

    private String extractXmlBlock(String text) {
        Pattern pattern = Pattern.compile("<prescription_data>(.*?)</prescription_data>", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(0);
        }
        return null;
    }

    private String extractTagValue(String xml, String tag) {
        Pattern pattern = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">");
        Matcher matcher = pattern.matcher(xml);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private String removeXmlBlock(String text) {
        if (text == null) return "";
        return text.replaceAll("(?s)<prescription_data>.*?</prescription_data>", "").trim();
    }

    private String sanitizeOutput(String text) {
        if (text == null) return "";
        return text.replaceAll("[*#`]", "").trim();
    }

    private String formatSpeedToPace(Double speedKmH) {
        if (speedKmH == null || speedKmH == 0) return "N/A";
        double totalSeconds = 3600 / speedKmH;
        long minutes = (long) (totalSeconds / 60);
        long seconds = (long) (totalSeconds % 60);
        return String.format("%d:%02d min/km", minutes, seconds);
    }

    private String formatSecondsToPace(double totalSeconds) {
        if (totalSeconds <= 0) return "N/A";
        long minutes = (long) (totalSeconds / 60);
        long seconds = (long) (totalSeconds % 60);
        return String.format("%d:%02d", minutes, seconds);
    }

    private String calcularProximaDataTreino(ZonedDateTime date) {
        LocalDate today = LocalDate.now(ZONE_SP);
        LocalDate baseDate = date.toLocalDate().isBefore(today) ? today : date.toLocalDate();
        LocalDate dataIteracao = baseDate.plusDays(1);
        Set<DayOfWeek> diasDeTreino = Set.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY);
        while (!diasDeTreino.contains(dataIteracao.getDayOfWeek())) {
            dataIteracao = dataIteracao.plusDays(1);
        }
        return dataIteracao.format(NEXT_WORKOUT_FORMATTER);
    }

    private LocalDate parseNextWorkoutDate(String proximoTreinoData) {
        return LocalDate.parse(proximoTreinoData.split(", ")[1], DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    private ZonedDateTime parseToZonedDateTime(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return ZonedDateTime.now(ZONE_SP);
        try {
            return LocalDateTime.parse(dateStr.substring(0, 19).replace(" ", "T")).atZone(ZONE_SP);
        } catch (Exception e) {
            try {
                return LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(ZONE_SP);
            } catch (Exception ex) {
                return ZonedDateTime.now(ZONE_SP);
            }
        }
    }

    private double calcularMediaEficienciaDaLista(List<ActivitySummaryEntity> lista, int limiteItens) {
        if (lista == null || lista.isEmpty()) {
            return 0.0;
        }
        return lista.stream()
                .limit(limiteItens)
                .mapToDouble(ActivitySummaryEntity::getEfficiencyIndex)
                .average()
                .orElse(0.0);
    }

    private String formatarHistoricoParaPrompt(List<ActivitySummaryEntity> historico, String rotuloTreino) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("--- HISTÓRICO DE %s ---\n", rotuloTreino.toUpperCase()));

        if (historico == null || historico.isEmpty()) {
            sb.append("  - Nenhum treino correspondente encontrado no banco até o momento.\n");
        } else {
            historico.stream()
                    .limit(5)
                    .forEach(t -> sb.append(String.format("  - %s: Efficiency Index: %.3f\n",
                            t.getStartDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                            t.getEfficiencyIndex())));
        }
        return sb.toString();
    }
}