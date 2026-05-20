package jackson.stravafit.service;

import jackson.stravafit.client.GeminiClient;
import jackson.stravafit.model.StravaActivity;
import jackson.stravafit.model.ActivityEntity;
import jackson.stravafit.repository.ActivityRepository;
import jackson.stravafit.service.KnowledgeService; // Importar KnowledgeService
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InsightService {

    private final GeminiClient geminiClient;
    private final ActivityRepository activityRepository;
    private final KnowledgeService knowledgeService; // Injetar KnowledgeService

    @Value("${atleta.hr-max:173}")
    private int hrMaxConfig;

    @Value("${atleta.hr-resting:53}")
    private int hrResting;

    @Value("${atleta.idade:47}")
    private int idadeAtleta;

    private static final ZoneId ZONE_SP = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter BRAZIL_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter NEXT_WORKOUT_FORMATTER = DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy");

    private static final int TETO_Z2_PADRAO = 138;

    private static final String NO_MARKDOWN_INSTRUCTION = 
        "--- REGRAS DE SAÍDA: Use apenas texto puro com quebras de linha e emojis. PROIBIDO o uso de blocos de código (```). ---";
    
    public String getActivityInsight(StravaActivity activity, List<StravaActivity.MinuteAnalysis> analysis) {
        // Ajustado para usar getters, tratando o acesso privado relatado
        return generateInsight(
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
        
        return generateInsight(entity.getName(), entity.getDistanceKm(), entity.getStartDate(), averageSpeed, analysis);
    }

    private String generateInsight(String name, Double distance, String dateStr, Double averageSpeed, List<StravaActivity.MinuteAnalysis> analysis) {
        ZonedDateTime activityDate = parseToZonedDateTime(dateStr);
        String proximoTreinoData = calcularProximaDataTreino(activityDate);
        String prompt = buildProfessionalPrompt(name, distance, activityDate, averageSpeed, analysis, proximoTreinoData);
        return sanitizeOutput(geminiClient.getInsight(prompt));
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
        
        double vo2Medio = 0;
        double fcMaxMedia = 0;
        double fcMedioDasMedias = 0;
        double paceMedioSegundos = 0;

        if (!historico.isEmpty()) {
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

        String dataFormatada = date.format(BRAZIL_FORMATTER);
        double safeDistance = (distance != null) ? distance : 0.0;
        String paceFormatted = (averageSpeed != null && averageSpeed > 0) ? formatSpeedToPace(averageSpeed) : "N/A";
        
        // --- CÁLCULO DE MÉTRICAS PARA O MOTOR DE CLASSIFICAÇÃO ---
        double fcMedia = analysis.stream().mapToDouble(m -> m.getAverageHeartRate()).average().orElse(0.0);
        double fcMax = analysis.stream().mapToDouble(m -> m.getMaxHeartRate()).max().orElse(0.0);
        int duracao = analysis.size();
        
        // Cálculo de Desvio Padrão (Estabilidade)
        double variance = analysis.stream().mapToDouble(m -> Math.pow(m.getAverageHeartRate() - fcMedia, 2)).average().orElse(0.0);
        double stdDev = Math.sqrt(variance);

        // Distribuição de Zonas
        long z2Count = analysis.stream().filter(m -> m.getZone() == 2).count();
        double z2Percent = (duracao > 0) ? (z2Count * 100.0) / duracao : 0.0;

        // Tendência de FC (Primeira vs Segunda Metade)
        double firstHalf = analysis.stream().limit(duracao / 2).mapToDouble(m -> m.getAverageHeartRate()).average().orElse(fcMedia);
        double secondHalf = analysis.stream().skip(duracao / 2).mapToDouble(m -> m.getAverageHeartRate()).average().orElse(fcMedia);
        String comportamento = (secondHalf > firstHalf * 1.05) ? "subindo gradualmente (drift)" : "predominantemente estável";

        // Cálculo de VO2 Max Estimado (Fórmula de Uth-Sørensen)
        double vo2MaxEstimado = 15.3 * ((double) hrMaxConfig / hrResting);
        
        double ganhoAlt = analysis.stream().mapToDouble(m -> m.getAverageElevation()).max().orElse(0.0) - 
                          analysis.stream().mapToDouble(m -> m.getAverageElevation()).min().orElse(0.0);

        StringBuilder sb = new StringBuilder();
        
        sb.append("SISTEMA: Motor de Classificação Fisiológica StravaFit.\n");
        sb.append(String.format("PERFIL DO ATLETA: %d anos | FC Máx: %d | FC Repouso: %d\n", idadeAtleta, hrMaxConfig, hrResting));
        sb.append("REGRA: Retorne EXATAMENTE o texto do campo 'Diagnóstico Clínico-Esportivo da IA' do cenário identificado no arquivo abaixo, adaptando o tom para a idade do atleta.\n\n");

        sb.append("--- BASE DE CONHECIMENTO (studySettings) ---\n");
        sb.append(scientificContext).append("\n\n");

        sb.append("CONTEXTO DO USUÁRIO (MÉDIAS DOS ÚLTIMOS 10 TREINOS NO MYSQL):\n")
          .append("- VO2 Máx Médio Atual: ").append(String.format("%.1f", vo2Medio)).append(" ml/kg/min\n")
          .append("- FC Máxima Média Registrada: ").append((int)fcMaxMedia).append(" bpm\n")
          .append("- FC Média Geral das Sessões: ").append((int)fcMedioDasMedias).append(" bpm\n")
          .append("- Ritmo (Pace) Médio de Corrida: ").append(formatSecondsToPace(paceMedioSegundos)).append(" min/km\n\n");

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
        sb.append("------------------------------\n\n");

        sb.append("TAREFA: Analise os DADOS DO TREINO ATUAL correlacionando os indicadores de Frequência Cardíaca Média, ")
                .append("Altimetria e Ritmo de Corrida. Compare os resultados, identifique o CENÁRIO correto da BASE e ")
                .append("Classifique o treino com base nos modelos de San-Millán (2017), Casanova (2023) ")
                .append("e no modelo de distribuição de intensidade de Stephen Seiler (2010) focado em VO2 máx. ")
                .append("monte o retorno estritamente no formato estruturado abaixo (SEM ASTERISCOS nos títulos):\n\n")

                .append("DIAGNÓSTICO CLÍNICO-ESPORTIVO:\n")
                .append("[Transcreva exatamente o texto do diagnóstico do cenário identificado na BASE]\n\n")
                .append("RESUMO DIDÁTICO:\n")
                .append("[Gere uma única linha curta, direta e muito didática resumindo o impacto prático do cenário, em linguagem de corredor].\n\n")
                .append("ANÁLISE DE RITMO E COMPORTAMENTO CARDÍACO:\n")
                .append("[Comente brevemente como o Ritmo de Corrida (Pace) e a Altimetria influenciaram os Batimentos (BPM). ")
                .append("Identifique e cite explicitamente qual foi a zona cardíaca predominante praticada no treino, ")
                .append("justificando se o ritmo estava estável ou se o coração subiu de rotação de forma desproporcional].")

                .append("CONCLUSÃO DA IA (TRADUÇÃO CIENTÍFICA):\n")
                .append("[Gere uma conclusão dinâmica, curta e com linguagem muito fácil e direta, adaptada ao cenário identificado. ")
                .append("Explique a importância desse treino para o VO2 máx do usuário: se ele serviu para construir a base celular ")
                .append("ou se serviu para desafiar o teto do indicador. Use as metáforas de 'fábricas de energia', 'combustível limpo' ")
                .append("ou 'limpeza de lixo celular' de acordo com o artigo correspondente].")
                .append("Use metáforas simples baseadas no arquivo (como 'fábricas de energia', 'combustível limpo vs sujo' ou 'sobrecarga'). ")
                .append("Explique de forma prática o impacto do treino na saúde celular do usuário e dê um conselho claro de ação ")
                .append("apoiado nos conceitos de San-Millán ou Casanova et al.](SEM ASTERISCOS nos títulos):\n\n");
        sb.append("🏃‍♂️ StravaFit IA - Análise de Eficiência Metabólica\n\n");
        sb.append("📌 Cenário Detectado: [Título]\n");
        sb.append("📊 Métricas: ").append(duracao).append(" min | FC Média: ").append((int)fcMedia)
                .append(" bpm | FC Máx: ").append((int)fcMax).append(" bpm | VO2 Max: ")
                .append(String.format("%.1f", vo2MaxEstimado)).append(" | Pace: ").append(paceFormatted).append("\n\n");
        sb.append("🩺 Diagnóstico Fisiológico:\n[Diagnóstico do Estudo]\n\n");
        sb.append("PRÓXIMO TREINO: ").append(proximoTreinoData).append("\n\n");
        sb.append("CONTEXTO DO USUÁRIO (MÉDIAS DOS ÚLTIMOS 10 TREINOS):\n")
                .append("- VO2 Máx Médio Atual: ").append(String.format("%.1f", vo2Medio)).append(" ml/kg/min\n")
                .append("- FC Máxima Média Registrada: ").append((int) fcMaxMedia).append(" bpm\n")
                .append("- FC Média Geral das Sessões: ").append((int) fcMedioDasMedias).append(" bpm\n")
                .append("- Ritmo (Pace) Médio de Corrida: ").append(formatSecondsToPace(paceMedioSegundos)).append(" min/km\n\n")

                .append("DIRETRIZ DE SELEÇÃO DE TIROS:\n")
                .append("Compare os dados acima com a MATRIZ DE PROGRESSÃO DE INTENSIDADE. ")
                .append("Se o treino atual foi Zona 2 e o usuário precisa de tiros, selecione o NÍVEL adequado de progressão ")
                .append("(iniciando pelo Nível 1 se o histórico não mostrar treinos intensos recentes) e monte a prescrição ")
                .append("calculando os ritmos alvo baseados no Pace Médio dele (ex: os tiros devem ser mais rápidos que o Pace Médio de 10 treinos).\n");
        

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
        long minutes = (long) (totalSeconds / 60);
        long seconds = (long) (totalSeconds % 60);
        return String.format("%d:%02d min/km", minutes, seconds);
    }

    private String calcularProximaDataTreino(ZonedDateTime date) {
        // O método retorna String, garantindo compatibilidade com o que é esperado no prompt
        LocalDate hoje = date.toLocalDate();
        LocalDate proximo = hoje.plusDays(1);
        while (proximo.getDayOfWeek() != DayOfWeek.TUESDAY && proximo.getDayOfWeek() != DayOfWeek.THURSDAY && proximo.getDayOfWeek() != DayOfWeek.SATURDAY) {
            proximo = proximo.plusDays(1);
        }
        return proximo.atStartOfDay(ZONE_SP).format(NEXT_WORKOUT_FORMATTER);
    }

    private ZonedDateTime parseToZonedDateTime(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return ZonedDateTime.now(ZONE_SP);
        }

        try {
            // Tenta parsear como ISO_INSTANT (com Z no final)
            return ZonedDateTime.parse(dateStr, DateTimeFormatter.ISO_INSTANT.withZone(ZONE_SP));
        } catch (Exception e) {
            // Se falhar, tenta parsear como ISO_DATE_TIME (sem Z, com fuso horário)
            try {
                String localPart = dateStr.length() >= 19 ? dateStr.substring(0, 19) : dateStr;
                return LocalDateTime.parse(localPart, DateTimeFormatter.ISO_DATE_TIME).atZone(ZONE_SP);
            } catch (Exception ex) {
                // Última tentativa: se for apenas data, adiciona um horário padrão
                try {
                    return LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(ZONE_SP);
                } catch (Exception exc) {
                    // Se tudo falhar, retorna o horário atual
                    return ZonedDateTime.now(ZONE_SP);
                }
            }
        }
    }
}
