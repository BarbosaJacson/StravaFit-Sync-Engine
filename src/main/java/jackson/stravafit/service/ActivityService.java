package jackson.stravafit.service;

import jackson.stravafit.client.StravaClient;
import jackson.stravafit.model.StravaActivity;
import jackson.stravafit.model.ActivityEntity;
import jackson.stravafit.model.MinuteAnalysisEntity;
import jackson.stravafit.repository.ActivityRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;

@Service
public class ActivityService {

    private final StravaClient stravaClient;
    private final ActivityRepository activityRepository;

    @Value("${atleta.hr-max}")
    private int hrMax;

    @Value("${atleta.hr-resting}")
    private int hrResting;

    public ActivityService(StravaClient stravaClient, ActivityRepository activityRepository) {
        this.stravaClient = stravaClient;
        this.activityRepository = activityRepository;
    }

    /**
     * Recupera atividades do Strava e filtra apenas aquelas que possuem monitoramento de batimento cardíaco válido.
     */
    public ActivityPageResponse getActivitiesWithHeartRate(String token, int page) {
        List<StravaActivity> allActivities = stravaClient.getActivities(token, page);

        List<StravaActivity> filtered = allActivities.stream()
                .filter(activity -> Boolean.TRUE.equals(activity.hasHeartRate()))
                .filter(activity -> {
                    String type = activity.sportType();
                    return "Run".equalsIgnoreCase(type) || "Walk".equalsIgnoreCase(type);
                })
                .filter(activity -> activity.averageHeartRate() != null && activity.averageHeartRate() > 0)
                .toList();

        return new ActivityPageResponse(filtered, allActivities.size());
    }

    public List<StravaActivity.HeartRateZone> getActivityZones(String token, Long id) {
        return stravaClient.getActivityZones(token, id);
    }

    public List<StravaActivity.ActivityStream> getActivityStreams(String token, Long id) {
        return stravaClient.getActivityStreams(token, id);
    }

    @Transactional
    public void saveActivity(StravaActivity activity, List<StravaActivity.MinuteAnalysis> minutes, String zone) {
        if (activity.id() == null || activityRepository.existsById(activity.id())) return;

        List<MinuteAnalysisEntity> minuteEntities = minutes.stream()
                .map(m -> MinuteAnalysisEntity.builder()
                        .minute(m.minute())
                        .averageHeartRate(m.averageHeartRate())
                        .maxHeartRate(m.maxHeartRate())
                        .zone(m.zone())
                        .averageElevation(m.averageElevation())
                        .averageCadence(m.averageCadence())
                        .build())
                .toList();

        ActivityEntity entity = ActivityEntity.builder()
                .id(activity.id())
                .name(activity.name())
                .startDate(activity.startDateLocal())
                .distanceKm(activity.distanceKm())
                .averageHeartRate(activity.averageHeartRate())
                .maxHeartRate(activity.maxHeartRate())
                .sportType(activity.sportType())
                .dominantZone(zone)
                .totalTimeMinutes(activity.elapsedTimeMinutes())
                .minuteDetails(new ArrayList<>(minuteEntities))
                .build();

        activityRepository.save(entity);
    }

    /**
     * Transforma dados de segundos em médias por minuto e identifica a zona cardíaca.
     */
    public List<StravaActivity.MinuteAnalysis> aggregateStreamsByMinute(
            List<StravaActivity.ActivityStream> streams, 
            List<StravaActivity.HeartRateZone> zones) {
        
        List<StravaActivity.MinuteAnalysis> analysis = new ArrayList<>();
        
        // Extração dos streams necessários
        List<Double> hrData = extractStream(streams, "heartrate");
        List<Double> altData = extractStream(streams, "altitude");
        List<Double> cadData = extractStream(streams, "cadence");

        if (hrData == null || hrData.isEmpty()) return analysis;

        for (int i = 0; i < hrData.size(); i += 60) {
            int end = Math.min(i + 60, hrData.size());
            List<Double> minuteSlice = hrData.subList(i, end);
            
            double avgHr = minuteSlice.stream().mapToDouble(d -> d).average().orElse(0.0);
            double maxHr = minuteSlice.stream().mapToDouble(d -> d).max().orElse(0.0);
            double avgAlt = getAverage(altData, i, end);
            double avgCad = getAverage(cadData, i, end);
            
            int minuteNumber = (i / 60) + 1;
            int zoneDetected = calculateKarvonenZone(avgHr);
            
            analysis.add(new StravaActivity.MinuteAnalysis(minuteNumber, avgHr, maxHr, zoneDetected, avgAlt, avgCad));
        }
        return analysis;
    }

    /**
     * Prepara o payload condensado para a API do Gemini.
     * Em vez de enviar milhares de pontos, enviamos o resumo estatístico.
     */
    public Map<String, Object> prepareGeminiPayload(StravaActivity activity, List<StravaActivity.MinuteAnalysis> analysis, String lastInsight) {
        return Map.of(
            "summary", activity,
            "minuteAnalysis", analysis,
            "previousContext", lastInsight != null ? lastInsight : "Nenhum histórico disponível."
        );
    }

    /**
     * Calcula qual a zona de Karvonen foi a mais frequente durante o treino (baseado em segundos)
     */
    public String calculateDominantZoneSummary(List<Double> hrData) {
        if (hrData.isEmpty()) return "N/A";

        Map<Integer, Integer> zoneCounts = new HashMap<>();
        for (Double bpm : hrData) {
            int zone = calculateKarvonenZone(bpm);
            if (zone > 0) {
                zoneCounts.put(zone, zoneCounts.getOrDefault(zone, 0) + 1);
            }
        }

        if (zoneCounts.isEmpty()) return "N/A";

        int dominantZone = Collections.max(zoneCounts.entrySet(), Map.Entry.comparingByValue()).getKey();
        double percentage = (zoneCounts.get(dominantZone) * 100.0) / hrData.size();

        return String.format("Z%d (%.1f%%)", dominantZone, percentage);
    }

    public List<Double> getHeartRateStream(List<StravaActivity.ActivityStream> streams) {
        return extractStream(streams, "heartrate");
    }

    private List<Double> extractStream(List<StravaActivity.ActivityStream> streams, String type) {
        return streams.stream()
                .filter(s -> type.equals(s.type()))
                .findFirst()
                .map(StravaActivity.ActivityStream::data)
                .orElse(List.of());
    }

    private double getAverage(List<Double> data, int start, int end) {
        if (data.isEmpty() || start >= data.size()) return 0.0;
        int actualEnd = Math.min(end, data.size());
        return data.subList(start, actualEnd).stream().mapToDouble(d -> d).average().orElse(0.0);
    }

    private int calculateKarvonenZone(double bpm) {
        int hrReserve = hrMax - hrResting;
        if (hrReserve <= 0) return 0;

        double intensity = (bpm - hrResting) / hrReserve;

        if (intensity >= 0.90) return 5; // Z5: 90-100%
        if (intensity >= 0.80) return 4; // Z4: 80-90%
        if (intensity >= 0.70) return 3; // Z3: 70-80%
        if (intensity >= 0.60) return 2; // Z2: 60-70%
        if (intensity >= 0.50) return 1; // Z1: 50-60%
        
        return 0; // Abaixo de Z1
    }

    public record ActivityPageResponse(
            List<StravaActivity> activities,
            int rawCount
    ) {}
}