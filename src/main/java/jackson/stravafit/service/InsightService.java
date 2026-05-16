package jackson.stravafit.service;

import jackson.stravafit.client.GeminiClient;
import jackson.stravafit.model.StravaActivity;
import jackson.stravafit.model.ActivityEntity;
import jackson.stravafit.model.MinuteAnalysisEntity; // Importar MinuteAnalysisEntity
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class InsightService {

    private final GeminiClient geminiClient;

    private static final ZoneId ZONE_SP = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter BRAZIL_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter NEXT_WORKOUT_FORMATTER = DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy");
    private static final int TETO_Z2_PADRAO = 138;

    public InsightService(GeminiClient geminiClient) {
        this.geminiClient = geminiClient;
    }

    // Gerador de recomendação pré-treino baseado no sono
    public String getPreWorkoutRecommendation(String sleepQuality) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- INSTRUÇÃO DE FORMATAÇÃO: O RETORNO DEVE SER COESO, ORGANIZADO E BEM FORMATADO, UTILIZANDO TÍTULOS E SUBTÍTULOS EM LETRAS MAIÚSCULAS. NÃO USE ASTERISCOS OU OUTROS SÍMBOLOS DE MARKDOWN. ---\n\n");
        sb.append("AVALIAÇÃO PRÉ-TREINO: CONDIÇÕES FISIOLÓGICAS\n\n");
        sb.append("SITUAÇÃO DO SONO: ").append(sleepQuality.toUpperCase()).append("\n\n");
        sb.append("TAREFA:\n");
        sb.append("Como um Especialista em Fisiologia, avalie esta qualidade de sono para um atleta que tem um treino de Zona 2 programado para hoje as 05:30 da manhã.\n");
        sb.append("Se o sono foi ruim ou muito ruim, sugira uma adaptação (redução de volume ou intensidade, ou até mesmo descanso total). Se o sono foi bom, reforce o plano.\n");
        sb.append("Forneça uma recomendação curta, direta e técnica.");
        
        return geminiClient.getInsight(sb.toString());
    }

    public String getActivityInsight(StravaActivity activity, List<StravaActivity.MinuteAnalysis> analysis) {
        // Ajustado para usar getters, tratando o acesso privado relatado
        return generateInsight(
                activity.getName(), 
                activity.getDistance() / 1000.0, 
                activity.getStartDateLocal(), 
                activity.getAverageSpeed(), 
                analysis);
    }

    public String getActivityInsightFromEntity(ActivityEntity entity) {
        if (entity.getMinuteDetails() == null || entity.getMinuteDetails().isEmpty()) {
            return "Erro: Dados de telemetria insuficientes para gerar análise.";
        }

        List<StravaActivity.MinuteAnalysis> analysis = entity.getMinuteDetails().stream()
                .map(m -> new StravaActivity.MinuteAnalysis(m.getMinute(), m.getAverageHeartRate(), m.getMaxHeartRate(), m.getZone(), m.getAverageElevation(), m.getAverageCadence()))
                .toList();

        // Como ActivityEntity não tem averageSpeed, vamos calcular um pace aproximado para o prompt
        Double averageSpeed = (entity.getDistanceKm() != null && entity.getTotalTimeMinutes() != null && entity.getTotalTimeMinutes() > 0) ?
                                entity.getDistanceKm() / (entity.getTotalTimeMinutes() / 60.0) : null; // km/h
        
        return generateInsight(entity.getName(), entity.getDistanceKm(), entity.getStartDate(), averageSpeed, analysis);
    }

    private String generateInsight(String name, Double distance, String dateStr, Double averageSpeed, List<StravaActivity.MinuteAnalysis> analysis) {
        ZonedDateTime activityDate = parseToZonedDateTime(dateStr);
        String proximoTreinoData = calcularProximaDataTreino(activityDate);
        String prompt = buildProfessionalPrompt(name, distance, activityDate, averageSpeed, analysis, proximoTreinoData);
        return geminiClient.getInsight(prompt);
    }

    private String buildProfessionalPrompt(String name, Double distance, ZonedDateTime date, Double averageSpeed, List<StravaActivity.MinuteAnalysis> analysis, String proximoTreinoData) {
        String dataFormatada = date.format(BRAZIL_FORMATTER);
        String paceFormatted = (averageSpeed != null && averageSpeed > 0) ? formatSpeedToPace(averageSpeed) : "N/A";

        StringBuilder sb = new StringBuilder();

        // --- INSTRUÇÃO DE SISTEMA / CONTEXTO ---
        sb.append("VOCÊ É UM ANALISTA DE PERFORMANCE DE ELITE, ESPECIALISTA EM FISIOLOGIA DO EXERCÍCIO E BIOGÊNESE MITOCONDRIAL. SUA MISSÃO É ANALISAR OS DADOS DE TREINO DO ATLETA E FORNECER FEEDBACKS TÉCNICOS BASEADOS EM CIÊNCIA.\n\n");
        sb.append("OBJETIVO CENTRAL: Otimizar a Eficiência Cardiorrespiratória e Metabólica para ganho de performance em corridas de longa distância (10km+).\n");
        sb.append("METAS ESPECÍFICAS:\n");
        sb.append("- Saúde Mitocondrial: Aumentar a densidade mitocondrial via treinos de Zona 2 (Z2), visando maior oxidação de gordura como fonte de energia e poupando glicogênio.\n");
        sb.append("- Capacidade Respiratória (VO2 Max): Melhorar a captação e utilização de oxigênio através de treinos de intervalo (tiros).\n");
        sb.append("- Economia de Corrida: Reduzir o custo energético (manter o mesmo pace com menor esforço cardíaco).\n");
        sb.append("- Recuperação Autonômica: Reduzir a FC de repouso e monitorar a VFC (Variabilidade da Frequência Cardíaca) como indicador de adaptação ao volume de treino.\n\n");

        sb.append("TAREFAS DE ANALISE INICIAIS (CONSIDERAÇÕES DO ANALISTA):\n");
        sb.append("- Análise de Atividade Recente: Extrair métricas de Pace, BPM, BPM médio, BPM máximo, Zonas de Frequencia cardíaca (minuto a minuto), Frequencia cardíaca em repouso (se disponível), Altimetria e Cadência.\n");
        sb.append("- Identificação de Tendências (Eficiência Aeróbica): Avaliar a relação direta entre Ritmo (Pace) e Frequência Cardíaca. Verificar se o usuário está conseguindo sustentar um Pace mais baixo dentro da mesma zona de esforço (especialmente Z2).\n");
        sb.append("- Auditoria de Volume e Saúde: Validar se o volume semanal e a intensidade da sessão são condizentes com os objetivos de saúde respiratória e biogênese mitocondrial.\n");
        sb.append("- Cálculo de Deriva Cardíaca (Cardiac Drift): Comparar a FC média da primeira metade do treino com a segunda metade (mantendo o ritmo constante). Se houver um desacoplamento superior a 5%, diagnosticar fadiga mitocondrial ou necessidade de maior base aeróbica.\n");
        sb.append("- Normalização por Altimetria (Análise de GAP): Aplicar o Grade Adjusted Pace (GAP) para neutralizar o impacto das subidas. O objetivo é garantir que a análise metabólica não seja penalizada pelo terreno irregular.\n");
        sb.append("- Verificação de Limiar de Lactato: Identificar o ponto de inflexão cardíaca durante treinos de intensidade para medir a tolerância ao acúmulo de metabólitos e a capacidade de remoção via metabolismo oxidativo.\n");
        sb.append("- Avaliação de Recuperação e FC de Repouso: Cruzar os dados de desempenho com a Frequência Cardíaca de Repouso (se disponível), buscando sinais de sobrecarga ou adaptação positiva do sistema nervoso autônomo.\n\n");

        // --- INSTRUÇÃO DE FORMATAÇÃO: O RETORNO DEVE SER COESO, ORGANIZADO E BEM FORMATADO, UTILIZANDO TÍTULOS E SUBTÍTULOS EM LETRAS MAIÚSCULAS. NÃO USE ASTERISCOS OU OUTROS SÍMBOLOS DE MARKDOWN. ---\n\n");
        
        sb.append("DATA E HORA DO TREINO: ").append(dataFormatada).append("\n\n");

        sb.append("ETAPA 1: ANALISE DO TREINO ATUAL\n");
        sb.append("DADOS DO TREINO: ").append(name).append(" | ").append(String.format("%.1f km", distance)).append(" | Pace Médio: ").append(paceFormatted).append("\n");
        sb.append("PARAMETROS DE REFERENCIA: Z2 (127 - 137 BPM), Teto 138 BPM.\n\n");
        
        sb.append("SERIE TEMPORAL (Min: BPM/Alt/Cad/Pace) - Amostra a cada 2min:\n");
        for (int i = 0; i < analysis.size(); i += 2) {
            StravaActivity.MinuteAnalysis m = analysis.get(i);
            // Para incluir o Pace por minuto, precisaríamos que o StravaActivity.MinuteAnalysis tivesse essa informação.
            // Ajustado para usar getters (getMinute, getAverageHeartRate, etc)
            sb.append(String.format("%d:%.0f/%.0fm/%.0f | ", 
                    m.getMinute(), 
                    m.getAverageHeartRate(), 
                    m.getAverageElevation(), 
                    m.getAverageCadence()));
        }

        sb.append("\n\nTAREFAS DE ANALISE TECNICA:\n");
        sb.append("- Auditoria de Zonas (Time in Zone): Calcular tempo/percentual acima de 138 BPM. Identificar o 'ponto de transição' (em qual quilômetro ou minuto o controle da Z2 foi perdido).\n");
        sb.append("- Calculo do Desacoplamento Aerobico (Cardiac Drift): Comparar a relação Pace/BPM da primeira metade do treino com a segunda metade. Critério: Se o BPM subiu mais de 5% mantendo o mesmo Pace, sinalizar como 'Deriva Cardíaca', indicando que a base mitocondrial ainda não sustenta o volume atual.\n");
        sb.append("- Analise de Eficiencia Relativa (Pace vs. Esforco): Calcular o Pace Médio em Z2 desta atividade e comparar com a média histórica. Se o Pace em Z2 estiver mais rápido que o anterior com o mesmo BPM, destacar como 'Sucesso na Biogênese Mitocondrial'.\n");
        sb.append("- Correcao por Altimetria e GAP: Cruzar picos de BPM com o ganho de elevação. O Fator Ladeira: Utilizar o Grade Adjusted Pace (GAP) para validar se o esforço foi justificado pela subida. Se o BPM subiu em terreno plano sem aumento de cadência, rotular como 'Instabilidade Aeróbica' ou 'Fadiga Térmica/Fisiológica'.\n");
        sb.append("- Analise de Pico e Recuperacao: Identificar o BPM Máximo e a rapidez com que a FC retorna à Z2 após um esforço em subida (Capacidade de Recuperação).\n");
        sb.append("- Indicadores de Economia de Corrida: Correlacionar a Cadência com o BPM. Verificar se uma cadência mais alta (passadas mais curtas e frequentes) está ajudando a manter o BPM sob controle na Z2.\n\n");

        sb.append("ETAPA 2: FEEDBACK E PRESCRICAO TECNICA\n");
        sb.append("1. Diagnostico de Eficiencia Metabolica (Realista e Sincero): Status da Z2 (Natural ou Forcado) e Analise de Fadiga Residual.\n");
        sb.append("2. Planejamento Adaptativo para ").append(proximoTreinoData).append(": Definir Distância, Pace Alvo e Método.\n");
        sb.append("3. Bloco de Estimulo a Biogenese Mitocondrial (HIIT): Prescrever se o treino atual foi leve.\n");
        sb.append("4. Recomendacao Nutricional Contextual: Ajuste de suplementação com base no esforço.\n");

        return sb.toString();
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
