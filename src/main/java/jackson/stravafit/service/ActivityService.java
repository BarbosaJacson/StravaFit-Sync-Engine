package jackson.stravafit.service;

import jackson.stravafit.client.StravaClient;
import jackson.stravafit.config.AthleteConfig;
import jackson.stravafit.model.StravaActivity;
import jackson.stravafit.model.ActivityEntity;
import jackson.stravafit.model.MinuteAnalysisEntity;
import jackson.stravafit.repository.ActivityRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ActivityService {

    private final StravaClient stravaClient;
    private final ActivityRepository activityRepository;
    private final AthleteConfig athleteConfig;


    public ActivityPageResponse getActivitiesWithHeartRate(String token, int page) {
        List<StravaActivity> allActivities = stravaClient.getActivities(token, page);

        List<StravaActivity> filtered = allActivities.stream()
                .filter(activity -> Boolean.TRUE.equals(activity.getHasHeartRate()))
                .filter(activity -> {
                    String type = activity.getSportType();
                    return "Run".equalsIgnoreCase(type) || "Walk".equalsIgnoreCase(type);
                })
                .filter(activity -> activity.getAverageHeartRate() != null && activity.getAverageHeartRate() > 0)
                .toList();

        return new ActivityPageResponse(filtered, allActivities.size());
    }

    public List<StravaActivity.HeartRateZone> getActivityZones(String token, Long id) {
        return stravaClient.getActivityZones(token, id);
    }

    public List<StravaActivity.ActivityStream> getActivityStreams(String token, Long id) {
        return stravaClient.getActivityStreams(token, id);
    }

    public AthleteConfig getAthleteConfig() {
        return this.athleteConfig;
    }

    @Transactional
    public void saveActivity(StravaActivity activity, List<StravaActivity.MinuteAnalysis> minutes, String zone, String insight) {
        if (activity.getId() == null || activityRepository.existsById(activity.getId())) return;

        List<MinuteAnalysisEntity> minuteEntities = minutes.stream()
                .map(m -> new MinuteAnalysisEntity(null, m.getMinute(), m.getAverageHeartRate(), m.getMaxHeartRate(), m.getZone(), m.getAverageElevation(), m.getAverageCadence()))
                .toList();

        ActivityEntity entity = new ActivityEntity(
                activity.getId(),
                activity.getName(),
                activity.getStartDateLocal(),
                activity.getDistance() / 1000.0, // Converte metros para Km
                activity.getAverageHeartRate(),
                activity.getMaxHeartRate(),
                activity.getSportType(),
                zone,
                (int) (activity.getElapsedTime() / 60.0), // Converte segundos para minutos
                insight,
                new ArrayList<>(minuteEntities)
        );

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
            
            analysis.add(new StravaActivity.MinuteAnalysis(minuteNumber, avgHr, maxHr, zoneDetected, avgAlt, avgCad)); // Se for record, manter, se classe, usar new
        }
        return analysis;
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

        // 👑 Ajustado: Calcula a zona predominante corrigindo empates (prioriza maior intensidade)
        int dominantZone = zoneCounts.entrySet().stream()
                .max((entry1, entry2) -> {
                    int compare = entry1.getValue().compareTo(entry2.getValue());
                    if (compare == 0) {
                        return entry1.getKey().compareTo(entry2.getKey()); // Se empatar em minutos, escolhe a zona mais alta (Z3 > Z2)
                    }
                    return compare;
                })
                .map(Map.Entry::getKey).orElse(0);

        double percentage = (zoneCounts.get(dominantZone) * 100.0) / hrData.size();

        return String.format("Z%d (%.1f%%)", dominantZone, percentage);
    }

    public List<Double> getHeartRateStream(List<StravaActivity.ActivityStream> streams) {
        return extractStream(streams, "heartrate");
    }

    private List<Double> extractStream(List<StravaActivity.ActivityStream> streams, String type) {
        return streams.stream()
                .filter(s -> type.equals(s.getType()))
                .findFirst()
                .map(StravaActivity.ActivityStream::getData)
                .orElse(List.of());
    }

    private double getAverage(List<Double> data, int start, int end) {
        if (data.isEmpty() || start >= data.size()) return 0.0;
        int actualEnd = Math.min(end, data.size());
        return data.subList(start, actualEnd).stream().mapToDouble(d -> d).average().orElse(0.0);
    }

    public int calculateKarvonenZone(double bpm) {
        int hrMax = athleteConfig.getHrMax();
        int hrResting = athleteConfig.getHrResting();
        int hrReserve = hrMax - hrResting;
        if (hrReserve <= 0) return 0;

        // 1. Arredonda o BPM atual para o inteiro mais próximo
        int bpmInt = (int) Math.round(bpm);

        // 2. Calcula os limites exatos em BPM baseados na sua FCR (120 bpm)
        int z1Min = hrResting + (int) Math.round(0.50 * hrReserve); // 53 + 60 = 113 bpm
        int z1Max = hrResting + (int) Math.round(0.59 * hrReserve); // 53 + 71 = 124 bpm

        int z2Min = z1Max + 1;                                      // 125 bpm
        int z2Max = hrResting + (int) Math.round(0.72 * hrReserve); // 53 + 86 = 139 bpm (Se quiser estender ao limiar físico de 140, usamos 140)

        // Garantimos a continuidade perfeita somando +1 para a próxima zona
        int cinzaMin = z2Max + 1;                                   // 140 bpm
        int cinzaMax = hrResting + (int) Math.round(0.79 * hrReserve); // 53 + 95 = 148 bpm

        int z34Min = cinzaMax + 1;                                  // 149 bpm
        int z34Max = hrResting + (int) Math.round(0.89 * hrReserve); // 53 + 107 = 160 bpm

        int z5Min = z34Max + 1;                                     // 161 bpm

        // 3. Classificação contínua e sem frestas
        if (bpmInt < z1Min) {
            return 0; // Fora das zonas (Z0)
        }
        if (bpmInt <= z1Max) {
            return 1; // Zona 1 (113 a 124 bpm)
        }
        if (bpmInt <= z2Max) {
            return 2; // Zona 2 (125 a 139 bpm ou 140 bpm dependendo do teto)
        }
        if (bpmInt <= cinzaMax) {
            return 0; // Zona Cinzenta (140 a 148 bpm) -> Não pontua nas zonas alvo
        }
        if (bpmInt <= z34Max) {
            return 3; // Zona 3/4 (149 a 160 bpm)
        }

        return 5; // Zona 5 (161 bpm para cima)
    }

    public Map<Integer, Double> calculateZonePercentages(List<Double> hrData) {
        if (hrData == null || hrData.isEmpty()) return Map.of();

        Map<Integer, Long> zoneCounts = hrData.stream()
                .map(this::calculateKarvonenZone)
                .filter(z -> z > 0)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        long totalMinutos = hrData.size();

        Map<Integer, Double> percentages = new HashMap<>();
        for (int z = 1; z <= 5; z++) {
            long count = zoneCounts.getOrDefault(z, 0L);
            double pct = (count * 100.0) / totalMinutos;
            percentages.put(z, pct);
        }

        return percentages;
    }

    public record ActivityPageResponse(
            List<StravaActivity> activities,
            int rawCount
    ) {}
}
