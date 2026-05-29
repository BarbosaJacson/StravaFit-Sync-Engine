package jackson.stravafit.service;

import jackson.stravafit.client.GeminiClient;
import jackson.stravafit.model.StravaActivity;
import jackson.stravafit.model.ActivityEntity;
import jackson.stravafit.repository.ActivityRepository;
import jackson.stravafit.model.WorkoutPrescriptionEntity;
import jackson.stravafit.repository.WorkoutPrescriptionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class InsightService {

    private final GeminiClient geminiClient;
    private final ActivityRepository activityRepository;
    private final WorkoutPrescriptionRepository workoutPrescriptionRepository;
    private final KnowledgeService knowledgeService;
    private final int hrMaxConfig;
    private final int hrResting;
    private final int idadeAtleta;

    private static final ZoneId ZONE_SP = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter BRAZIL_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter NEXT_WORKOUT_FORMATTER = DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy");

    private static final String NO_MARKDOWN_INSTRUCTION =
            "--- REGRAS DE SAÍDA: Use apenas texto puro com quebras de linha e emojis. PROIBIDO o uso de blocos de código (```). ---";

    public InsightService(GeminiClient geminiClient,
                          ActivityRepository activityRepository,
                          WorkoutPrescriptionRepository workoutPrescriptionRepository,
                          KnowledgeService knowledgeService,
                          @Value("${atleta.hr-max:173}") int hrMaxConfig,
                          @Value("${atleta.hr-resting:53}") int hrResting,
                          @Value("${atleta.idade:47}") int idadeAtleta) {
        this.geminiClient = geminiClient;
        this.activityRepository = activityRepository;
        this.workoutPrescriptionRepository = workoutPrescriptionRepository;
        this.knowledgeService = knowledgeService;
        this.hrMaxConfig = hrMaxConfig;
        this.hrResting = hrResting;
        this.idadeAtleta = idadeAtleta;
    }

    @Transactional
    public String getActivityInsight(StravaActivity activity, List<StravaActivity.MinuteAnalysis> analysis) {
        return generateInsight(
                activity.getId(),
                activity.getName(),
                activity.getDistance() / 1000.0,
                activity.getStartDateLocal(),
                activity.getAverageSpeed() * 3.6,
                analysis);
    }

    @Transactional
    public String getActivityInsightFromEntity(ActivityEntity entity) {
        if (entity.getMinuteDetails() == null || entity.getMinuteDetails().isEmpty()) {
            return "Erro: Dados de telemetria insuficientes para gerar análise.";
        }

        List<StravaActivity.MinuteAnalysis> analysis = entity.getMinuteDetails().stream()
                .map(m -> new StravaActivity.MinuteAnalysis(m.getMinute(), m.getAverageHeartRate(), m.getMaxHeartRate(), m.getZone(), m.getAverageElevation(), m.getAverageCadence()))
                .toList();

        Double averageSpeed = null;
        if (entity.getDistanceKm() != null && entity.getTotalTimeMinutes() != null && entity.getTotalTimeMinutes() > 0) {
            double totalHours = entity.getTotalTimeMinutes() / 60.0;
            averageSpeed = entity.getDistanceKm() / totalHours;
        }

        return generateInsight(entity.getId(), entity.getName(), entity.getDistanceKm(), entity.getStartDate(), averageSpeed, analysis);
    }

    private String generateInsight(Long activityId, String name, Double distance, String dateStr, Double averageSpeed, List<StravaActivity.MinuteAnalysis> analysis) {
        ZonedDateTime activityDate = parseToZonedDateTime(dateStr);
        String proximoTreinoData = calcularProximaDataTreino(activityDate);

        log.info("[AI] Iniciando construção do prompt para atividade: {}", name);
        String prompt = buildProfessionalPrompt(name, distance, activityDate, averageSpeed, analysis, proximoTreinoData);

        String result = geminiClient.getInsight(prompt);
        log.info("[AI] Resposta da IA recebida com sucesso.");

        extractAndSavePrescription(activityId, result);

        String cleanResult = removeXmlBlock(result);

        return sanitizeOutput(cleanResult);
    }

    private String buildProfessionalPrompt(String name, Double distance, ZonedDateTime date, Double averageSpeed, List<StravaActivity.MinuteAnalysis> analysis, String proximoTreinoData) {
        String scientificContext = knowledgeService.getScientificContext();
        log.info("[KNOWLEDGE] Contexto científico enviado para IA: {} caracteres.", scientificContext != null ? scientificContext.length() : 0);
        if (scientificContext == null || scientificContext.isBlank()) {
            log.error("ALERTA: Base de conhecimento (studySettings) está VAZIA no banco de dados!");
            scientificContext = "Use as diretrizes gerais de San-Millán para eficiência mitocondrial.";
        }

        List<ActivityEntity> historico = activityRepository.findTop10ByOrderByStartDateDesc();

        historico = historico.stream()
                .sorted(Comparator.comparing((ActivityEntity a) -> parseToZonedDateTime(a.getStartDate())).reversed())
                .toList();

        log.info("[AI] Histórico recuperado: {} atividades para cálculo de médias.", historico.size());

        double histVo2Medio = 0;
        double histFcMaxMedia = 0;
        double histFcMedioDasMedias = 0;
        double histPaceMedioSegundos = 0;

        if (!historico.isEmpty()) {
            String datasHistorico = historico.stream()
                    .map(ActivityEntity::getStartDate)
                    .collect(Collectors.joining(", "));
            log.info("[AI] Datas do histórico processadas (ordem DESC): [{}]", datasHistorico);

            histFcMaxMedia = historico.stream()
                    .mapToDouble(a -> a.getMaxHeartRate() != null ? a.getMaxHeartRate() : hrMaxConfig)
                    .average().orElse(0);

            histFcMedioDasMedias = historico.stream()
                    .mapToDouble(a -> a.getAverageHeartRate() != null ? a.getAverageHeartRate() : 0)
                    .average().orElse(0);

            histVo2Medio = historico.stream()
                    .mapToDouble(a -> 15.3 * ((a.getMaxHeartRate() != null ? a.getMaxHeartRate() : hrMaxConfig) / (double) hrResting))
                    .average().orElse(0);

            histPaceMedioSegundos = historico.stream()
                    .filter(a -> a.getDistanceKm() != null && a.getDistanceKm() > 0 && a.getTotalTimeMinutes() != null)
                    .mapToDouble(a -> (a.getTotalTimeMinutes() * 60.0) / a.getDistanceKm())
                    .average().orElse(0);
        }

        // --- CÁLCULO DE MÉTRICAS DO TREINO ATUAL (CORRIGIDO SEM COMPARAÇÃO INVÁLIDA) ---
        double fcMedia = analysis.stream()
                .map(StravaActivity.MinuteAnalysis::getAverageHeartRate)
                .filter(Objects::nonNull) // Filtra o Double objeto antes de virar primitivo
                .mapToDouble(Double::doubleValue)
                .average().orElse(0.0);

        double fcMax = analysis.stream()
                .map(StravaActivity.MinuteAnalysis::getMaxHeartRate)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .max().orElse(0.0);

        int duracao = analysis.size();

        double variance = analysis.stream()
                .map(StravaActivity.MinuteAnalysis::getAverageHeartRate)
                .filter(Objects::nonNull)
                .mapToDouble(m -> Math.pow(m - fcMedia, 2)).average().orElse(0.0);
        double stdDev = Math.sqrt(variance);

        int zonaPredominante = analysis.stream()
                .map(StravaActivity.MinuteAnalysis::getZone)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(0);

        long z2Count = analysis.stream()
                .map(StravaActivity.MinuteAnalysis::getZone)
                .filter(z -> z != null && z == 2)
                .count();
        double z2Percent = (duracao > 0) ? (z2Count * 100.0) / duracao : 0.0;

        double firstHalf = analysis.stream().limit(duracao / 2)
                .map(StravaActivity.MinuteAnalysis::getAverageHeartRate)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).average().orElse(fcMedia);

        double secondHalf = analysis.stream().skip(duracao / 2)
                .map(StravaActivity.MinuteAnalysis::getAverageHeartRate)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).average().orElse(fcMedia);

        String comportamento = (secondHalf > firstHalf * 1.05) ? "subindo gradualmente (drift)" : "predominantemente estável";

        double fcMaxPercentage = (fcMax / hrMaxConfig) * 100;

        double vo2MaxEstimado = 15.3 * ((double) hrMaxConfig / hrResting);

        double maxElev = analysis.stream()
                .map(StravaActivity.MinuteAnalysis::getAverageElevation)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).max().orElse(0.0);
        double minElev = analysis.stream()
                .map(StravaActivity.MinuteAnalysis::getAverageElevation)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).min().orElse(0.0);
        double ganhoAlt = maxElev - minElev;

        // --- DECLARAÇÃO DAS VARIÁVEIS AUSENTES (RESOLVE OS ERROS DE SÍMBOLO) ---
        String paceFormatted = formatSpeedToPace(averageSpeed);
        String workoutIntensityType = determineWorkoutIntensityType(stdDev, fcMaxPercentage);
        String dataFormatada = date.format(BRAZIL_FORMATTER);
        double safeDistance = distance != null ? distance : 0.0;

        StringBuilder sb = new StringBuilder();

        sb.append("SISTEMA: Motor de Classificação Fisiológica StravaFit.\n");
        sb.append("ATIVIDADE: ").append(name).append("\n");
        sb.append(String.format("PERFIL DO ATLETA: %d anos | FC Máx: %d | FC Repouso: %d | VO2 Est: %.1f\n", idadeAtleta, hrMaxConfig, hrResting, vo2MaxEstimado));
        sb.append("REGRA: Retorne EXATAMENTE o texto do campo 'Diagnóstico Clínico-Esportivo da IA' do cenário identificado no arquivo abaixo, adaptando o tom para a idade do atleta.\n\n");

        sb.append("--- BASE DE CONHECIMENTO (studySettings) ---\n");
        sb.append(scientificContext).append("\n\n");

        sb.append("--- CONTEXTO HISTÓRICO (MÉDIAS DOS ÚLTIMOS 10 TREINOS) ---\n")
                .append(String.format("- VO2 Máx Médio: %.1f ml/kg/min\n", histVo2Medio))
                .append(String.format("- FC Máxima Média: %d bpm\n", (int)histFcMaxMedia))
                .append(String.format("- FC Média Geral: %d bpm\n", (int)histFcMedioDasMedias))
                .append(String.format("- Pace Médio: %s min/km\n\n", formatSecondsToPace(histPaceMedioSegundos)));

        LocalDate dataTreino = date.toLocalDate();
        workoutPrescriptionRepository.findByScheduledDate(dataTreino).ifPresent(plano -> sb.append("--- TREINO PROGRAMADO PARA ESTA DATA (REFERÊNCIA DE COMPARAÇÃO) ---\n")
                .append("- Tipo Planejado: ").append(plano.getType()).append("\n")
                .append("- Duração/Volume: ").append(plano.getDuration()).append("\n")
                .append("- Intensidade Alvo: ").append(plano.getIntensity()).append("\n")
                .append("- Foco Técnico: ").append(plano.getFocus()).append("\n\n")
                .append("INSTRUÇÃO CRÍTICA DE COMPARAÇÃO: Este era o objetivo para hoje. No seu 'Diagnóstico Fisiológico', ")
                .append("determine explicitamente se o atleta seguiu o plano ou se 'furou' a planilha (ex: correu forte in dia de Z2).\n\n"));

        sb.append("--- DADOS DO TREINO ATUAL ---\n");
        sb.append(String.format("- Tempo Total de Treino: %d minutos\n", duracao));
        sb.append(String.format("- Frequência Cardíaca Média: %.0f bpm\n", fcMedia));
        sb.append(String.format("- Frequência Cardíaca Máxima: %.0f bpm\n", fcMax));
        sb.append(String.format("- Percentual em Zona 2: %.1f%%\n", z2Percent));
        sb.append(String.format("- VO2 Max Estimado (Perfil): %.1f ml/kg/min\n", vo2MaxEstimado));
        sb.append(String.format("- Comportamento da Frequência Cardíaca: %s\n", comportamento));
        sb.append(String.format("- Desvio Padrão da FC: %.1f bpm\n", stdDev));
        sb.append(String.format("- Altimetria: %.0f m | Pace Médio: %s\n", ganhoAlt, paceFormatted));
        sb.append(String.format("- Tipo de Intensidade Detectado: %s\n", workoutIntensityType));
        sb.append("------------------------------\n\n");

        sb.append("TAREFA DE ANÁLISE TÉCNICA E CIENTÍFICA:\n")
                .append("1. ANÁLISE DE INTENSIDADE:\n")
                .append("   - Para ALTA_INTENSIDADE: Foque nos PICOS de Frequência Cardíaca e Ritmo na SÉRIE TEMPORAL. Ignore a média geral, pois ela é diluída pela recuperação.\n")
                .append("   - Para BAIXA_INTENSIDADE: Foque na ESTABILIDADE e na manutenção rigorosa da Zona 2. Use o campo 'comportamento' para identificar drift cardíaco (desacoplamento aeróbico), validando a eficiência mitocondrial conforme San-Millán.\n")
                .append("2. COMPARAÇÃO PLANO VS REAL: Se houver um 'TREINO PROGRAMADO' acima, compare-o rigorosamente com os dados reais. Valide se o atleta respeitou as zonas prescritas ou se houve desvio (ex: picos em dia de Zona 2).\n")
                .append("3. ENQUADRAMENTO CIENTÍFICO: Classifique o impacto metabólico usando a BASE DE CONHECIMENTO (San-Millán, Seiler, Olguín). Explique como os picos ou a estabilidade afetaram a saúde mitocondrial e as vias de sinalização (como NOX2).\n")
                .append("4. FORMATO: Monte o retorno estritamente no formato estruturado abaixo (SEM ASTERISCOS nos títulos e sem blocos de código).\n\n");

        sb.append("🏃‍♂️ StravaFit IA - Análise de Eficiência Metabólica\n\n").append(dataFormatada).append("\n");
        sb.append("📌 Cenário Detectado: [Título]\n");
        sb.append("📊 Métricas: ").append(String.format("%.1f km | ", safeDistance)).append(duracao).append(" min | FC Média: ").append((int)fcMedia)
                .append(" bpm | FC Máx: ").append((int)fcMax).append(" bpm | Zona Predom: Z").append(zonaPredominante).append(" | VO2 Max: ")
                .append(String.format("%.1f", vo2MaxEstimado)).append(" | Pace: ").append(paceFormatted).append("\n\n");

        sb.append("🩺 DIAGNÓSTICO FISIOLÓGICO:\n")
                .append("[Transcreva aqui EXATAMENTE o texto do campo 'Diagnóstico Clínico-Esportivo da IA' da BASE de conhecimento para o cenário identificado]\n\n")

                .append("ANÁLISE DE RITMO E COMPORTAMENTO CARDÍACO:\n")
                .append("[Análise técnica curta de Pace vs BPM vs Altimetria e identificação da Zona Cardíaca predominante]\n\n");

        sb.append("🏃‍♂️ CONCLUSÃO E PRÓXIMO PASSO:\n")
                .append("[Explique o impacto na saúde mitocondrial usando metáforas do estudo e dê um conselho prático final].\n\n");

        sb.append("--- REPOSIÇÃO E DESCANSO PÓS-TREINO ---\n")
                .append("CONSULTE o CONTEXTO CIENTÍFICO (studySettings) na seção 'DIRETRIZES PARA ALIMENTAÇÃO DE REPOSIÇÃO E DESCANSO'. ")
                .append("Com base nessas diretrizes, forneça recomendações específicas para o atleta, considerando a intensidade e duração do treino atual.\n")
                .append("[Recomendações de reposição nutricional e descanso pós-treino extraídas do CONTEXTO CIENTÍFICO]\n\n");

        sb.append("--- PRESCRIÇÃO STRAFIT PREDICT ---\n");
        sb.append("DATA PROGRAMADA: ").append(proximoTreinoData).append("\n\n")
                .append("DIRETRIZES OBRIGATÓRIAS DE PRESCRIÇÃO (ESTRITAMENTE VINCULADAS AO CALENDÁRIO):\n")
                .append("1. REGRA DE OURO (SOBREPÕE QUALQUER ESTUDO): Identifique o dia da semana em 'DATA PROGRAMADA' e ignore outras sugestões de intensidade se o dia não permitir:\n")
                .append("   - Se 'DATA PROGRAMADA' for SÁBADO: Prescreva OBRIGATORIAMENTE um TREINO LONGO em Zona 2. É PROIBIDO prescrever tiros ou alta intensidade no sábado ou terça feira.\n")
                .append("   - Se 'DATA PROGRAMADA' for TERÇA-FEIRA: Prescreva um TREINO CURTO em Zona 2 (Manutenção).\n")
                .append("   - Se 'DATA PROGRAMADA' for QUINTA-FEIRA: Prescreva um TREINO DE INTENSIDADE (TIROS/HIIT).\n\n")
                .append("2. SELEÇÃO DE NÍVEL (Apenas para Quintas): Consulte a MATRIZ DE PROGRESSÃO DE INTENSIDADE no studySettings. ")
                .append("Selecione o NÍVEL adequado (use Nível 1 se não houver intensidade recente no histórico) e calcule os ritmos alvo ")
                .append("garantindo que os tiros sejam significativamente mais rápidos que o 'Ritmo Médio de Corrida' (Pace Médio) de 10 treinos do usuário.\n\n")
                .append("3. FORMATO VISUAL OBRIGATÓRIO (PARA O USUÁRIO):\n")
                .append("Apresente a prescrição obrigatoriamente neste formato de lista antes de qualquer outro texto:\n")
                .append("PRESCRICÃO DE TREINO:\n")
                .append("Tipo: [Tipo do treino]\n")
                .append("Duração: [Tempo ou distância prevista]\n")
                .append("Intensidade: [FC alvo e Zona de esforço]\n")
                .append("Foco: [Objetivo técnico ou biológico]\n")
                .append("Método: [Breve explicação de como executar]\n");

        sb.append("\n--- INSTRUÇÃO TÉCNICA DO SISTEMA ---\n")
                .append("Ao final do relatório, adicione OBRIGATORIAMENTE um bloco estruturado no formato XML abaixo ")
                .append("para processamento automatizado no banco de dados. Preencha os campos com os dados da prescrição criada:\n")
                .append("IMPORTANTE: Não use blocos de código (crases) ao redor do XML. Use apenas texto puro.\n")
                .append("<prescription_data>\n")
                .append("  <scheduled_date>[YYYY-MM-DD]</scheduled_date>\n")
                .append("  <type>[Tipo do treino]</type>\n")
                .append("  <duration>[Duração, ex: 60-75 min]</duration>\n")
                .append("  <intensity>[Zona e FC alvo]</intensity>\n")
                .append("  <focus>[Foco principal do treino]</focus>\n")
                .append("  <method>[Resumo do método em uma linha]</method>\n")
                .append("</prescription_data>\n");

        sb.append(NO_MARKDOWN_INSTRUCTION).append("\n\n");
        sb.append("SERIE TEMPORAL (Min: BPM/Alt/Cad):\n");
        for (int i = 0; i < analysis.size(); i += 2) {
            StravaActivity.MinuteAnalysis m = analysis.get(i);

            // Como os métodos já retornam 'double' primitivo, lemos direto sem checar null
            double hrVal = m.getAverageHeartRate();
            double elevVal = m.getAverageElevation();
            double cadVal = m.getAverageCadence();

            sb.append(String.format("%d:%.0f/%.0fm/%.0f | ",
                    m.getMinute(),
                    hrVal,
                    elevVal,
                    cadVal));
        }

        sb.append("\n\nPRÓXIMO TREINO: ").append(proximoTreinoData);

        return sb.toString();
    }

    @Transactional
    public void extractAndSavePrescription(Long activityId, String rawAiResponse) {
        try {
            String cleanResponse = rawAiResponse.replace("```xml", "").replace("```", "").trim();

            int startTag = cleanResponse.indexOf("<prescription_data>");
            int endTag = cleanResponse.indexOf("</prescription_data>");

            if (startTag != -1 && endTag > startTag) {
                String xml = cleanResponse.substring(startTag, endTag + 20);
                String scheduledDateStr = extractTagValue(xml, "scheduled_date");

                if (scheduledDateStr != null) {
                    log.info("[DB] Tentando persistir prescrição para a data: {}", scheduledDateStr);
                    WorkoutPrescriptionEntity prescription = WorkoutPrescriptionEntity.builder()
                            .activityId(activityId)
                            .scheduledDate(LocalDate.parse(scheduledDateStr.replaceAll("[^0-9-]", "")))
                            .type(extractTagValue(xml, "type"))
                            .duration(extractTagValue(xml, "duration"))
                            .intensity(extractTagValue(xml, "intensity"))
                            .focus(extractTagValue(xml, "focus"))
                            .paceTarget(extractTagValue(xml, "method"))
                            .build();

                    WorkoutPrescriptionEntity saved = workoutPrescriptionRepository.saveAndFlush(prescription);
                    log.info("[DB] Prescrição ID {} salva com sucesso para: {}", saved.getId(), saved.getScheduledDate());
                }
            }
        } catch (Exception e) {
            log.error("[ERROR] Falha ao processar XML de prescrição: {}", e.getMessage());
        }
    }

    private String extractTagValue(String xml, String tag) {
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int start = xml.indexOf(open);
        int end = xml.indexOf(close);
        if (start != -1 && end != -1) {
            return xml.substring(start + open.length(), end).trim();
        }
        return null;
    }

    private String determineWorkoutIntensityType(double stdDev, double fcMaxPercentage) {
        if (stdDev > 8.0 && fcMaxPercentage > 90.0) {
            return "ALTA_INTENSIDADE (Intervalado/Picos)";
        } else if (stdDev > 5.0 && fcMaxPercentage > 80.0) {
            return "MÉDIA_INTENSIDADE (Tempo Run/Fartlek)";
        }
        return "BAIXA_INTENSIDADE (Contínuo/Zona 2)";
    }

    private String removeXmlBlock(String text) {
        if (text == null) return "";
        int xmlStart = text.indexOf("<prescription_data>");
        if (xmlStart != -1) {
            return text.substring(0, xmlStart).trim();
        }
        return text;
    }

    private String sanitizeOutput(String text) {
        if (text == null) return "";
        return text.replaceAll("[*#]", "").trim();
    }

    private String formatSecondsToPace(double totalSeconds) {
        if (totalSeconds <= 0) return "N/A";
        long minutes = (long) (totalSeconds / 60);
        long seconds = (long) (totalSeconds % 60);
        return String.format("%d:%02d", minutes, seconds);
    }

    private String formatSpeedToPace(Double speedKmH) {
        if (speedKmH == null || speedKmH == 0) return "N/A";
        double totalSeconds = 3600 / speedKmH;
        return formatSecondsToPace(totalSeconds) + " min/km";
    }

    private String calcularProximaDataTreino(ZonedDateTime date) {
        LocalDate activityDate = date.toLocalDate();
        LocalDate today = LocalDate.now(ZONE_SP);

        LocalDate baseDate = activityDate.isBefore(today) ? today : activityDate;
        LocalDate proximo = baseDate.plusDays(1);

        while (proximo.getDayOfWeek() != DayOfWeek.TUESDAY &&
                proximo.getDayOfWeek() != DayOfWeek.THURSDAY &&
                proximo.getDayOfWeek() != DayOfWeek.SATURDAY) {
            proximo = proximo.plusDays(1);
        }
        return proximo.atStartOfDay(ZONE_SP).format(NEXT_WORKOUT_FORMATTER);
    }

    private ZonedDateTime parseToZonedDateTime(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return ZonedDateTime.now(ZONE_SP);
        }

        try {
            String localPart = dateStr.length() >= 19 ? dateStr.substring(0, 19) : dateStr;
            return LocalDateTime.parse(localPart.replace(" ", "T"), DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(ZONE_SP);
        } catch (Exception e) {
            try {
                log.warn("[DATE] Falha ao parsear data completa, tentando data simples: {}", dateStr);
                return LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(ZONE_SP);
            } catch (Exception ex) {
                try {
                    return ZonedDateTime.parse(dateStr).withZoneSameLocal(ZONE_SP);
                } catch (Exception exc) {
                    return ZonedDateTime.now(ZONE_SP);
                }
            }
        }
    }
}