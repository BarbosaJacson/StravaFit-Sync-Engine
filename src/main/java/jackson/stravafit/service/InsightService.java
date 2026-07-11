package jackson.stravafit.service;

import jackson.stravafit.client.GeminiClient;
import jackson.stravafit.model.StravaActivity;
import jackson.stravafit.model.ActivityEntity;
import jackson.stravafit.model.WorkoutPrescriptionEntity;
import jackson.stravafit.repository.ActivityRepository;
import jackson.stravafit.repository.WorkoutPrescriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InsightService {

    private final GeminiClient geminiClient;
    private final WorkoutPrescriptionRepository workoutPrescriptionRepository;
    private final KnowledgeService knowledgeService;
    private final ActivityRepository activityRepository;
    private final ActivityService activityService;

    @Value("${atleta.hr-max:173}")
    private int hrMaxConfig;

    @Value("${atleta.hr-resting:53}")
    private int hrResting;

    private static final ZoneId ZONE_SP = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter BRAZIL_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter NEXT_WORKOUT_FORMATTER = DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy");

    // Gerador de recomendação pré-treino baseado no sono
    public String getPreWorkoutRecommendation(String sleepQuality) {
        String prompt = """
            --- INSTRUÇÃO DE FORMATAÇÃO: O RETORNO DEVE SER COESO, ORGANIZADO E BEM FORMATADO, UTILIZANDO TÍTULOS E SUBTÍTULOS EM LETRAS MAIÚSCULAS. NÃO USE ASTERISCOS OU OUTROS SÍMBOLOS DE MARKDOWN. ---

            AVALIAÇÃO PRÉ-TREINO: CONDIÇÕES FISIOLÓGICAS

            SITUAÇÃO DO SONO: %S

            TAREFA:
            Como um Especialista em Fisiologia, avalie esta qualidade de sono para um atleta que tem um treino de Zona 2 programado para hoje as 05:30 da manhã.
            Se o sono foi ruim ou muito ruim, sugira uma adaptação (redução de volume ou intensidade, ou até mesmo descanso total). Se o sono foi bom, reforce o plano.
            Forneça uma recomendação curta, direta e técnica.
            """.formatted(sleepQuality);
        
        return geminiClient.getInsight(prompt);
    }

    public String getActivityInsight(StravaActivity activity, List<StravaActivity.MinuteAnalysis> analysis, List<StravaActivity.ActivityStream> streams) {
        Double averageSpeedKmh = activity.getAverageSpeed() != null ? activity.getAverageSpeed() * 3.6 : null;
        return generateInsight(activity.getId(), activity.getName(), activity.distanceKm(), activity.getStartDateLocal(), averageSpeedKmh, analysis, streams);
    }

    public String getActivityInsightFromEntity(ActivityEntity entity) {
        if (entity.getMinuteDetails() == null || entity.getMinuteDetails().isEmpty()) {
            return "Erro: Dados de telemetria insuficientes para gerar análise.";
        }

        List<StravaActivity.MinuteAnalysis> analysis = entity.getMinuteDetails().stream()
                .map(m -> new StravaActivity.MinuteAnalysis(m.getMinute(), m.getAverageHeartRate(), m.getMaxHeartRate(), m.getZone(), m.getAverageElevation(), m.getAverageCadence()))
                .toList();

        Double averageSpeed = (entity.getDistanceKm() != null && entity.getTotalTimeMinutes() != null && entity.getTotalTimeMinutes() > 0) ?
                                entity.getDistanceKm() / (entity.getTotalTimeMinutes() / 60.0) : null;
        
        return generateInsight(entity.getId(), entity.getName(), entity.getDistanceKm(), entity.getStartDate(), averageSpeed, analysis, null);
    }

    private String generateInsight(Long activityId, String name, Double distance, String dateStr, Double averageSpeed, List<StravaActivity.MinuteAnalysis> analysis, List<StravaActivity.ActivityStream> streams) {
        ZonedDateTime activityDate = parseToZonedDateTime(dateStr);
        String proximoTreinoData = calcularProximaDataTreino(activityDate);
        
        Optional<WorkoutPrescriptionEntity> prescricaoAnterior = workoutPrescriptionRepository.findTopByScheduledDateOrderByCreatedAtDesc(activityDate.toLocalDate());
        
        String prompt = buildProfessionalPrompt(name, distance, activityDate, averageSpeed, analysis, streams, proximoTreinoData, prescricaoAnterior.orElse(null));
        
        String insight = geminiClient.getInsight(prompt);
        
        saveNewPrescription(activityId, insight);
        
        return insight;
    }

    private String buildProfessionalPrompt(String name, Double distance, ZonedDateTime date, Double averageSpeed, List<StravaActivity.MinuteAnalysis> analysis, List<StravaActivity.ActivityStream> streams, String proximaData, WorkoutPrescriptionEntity prescricaoAnterior) {
        String dataFormatada = date.format(BRAZIL_FORMATTER);
        String paceFormatted = (averageSpeed != null && averageSpeed > 0) ? formatSpeedToPace(averageSpeed) : "N/A";
        String scientificContext = knowledgeService.getScientificContext();

        // --- CÁLCULO DE MÉTRICAS DO TREINO ATUAL ---
        double fcMedia = analysis.stream().mapToDouble(StravaActivity.MinuteAnalysis::getAverageHeartRate).average().orElse(0.0);
        double fcMax = analysis.stream().mapToDouble(StravaActivity.MinuteAnalysis::getMaxHeartRate).max().orElse(0.0);
        int duracao = analysis.size();
        
        double variance = analysis.stream().mapToDouble(m -> Math.pow(m.getAverageHeartRate() - fcMedia, 2)).average().orElse(0.0);
        double stdDev = Math.sqrt(variance);

        double fcMaxPercentage = (fcMax / hrMaxConfig) * 100;

        String zonaPredominante;
        if (streams != null) {
            List<Double> hrData = activityService.getHeartRateStream(streams);
            zonaPredominante = activityService.calculateDominantZoneSummary(hrData);
        } else {
            zonaPredominante = analysis.stream()
                .map(StravaActivity.MinuteAnalysis::getZone)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .map(zone -> "Z" + zone)
                .orElse("N/A");
        }
        
        double vo2MaxEstimado = 15.3 * (fcMax / hrResting);

        double efficiencyIndex = (distance != null && distance > 0 && fcMedia > 0 && duracao > 0) ? (distance * 1000) / (fcMedia * duracao) : 0.0;

        // --- CÁLCULO DE MÉTRICAS DO HISTÓRICO ---
        List<ActivityEntity> historico = activityRepository.findTop10ByOrderByStartDateDesc();
        double histVo2Medio = historico.stream().mapToDouble(a -> 15.3 * ((a.getMaxHeartRate() != null ? a.getMaxHeartRate() : hrMaxConfig) / (double) hrResting)).average().orElse(0.0);
        double histFcMaxMedia = historico.stream().mapToDouble(a -> a.getMaxHeartRate() != null ? a.getMaxHeartRate() : 0).average().orElse(0.0);
        double histFcMediaGeral = historico.stream().mapToDouble(a -> a.getAverageHeartRate() != null ? a.getAverageHeartRate() : 0).average().orElse(0.0);
        double histPaceMedioSegundos = historico.stream().filter(a -> a.getDistanceKm() != null && a.getDistanceKm() > 0 && a.getTotalTimeMinutes() != null && a.getTotalTimeMinutes() > 0).mapToDouble(a -> (a.getTotalTimeMinutes() * 60.0) / a.getDistanceKm()).average().orElse(0.0);
        double histEfficiencyIndex = historico.stream().filter(a -> a.getDistanceKm() != null && a.getDistanceKm() > 0 && a.getAverageHeartRate() != null && a.getAverageHeartRate() > 0 && a.getTotalTimeMinutes() != null && a.getTotalTimeMinutes() > 0).mapToDouble(a -> (a.getDistanceKm() * 1000) / (a.getAverageHeartRate() * a.getTotalTimeMinutes())).average().orElse(0.0);

        StringBuilder sb = new StringBuilder();

        // --- INSTRUÇÃO DE SISTEMA / CONTEXTO ---
        sb.append("VOCÊ É UM ANALISTA DE PERFORMANCE DE ELITE. SUA MISSÃO É ANALISAR OS DADOS DE TREINO E FORNECER FEEDBACKS TÉCNICOS SEGUINDO ESTRITAMENTE O FORMATO SOLICITADO.\n\n");
        sb.append("REGRA DE FORMATAÇÃO: GERE A RESPOSTA USANDO APENAS TEXTO PURO, TÍTULOS EM MAIÚSCULAS E QUEBRAS DE LINHA. É ESTRITAMENTE PROIBIDO O USO DE MARKDOWN (ASTERISCOS, HASHTAGS, ETC.).\n\n");
        
        if (scientificContext != null && !scientificContext.isBlank()) {
            sb.append("--- BASE DE CONHECIMENTO ---\n").append(scientificContext).append("\n\n");
        } else {
            log.warn("A base de conhecimento (cenários do MongoDB) está vazia ou não foi encontrada.");
        }

        if (prescricaoAnterior != null) {
            sb.append("--- TREINO PRESCRITO PARA ESTA DATA ---\n");
            sb.append("Duração: ").append(prescricaoAnterior.getDuration()).append("\n");
            sb.append("Faixa de FC: ").append(prescricaoAnterior.getIntensity()).append("\n");
            sb.append("Zona: ").append(prescricaoAnterior.getType()).append("\n");
            sb.append("Foco: ").append(prescricaoAnterior.getFocus()).append("\n\n");
        }

        sb.append("--- DADOS DO TREINO ATUAL ---\n");
        sb.append("DADOS DO TREINO: ").append(name).append(" | ").append(String.format("%.1f km", distance)).append(" | Pace Médio: ").append(paceFormatted).append("\n");
        
        sb.append("\n\n--- TAREFA PARA A IA ---\n");
        sb.append("1. Analise os 'DADOS DO TREINO ATUAL'.\n");
        sb.append("2. Com base na sua análise e na 'BASE DE CONHECIMENTO', identifique o 'CENÁRIO' do treino.\n");
        sb.append("3. Construa a resposta final preenchendo ESTRITAMENTE o formato abaixo.\n\n");

        sb.append("--- FORMATO DE SAÍDA OBRIGATÓRIO ---\n");
        sb.append("🏃‍♂️ StravaFit IA - Análise de Eficiência Metabólica\n\n");
        sb.append(dataFormatada).append("\n");
        sb.append("📌 Cenário Detectado: [PREENCHA AQUI COM O TÍTULO DO CENÁRIO IDENTIFICADO]\n\n");
        sb.append("⚡ Intensidade do Estímulo: [ANALISE O DESVIO PADRÃO DA FC (").append(String.format("%.1f", stdDev)).append(" bpm) E O PICO DE FC (").append(String.format("%.0f%%", fcMaxPercentage)).append(" da FC Máx) PARA CLASSIFICAR A INTENSIDADE.]\n\n");
        
        sb.append("📊 Métricas: ").append(String.format("%.1f km", distance)).append(" | ").append(duracao).append(" min | FC Méd: ").append(String.format("%.0f", fcMedia)).append(" bpm | FC Max: ").append(String.format("%.0f", fcMax)).append(" bpm | Zona Pred: ").append(zonaPredominante).append(" | Efic: ").append(String.format("%.3f", efficiencyIndex)).append(" | VO2: ").append(String.format("%.1f", vo2MaxEstimado)).append(" | Pace: ").append(paceFormatted).append("\n\n");
        
        sb.append("📋 Referência (Treino Anterior Prescrito):\n");
        if (prescricaoAnterior != null) {
            sb.append("- Tipo Planejado: ").append(prescricaoAnterior.getType()).append("\n");
            sb.append("- Duração/Volume: ").append(prescricaoAnterior.getDuration()).append("\n");
            sb.append("- Intensidade Alvo: ").append(prescricaoAnterior.getIntensity()).append("\n");
            sb.append("- Foco Técnico: ").append(prescricaoAnterior.getFocus()).append("\n\n");
        } else {
            sb.append("- Nenhuma prescrição encontrada para esta data.\n\n");
        }
        
        sb.append("📊 Histórico Médio (Últimos 10 treinos):\n");
        sb.append("- VO2 Máx Médio: ").append(String.format("%.1f", histVo2Medio)).append(" ml/kg/min\n");
        sb.append("- FC Máxima Média: ").append(String.format("%.0f", histFcMaxMedia)).append(" bpm\n");
        sb.append("- FC Média Geral: ").append(String.format("%.0f", histFcMediaGeral)).append(" bpm\n");
        sb.append("- Pace Médio: ").append(formatSecondsToPace(histPaceMedioSegundos)).append(" min/km\n");
        sb.append("- Eficiência Média: ").append(String.format("%.3f", histEfficiencyIndex)).append(" (m/bpm*min)\n\n");
        
        sb.append("1.0 - STATUS DO TREINO (CUMPRIMENTO DO PLANO):\n");
        sb.append("[ANALISE A 'SERIE TEMPORAL' PARA DETECTAR PICOS DE FC. SE HOUVER PICOS INTERVALADOS, CLASSIFIQUE O TREINO COMO 'INTERVALADO'. CASO CONTRÁRIO, COMO 'CONTÍNUO'. COMPARE ESTA CLASSIFICAÇÃO COM O 'TREINO PRESCRITO' E DETERMINE O STATUS (CUMPRIDO, NÃO CUMPRIDO, CUMPRIDO PARCIALMENTE). JUSTIFIQUE SUA DECISÃO COM BASE NA ANÁLISE FISIOLÓGICA E NOS OBJETIVOS DO TREINO PRESCRITO.]\n\n");
        
        sb.append("2.0 - DIAGNÓSTICO TÉCNICO FISIOLÓGICO PARA Jacson:\n");
        sb.append("[BUSQUE NA 'BASE DE CONHECIMENTO' O CENÁRIO QUE VOCÊ IDENTIFICOU. TRANSCREVA AQUI O CONTEÚDO DO CAMPO 'DIAGNÓSTICO' DAQUELE CENÁRIO, ADAPTANDO O TEXTO PARA FALAR DIRETAMENTE COM 'Jacson'.]\n\n");
        
        sb.append("3.0 - ANÁLISE DE RITMO E COMPORTAMENTO CARDÍACO (Jacson):\n");
        sb.append("[O 'efficiency_index' calculado para este treino foi de ").append(String.format("%.3f", efficiencyIndex)).append(". BUSQUE NA 'BASE DE CONHECIMENTO' A LEGENDA CORRESPONDENTE A ESTE VALOR E TRANSCREVA-A AQUI, ADAPTANDO O TEXTO PARA FALAR DIRETAMENTE COM 'Jacson'.]\n\n");
        
        sb.append("4.0 - CONCLUSÃO E PRÓXIMO PASSO PARA Jacson:\n");
        sb.append("[FAÇA UMA CONCLUSÃO BREVE E MOTIVACIONAL. USE AS METÁFORAS DO ESTUDO (EX: 'FÁBRICA DE ENERGIA', 'EXPANDINDO O TETO DO MOTOR') PARA EXPLICAR O IMPACTO DO TREINO. DÊ UM CONSELHO PRÁTICO FINAL PARA O ATLETA.]\n\n");
        
        sb.append("5.0 - NUTRIÇÃO / DESCANSO\n");
        sb.append("[CONSULTE A 'BASE DE CONHECIMENTO' (SEÇÃO 'DIRETRIZES PARA ALIMENTAÇÃO DE REPOSIÇÃO E DESCANSO'). COM BASE NA INTENSIDADE E DURAÇÃO DO TREINO ATUAL, FORNEÇA RECOMENDAÇÕES ESPECÍFICAS PARA 'Jacson'.]\n\n");
        
        sb.append("6.0 --- PRESCRIÇÃO STRAFIT PREDICT PARA ").append(proximaData.toUpperCase()).append(" ---\n");
        sb.append("[DIRETRIZES OBRIGATÓRIAS DE PRESCRIÇÃO:\n");
        sb.append("1. REGRA DE OURO DO CALENDÁRIO: IDENTIFIQUE O DIA DA SEMANA EM 'DATA PROGRAMADA' (").append(proximaData).append(") E PRESCREVA OBRIGATORIAMENTE O TIPO DE TREINO CORRESPONDENTE: TERÇA (Z2 CURTO/MÉDIO), QUINTA (TIROS/HIIT), SÁBADO (Z2 LONGO).\n");
        sb.append("2. SELEÇÃO DE NÍVEL (PARA QUINTAS): CONSULTE A 'MATRIZ DE PROGRESSÃO DE INTENSIDADE' NA 'BASE DE CONHECIMENTO' E O 'efficiency_index' DO ATLETA PARA DETERMINAR SE ELE DEVE PROGREDIR OU MANTER O NÍVEL.\n");
        sb.append("3. CÁLCULO DE RITMO ALVO (PARA QUINTAS): CALCULE O RITMO ALVO DOS TIROS COM BASE NO 'Pace Médio' DO HISTÓRICO, APLICANDO AS REGRAS DE GANHO DE VELOCIDADE DA 'BASE DE CONHECIMENTO'.\n");
        sb.append("4. JUSTIFICATIVA BIOLÓGICA: FORNEÇA UMA JUSTIFICATIVA PARA A PRESCRIÇÃO, USANDO OS CONCEITOS DA 'BASE DE CONHECIMENTO' (EX: 'BIOGÊNESE MITOCONDRIAL', 'SINALIZAÇÃO HORMÉTICA', 'MITOHORMESE').]\n");

        return sb.toString();
    }
    
    private void saveNewPrescription(Long activityId, String geminiResponse) {
        log.info("Extraindo e salvando nova prescrição para a atividade {}", activityId);
        // TODO: Implementar a extração dos dados da prescrição da resposta do Gemini
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
        LocalDate hoje = date.toLocalDate();
        LocalDate proximo = hoje.plusDays(1);
        while (proximo.getDayOfWeek() != DayOfWeek.TUESDAY && proximo.getDayOfWeek() != DayOfWeek.THURSDAY && proximo.getDayOfWeek() != DayOfWeek.SATURDAY) {
            proximo = proximo.plusDays(1);
        }
        return proximo.format(NEXT_WORKOUT_FORMATTER);
    }

    private ZonedDateTime parseToZonedDateTime(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return ZonedDateTime.now(ZONE_SP);
        }
        try {
            return ZonedDateTime.parse(dateStr, DateTimeFormatter.ISO_INSTANT.withZone(ZONE_SP));
        } catch (Exception e) {
            try {
                String localPart = dateStr.length() >= 19 ? dateStr.substring(0, 19) : dateStr;
                return LocalDateTime.parse(localPart, DateTimeFormatter.ISO_DATE_TIME).atZone(ZONE_SP);
            } catch (Exception ex) {
                try {
                    return LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(ZONE_SP);
                } catch (Exception exc) {
                    return ZonedDateTime.now(ZONE_SP);
                }
            }
        }
    }
}
