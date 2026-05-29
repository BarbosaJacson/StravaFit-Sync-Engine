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
    private final KnowledgeService knowledgeService; // Injetar KnowledgeService

    private final int hrMaxConfig;
    private final int hrResting;
    private final int idadeAtleta;

    private static final ZoneId ZONE_SP = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter BRAZIL_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter NEXT_WORKOUT_FORMATTER = DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy");

    private static final String NO_MARKDOWN_INSTRUCTION = 
        "--- REGRAS DE SAÍDA: Use apenas texto puro com quebras de linha e emojis. PROIBIDO o uso de blocos de código (```). ---";

    // Construtor manual para resolver avisos de "never assigned" e garantir imutabilidade (World Class Practice)
    public InsightService(GeminiClient geminiClient, 
                          ActivityRepository activityRepository, 
                          WorkoutPrescriptionRepository workoutPrescriptionRepository, 
                          KnowledgeService knowledgeService,
                          @Value("${atleta.hr-max}") int hrMaxConfig,
                          @Value("${atleta.hr-resting}") int hrResting,
                          @Value("${atleta.idade}") int idadeAtleta) {
        this.geminiClient = geminiClient;
        this.activityRepository = activityRepository;
        this.workoutPrescriptionRepository = workoutPrescriptionRepository;
        this.knowledgeService = knowledgeService;
        this.hrMaxConfig = hrMaxConfig;
        this.hrResting = hrResting;
        this.idadeAtleta = idadeAtleta;
    }
    
    public String getActivityInsight(StravaActivity activity, List<StravaActivity.MinuteAnalysis> analysis) {
        // Ajustado para usar getters, tratando o acesso privado relatado
        return generateInsight(
                activity.getId(),
                activity.getName(), 
                activity.getDistance() / 1000.0, 
                activity.getStartDateLocal(), 
                activity.getAverageSpeed() * 3.6, // Converte m/s para km/h
                analysis);
    }

    public String getActivityInsightFromEntity(ActivityEntity entity) {
        if (entity.getMinuteDetails() == null || entity.getMinuteDetails().isEmpty()) {
            return "Erro: Dados de telemetria insuficientes para gerar análise.";
        }

        List<StravaActivity.MinuteAnalysis> analysis = entity.getMinuteDetails().stream()
                .map(m -> new StravaActivity.MinuteAnalysis(m.getMinute(), m.getAverageHeartRate(), m.getMaxHeartRate(), m.getZone(), m.getAverageElevation(), m.getAverageCadence()))
                .toList();

        // Cálculo seguro da velocidade média (km/h) para evitar divisão por zero
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

        // 1. Extrai e salva a prescrição no banco de dados
        extractAndSavePrescription(activityId, result);

        // 2. Remove o bloco XML do texto que será enviado ao usuário
        String cleanResult = removeXmlBlock(result);

        return sanitizeOutput(cleanResult);
    }

    private String buildProfessionalPrompt(String name, Double distance, ZonedDateTime date, Double averageSpeed, List<StravaActivity.MinuteAnalysis> analysis, String proximoTreinoData) {
        String scientificContext = knowledgeService.getScientificContext(); // Recupera o material científico
        log.info("[KNOWLEDGE] Contexto científico enviado para IA: {} caracteres.", scientificContext != null ? scientificContext.length() : 0);
        if (scientificContext == null || scientificContext.isBlank()) {
            log.error("ALERTA: Base de conhecimento (studySettings) está VAZIA no banco de dados!");
            scientificContext = "Use as diretrizes gerais de San-Millán para eficiência mitocondrial.";
        }

        // --- BUSCA HISTÓRICO PARA CONTEXTO DE MÉDIAS (ÚLTIMOS 10 TREINOS) ---
        List<ActivityEntity> historico = activityRepository.findTop10ByOrderByStartDateDesc();

        // Segunda camada de proteção: Força a ordenação no Java para garantir que o Cloud Run não se confunda
        historico = historico.stream()
                .sorted(Comparator.comparing(ActivityEntity::getStartDate).reversed())
                .toList();

        log.info("[AI] Histórico recuperado: {} atividades para cálculo de médias.", historico.size());

        double vo2Medio = 0;
        double fcMaxMedia = 0;
        double fcMedioDasMedias = 0;
        double paceMedioSegundos = 0;

        if (!historico.isEmpty()) {
            // Log em nível INFO para que possamos validar a ordenação no painel do Cloud Run
            String datasHistorico = historico.stream()
                    .map(ActivityEntity::getStartDate)
                    .collect(Collectors.joining(", "));
            log.info("[AI] Datas do histórico processadas (ordem DESC): [{}]", datasHistorico);

            fcMaxMedia = historico.stream()
                    .mapToDouble(a -> a.getMaxHeartRate() != null ? a.getMaxHeartRate() : hrMaxConfig)
                    .average().orElse(0);
            
            fcMedioDasMedias = historico.stream()
                    .mapToDouble(a -> a.getAverageHeartRate() != null ? a.getAverageHeartRate() : 0)
                    .average().orElse(0);

            vo2Medio = historico.stream()
                    .mapToDouble(a -> 15.3 * ((a.getMaxHeartRate() != null ? a.getMaxHeartRate() : hrMaxConfig) / (double) hrResting))
                    .average().orElse(0);

            paceMedioSegundos = historico.stream()
                    .filter(a -> a.getDistanceKm() != null && a.getDistanceKm() > 0 && a.getTotalTimeMinutes() != null)
                    .mapToDouble(a -> (a.getTotalTimeMinutes() * 60.0) / a.getDistanceKm())
                    .average().orElse(0);
        }

        // Garante que a data exibida no cabeçalho seja o horário local de SP
        String dataFormatada = date.format(BRAZIL_FORMATTER);
        double safeDistance = (distance != null) ? distance : 0.0;
        String paceFormatted = (averageSpeed != null && averageSpeed > 0) ? formatSpeedToPace(averageSpeed) : "N/A";
        
        // --- CÁLCULO DE MÉTRICAS PARA O MOTOR DE CLASSIFICAÇÃO ---
        double fcMedia = analysis.stream().mapToDouble(StravaActivity.MinuteAnalysis::getAverageHeartRate).average().orElse(0.0);
        double fcMax = analysis.stream().mapToDouble(StravaActivity.MinuteAnalysis::getMaxHeartRate).max().orElse(0.0);
        int duracao = analysis.size();
        
        // Cálculo de Desvio Padrão (Estabilidade)
        double variance = analysis.stream().mapToDouble(m -> Math.pow(m.getAverageHeartRate() - fcMedia, 2)).average().orElse(0.0);
        double stdDev = Math.sqrt(variance);

        // Cálculo de Zona Predominante (Moda das zonas registradas)
        int zonaPredominante = analysis.stream()
                .map(StravaActivity.MinuteAnalysis::getZone)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(0);

        // Distribuição de Zonas
        long z2Count = analysis.stream().filter(m -> m.getZone() == 2).count();
        double z2Percent = (duracao > 0) ? (z2Count * 100.0) / duracao : 0.0;

        // Tendência de FC (Primeira vs Segunda Metade)
        double firstHalf = analysis.stream().limit(duracao / 2).mapToDouble(StravaActivity.MinuteAnalysis::getAverageHeartRate).average().orElse(fcMedia);
        double secondHalf = analysis.stream().skip(duracao / 2).mapToDouble(StravaActivity.MinuteAnalysis::getAverageHeartRate).average().orElse(fcMedia);
        String comportamento = (secondHalf > firstHalf * 1.05) ? "subindo gradualmente (drift)" : "predominantemente estável";

        // --- DETECÇÃO DO TIPO DE INTENSIDADE DO TREINO ---
        String workoutIntensityType;
        double fcMaxPercentage = (fcMax / hrMaxConfig) * 100; // Percentual da FC Máx configurada

        // Heurística: Alta intensidade se alta variabilidade de FC (stdDev alto) E FC máxima atingida alta
        if (stdDev > 8.0 && fcMaxPercentage > 90.0) { // Ajuste os thresholds conforme sua necessidade
            workoutIntensityType = "ALTA_INTENSIDADE (Intervalado/Picos)";
        } else if (stdDev > 5.0 && fcMaxPercentage > 80.0) { // Moderada variabilidade e esforço
            workoutIntensityType = "MÉDIA_INTENSIDADE (Tempo Run/Fartlek)";
        } else {
            workoutIntensityType = "BAIXA_INTENSIDADE (Contínuo/Zona 2)";
        }

        // Cálculo de VO2 Max Estimado (Fórmula de Uth-Sørensen)
        double vo2MaxEstimado = 15.3 * ((double) hrMaxConfig / hrResting);
        
        double ganhoAlt = analysis.stream().mapToDouble(StravaActivity.MinuteAnalysis::getAverageElevation).max().orElse(0.0) - 
                          analysis.stream().mapToDouble(StravaActivity.MinuteAnalysis::getAverageElevation).min().orElse(0.0);

        StringBuilder sb = new StringBuilder();
        
        sb.append("SISTEMA: Motor de Classificação Fisiológica StravaFit.\n");
        sb.append("ATIVIDADE: ").append(name).append("\n"); // Agora o parâmetro 'name' é utilizado no prompt
        sb.append(String.format("PERFIL DO ATLETA: %d anos | FC Máx: %d | FC Repouso: %d\n", idadeAtleta, hrMaxConfig, hrResting));
        sb.append("REGRA: Retorne EXATAMENTE o texto do campo 'Diagnóstico Clínico-Esportivo da IA' do cenário identificado no arquivo abaixo, adaptando o tom para a idade do atleta.\n\n");

        sb.append("--- BASE DE CONHECIMENTO (studySettings) ---\n");
        sb.append(scientificContext).append("\n\n");

        // --- BUSCA PLANEJAMENTO PARA O DIA DA ATIVIDADE (PLANO VS REAL) ---
        LocalDate dataTreino = date.toLocalDate();
        workoutPrescriptionRepository.findByScheduledDate(dataTreino).ifPresent(plano -> sb.append("--- TREINO PROGRAMADO PARA ESTA DATA (REFERÊNCIA DE COMPARAÇÃO) ---\n")
              .append("- Tipo Planejado: ").append(plano.getType()).append("\n")
              .append("- Duração/Volume: ").append(plano.getDuration()).append("\n")
              .append("- Intensidade Alvo: ").append(plano.getIntensity()).append("\n")
              .append("- Foco Técnico: ").append(plano.getFocus()).append("\n\n")
              .append("INSTRUÇÃO CRÍTICA DE COMPARAÇÃO: Este era o objetivo para hoje. No seu 'Diagnóstico Fisiológico', ")
              .append("determine explicitamente se o atleta seguiu o plano ou se 'furou' a planilha (ex: correu forte em dia de Z2).\n\n"));
        sb.append("--- DADOS DO TREINO ATUAL ---\n");
        sb.append(String.format("- Tempo Total de Treino: %d minutos\n", duracao));
        sb.append(String.format("- Frequência Cardíaca Média: %.0f bpm\n", fcMedia));
        sb.append(String.format("- Frequência Cardíaca Máxima: %.0f bpm\n", fcMax));
        sb.append(String.format("- Percentual em Zona 2: %.1f%%\n", z2Percent));
        sb.append(String.format("- VO2 Max Estimado (Perfil): %.1f ml/kg/min\n", vo2MaxEstimado));
        sb.append(String.format("- Comportamento da Frequência Cardíaca: %s\n", comportamento));
        sb.append(String.format("- Desvio Padrão da FC: %.1f bpm\n", stdDev));
        sb.append(String.format("- Altimetria: %.0f m | Pace Médio: %s\n", ganhoAlt, paceFormatted));
        sb.append(String.format("- Ritmo de Corrida (Pace Médio): %s\n", paceFormatted));
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
            sb.append(String.format("%d:%.0f/%.0fm/%.0f | ", 
                    m.getMinute(), 
                    m.getAverageHeartRate(), 
                    m.getAverageElevation(), 
                    m.getAverageCadence()));
        }
        
        sb.append("\n\nPRÓXIMO TREINO: ").append(proximoTreinoData);
        
        return sb.toString();
    }

    private void extractAndSavePrescription(Long activityId, String rawAiResponse) {
        try {
            int startTag = rawAiResponse.indexOf("<prescription_data>");
            int endTag = rawAiResponse.indexOf("</prescription_data>");

            if (startTag != -1 && endTag > startTag) {
                String xml = rawAiResponse.substring(startTag, endTag + 20);
                
                WorkoutPrescriptionEntity prescription = WorkoutPrescriptionEntity.builder()
                        .activityId(activityId)
                        .scheduledDate(LocalDate.parse(extractTagValue(xml, "scheduled_date")))
                        .type(extractTagValue(xml, "type"))
                        .duration(extractTagValue(xml, "duration"))
                        .intensity(extractTagValue(xml, "intensity"))
                        .focus(extractTagValue(xml, "focus"))
                        .paceTarget(extractTagValue(xml, "method")) // Mapeando o método para o campo de notas/ritmo
                        .build();

                workoutPrescriptionRepository.save(prescription);
                log.info("[DB] Prescrição automatizada salva para a data: {}", prescription.getScheduledDate());
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
        // Remove asteriscos e hashtags comuns de Markdown
        return text.replaceAll("[*#]", "")
                   .trim();
    }

    private String formatSecondsToPace(double totalSeconds) {
        if (totalSeconds <= 0) return "N/A";
        long minutes = (long) (totalSeconds / 60);
        long seconds = (long) (totalSeconds % 60);
        return String.format("%d:%02d", minutes, seconds);
    }

    private String formatSpeedToPace(Double speedKmH) {
        if (speedKmH == null || speedKmH == 0) return "N/A";
        double totalSeconds = 3600 / speedKmH; // Segundos por km
        return formatSecondsToPace(totalSeconds) + " min/km";
    }

    private String calcularProximaDataTreino(ZonedDateTime date) {
        LocalDate activityDate = date.toLocalDate();
        LocalDate today = LocalDate.now(ZONE_SP);

        // Ponto de partida: se o treino analisado é antigo, começamos a busca a partir de hoje.
        // Caso contrário, começamos a partir da data do treino.
        LocalDate baseDate = activityDate.isBefore(today) ? today : activityDate;

        // O próximo treino sempre será, no mínimo, amanhã em relação à data base
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
            // Blindagem para Cloud Run: Priorizamos o horário "nominal" para evitar o shift de fuso.
            // Pegamos apenas os primeiros 19 caracteres (YYYY-MM-DDTHH:mm:ss) e fixamos no fuso de SP.
            String localPart = dateStr.length() >= 19 ? dateStr.substring(0, 19) : dateStr;
            return LocalDateTime.parse(localPart.replace(" ", "T"), DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(ZONE_SP);
        } catch (Exception e) {
            try {
                // Fallback: Tenta parsear como data simples (YYYY-MM-DD)
                log.warn("[DATE] Falha ao parsear data completa, tentando data simples: {}", dateStr);
                return LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(ZONE_SP);
            } catch (Exception ex) {
                try {
                    // Último recurso: parser genérico do ZonedDateTime
                    return ZonedDateTime.parse(dateStr).withZoneSameLocal(ZONE_SP);
                } catch (Exception exc) {
                    // Se tudo falhar, retorna o horário atual
                    return ZonedDateTime.now(ZONE_SP);
                }
            }
        }
    }
}
