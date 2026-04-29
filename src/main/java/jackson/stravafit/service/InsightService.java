package jackson.stravafit.service;

import jackson.stravafit.client.GeminiClient;
import jackson.stravafit.model.StravaActivity;
import jackson.stravafit.model.ActivityEntity;
import jackson.stravafit.model.MinuteAnalysisEntity;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class InsightService {

    private final GeminiClient geminiClient;

    public InsightService(GeminiClient geminiClient) {
        this.geminiClient = geminiClient;
    }

    public String getActivityInsight(StravaActivity activity, List<StravaActivity.MinuteAnalysis> analysis) {
        String proximoTreinoData = calcularProximaDataTreino(activity.startDateLocal());
        String prompt = buildProfessionalPrompt(activity.name(), activity.distanceKm(), activity.startDateLocal(), analysis, proximoTreinoData);
        return geminiClient.getInsight(prompt);
    }

    public String getActivityInsightFromEntity(ActivityEntity entity) {
        List<StravaActivity.MinuteAnalysis> analysis = entity.getMinuteDetails().stream()
                .map(m -> new StravaActivity.MinuteAnalysis(m.getMinute(), m.getAverageHeartRate(), m.getMaxHeartRate(), m.getZone(), m.getAverageElevation(), m.getAverageCadence()))
                .toList();

        String proximoTreinoData = calcularProximaDataTreino(entity.getStartDate());
        String prompt = buildProfessionalPrompt(entity.getName(), entity.getDistanceKm(), entity.getStartDate(), analysis, proximoTreinoData);
        return geminiClient.getInsight(prompt);
    }

    private String buildProfessionalPrompt(String name, Double distance, String startDate, List<StravaActivity.MinuteAnalysis> analysis, String proximaData) {
        DateTimeFormatter parser = DateTimeFormatter.ISO_INSTANT;
        ZonedDateTime date = ZonedDateTime.parse(startDate, parser.withZone(ZoneId.of("America/Sao_Paulo")));
        String dataFormatada = date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));

        StringBuilder sb = new StringBuilder();

        // --- INSTRUÇÃO DE SISTEMA / CONTEXTO ---
        sb.append("VOCÊ É UM ESPECIALISTA EM FISIOLOGIA DO EXERCÍCIO E TREINADOR DE ALTA PERFORMANCE, ESPECIALIZADO EM TREINAMENTO DE BASE AERÓBICA (MAF) E SAÚDE MITOCONDRIAL. SUA MISSÃO É ANALISAR OS DADOS DE TREINO DO USUÁRIO E FORNECER FEEDBACKS TÉCNICOS BASEADOS EM CIÊNCIA.\n\n");
        sb.append("OBJETIVO CENTRAL: Otimizar a Eficiência Cardiorrespiratória e Metabólica para ganho de performance em corridas de longa distância (10km+).\n");
        sb.append("METAS ESPECÍFICAS:\n");
        sb.append("- Saúde Mitocondrial: Aumentar a densidade mitocondrial via treinos de Zona 2 (Z2), visando maior oxidação de gordura como fonte de energia e poupando glicogênio.\n");
        sb.append("- Capacidade Respiratória (VO2 Max): Melhorar a captação e utilização de oxigênio através de treinos de intervalo (tiros).\n");
        sb.append("- Economia de Corrida: Reduzir o custo energético (manter o mesmo pace com menor esforço cardíaco).\n");
        sb.append("- Recuperação Autonômica: Reduzir a FC de repouso e monitorar a VFC (Variabilidade da Frequência Cardíaca) como indicador de adaptação ao volume de treino.\n\n");

        sb.append("TAREFAS DE ANALISE INICIAIS (CONSIDERAÇÕES DO ANALISTA):\n");
        sb.append("- Analise de Atividade Recente: Extrair métricas de Pace, BPM, BPM médio, BPM máximo, Zonas de Frequencia cardíaca (minuto a minuto), Frequencia cardíaca em repouso (se disponível), Altimetria e Cadência.\n");
        sb.append("- Identificação de Tendências (Eficiência Aeróbica): Avaliar a relação direta entre Ritmo (Pace) e Frequência Cardíaca. Verificar se o usuário está conseguindo sustentar um Pace mais baixo dentro da mesma zona de esforço (especialmente Z2).\n");
        sb.append("- Auditoria de Volume e Saúde: Validar se o volume semanal e a intensidade da sessão são condizentes com os objetivos de saúde respiratória e biogênese mitocondrial.\n");
        sb.append("- Cálculo de Deriva Cardíaca (Cardiac Drift): Comparar a FC média da primeira metade do treino com a segunda metade (mantendo o ritmo constante). Se houver um desacoplamento superior a 5%, diagnosticar fadiga mitocondrial ou necessidade de maior base aeróbica.\n");
        sb.append("- Normalização por Altimetria (Análise de GAP): Aplicar o Grade Adjusted Pace (Pace Ajustado à Inclinação) para neutralizar o impacto das subidas. O objetivo é garantir que a análise metabólica não seja penalizada pelo terreno irregular.\n");
        sb.append("- Verificação de Limiar de Lactato: Identificar o ponto de inflexão cardíaca durante treinos de intensidade para medir a tolerância ao acúmulo de metabólitos e a capacidade de remoção via metabolismo oxidativo.\n");
        sb.append("- Avaliação de Recuperação e FC de Repouso: Cruzar os dados de desempenho com a Frequência Cardíaca de Repouso (se disponível), buscando sinais de sobrecarga ou adaptação positiva do sistema nervoso autônomo.\n\n");

        // --- INSTRUÇÃO DE FORMATAÇÃO: NÃO USE ASTERISCOS (*) OU SÍMBOLOS DE MARKDOWN. USE APENAS TÍTULOS EM LETRAS MAIÚSCULAS E TRAÇOS PARA SEPARAR SEÇÕES ---
        sb.append("--- INSTRUÇÃO DE FORMATAÇÃO: O RETORNO DEVE SER COESO, ORGANIZADO E BEM FORMATADO, UTILIZANDO TÍTULOS E SUBTÍTULOS EM LETRAS MAIÚSCULAS. NÃO USE ASTERISCOS OU OUTROS SÍMBOLOS DE MARKDOWN. ---\n\n");
        
        sb.append("DATA E HORA DO TREINO: ").append(dataFormatada).append("\n\n");

        sb.append("ETAPA 2: ANALISE DO TREINO ATUAL\n");
        sb.append("PARAMETROS DE REFERENCIA:\n");
        sb.append("- Zona Alvo Z2: 127 - 137 BPM.\n");
        sb.append("- Teto Aeróbico: 138 BPM.\n\n");
        sb.append("DADOS DO TREINO: ").append(name).append(" | ").append(String.format("%.1f km", distance)).append("\n");
        
        sb.append("SERIE TEMPORAL (Min: BPM/Alt/Cad) - Amostra a cada 2min:\n");
        for (int i = 0; i < analysis.size(); i += 2) {
            StravaActivity.MinuteAnalysis m = analysis.get(i);
            sb.append(String.format("%d:%.0f/%.0fm/%.0f | ", m.minute(), m.averageHeartRate(), m.averageElevation(), m.averageCadence()));
        }

        sb.append("\n\nTAREFAS DE ANALISE TECNICA:\n");
        sb.append("- Auditoria de Zonas (Time in Zone): Calcular o tempo total e o percentual da atividade acima de 138 BPM. Identificar o 'ponto de transição' (em qual quilômetro ou minuto o controle da Z2 foi perdido).\n");
        sb.append("- Calculo do Desacoplamento Aerobico (Cardiac Drift): Comparar a relação Pace/BPM da primeira metade do treino com a segunda metade. Critério: Se o BPM subiu mais de 5% mantendo o mesmo Pace, sinalizar como 'Deriva Cardíaca', indicando que a base mitocondrial ainda não sustenta o volume atual.\n");
        sb.append("- Analise de Eficiencia Relativa (Pace vs. Esforco): Calcular o Pace Médio em Z2 desta atividade e comparar com a média histórica. Se o Pace em Z2 estiver mais rápido que o anterior com o mesmo BPM, destacar como 'Sucesso na Biogênese Mitocondrial'.\n");
        sb.append("- Correcao por Altimetria e GAP: Cruzar picos de BPM com o ganho de elevação. O Fator Ladeira: Utilizar o Grade Adjusted Pace (GAP) para validar se o esforço foi justificado pela subida. Se o BPM subiu em terreno plano sem aumento de cadência, rotular como 'Instabilidade Aeróbica' ou 'Fadiga Térmica/Fisiológica'.\n");
        sb.append("- Analise de Pico e Recuperacao: Identificar o BPM Máximo e a rapidez com que a FC retorna à Z2 após um esforço em subida (Capacidade de Recuperação).\n");
        sb.append("- Indicadores de Economia de Corrida: Correlacionar a Cadência com o BPM. Verificar se uma cadência mais alta (passadas mais curtas e frequentes) está ajudando a manter o BPM sob controle na Z2.\n\n");

        sb.append("ETAPA 3: FEEDBACK E PRESCRICAO TECNICA\n");
        sb.append("1. Diagnostico de Eficiencia Metabolica (Realista e Sincero): Status da Z2 (Natural ou Forcado), Analise de Fadiga Residual, O Veredito (Feedback direto sobre a disciplina).\n");
        sb.append("2. Planejamento Adaptativo (Agenda: Terças, Quintas e Sábados às 05:30):\n");
        sb.append("- Prescricao do Proximo Treino para ").append(proximaData).append(": Definir Distância (km), Pace Alvo (min/km) e Método (ex: Rodagem Contínua, Progressivo ou Intervalado).\n");
        sb.append("3. Bloco de Estimulo a Biogenese Mitocondrial (Iniciacao HIIT): Frequência (1x por semana se treino leve), Protocolo de Entrada (series curtas com foco em VO2 Máximo, especificando tempo de descanso ativo), Objetivo.\n");
        sb.append("4. Recomendacao Nutricional e de Suplementacao (Contextual): Sugerir o ajuste no timing da Creatina e Whey (pos-treino) ou do Omega 3 e Curcuma (anti-inflamatorios) com base na intensidade do esforco relatado na Etapa 2.\n");

        return sb.toString();
    }

    private String calcularProximaDataTreino(String dataAtualStr) {
        LocalDate hoje = ZonedDateTime.parse(dataAtualStr).toLocalDate();
        LocalDate proximo = hoje.plusDays(1);
        while (proximo.getDayOfWeek() != DayOfWeek.TUESDAY && proximo.getDayOfWeek() != DayOfWeek.THURSDAY && proximo.getDayOfWeek() != DayOfWeek.SATURDAY) {
            proximo = proximo.plusDays(1);
        }
        return proximo.format(DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy"));
    }
}
