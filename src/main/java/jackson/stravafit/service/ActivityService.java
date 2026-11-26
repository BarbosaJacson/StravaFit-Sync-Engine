package jackson.stravafit.service;

import jackson.stravafit.client.StravaClient;
import jackson.stravafit.model.ActivityEntity;
import jackson.stravafit.model.MinuteAnalysisEntity;
import jackson.stravafit.model.StravaActivity;
import jackson.stravafit.repository.ActivityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jackson.stravafit.model.UserEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityService {

    private final StravaClient stravaClient;
    private final ActivityRepository activityRepository;

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

    @Transactional
    public void saveActivity(UserEntity user, StravaActivity activity, List<StravaActivity.MinuteAnalysis> minutes, String zone, String insight) {
        if (activity == null || activity.getId() == null) {
            log.warn("[SAVE] Tentativa de salvar atividade nula ou sem ID.");
            return;
        }

        List<MinuteAnalysisEntity> minuteEntities = minutes.stream()
                .map(m -> new MinuteAnalysisEntity(null, m.getMinute(), m.getAverageHeartRate(), m.getMaxHeartRate(), m.getZone(), m.getAverageElevation(), m.getAverageCadence()))
                .toList();

        ActivityEntity entity = activityRepository.findById(activity.getId())
                .map(existing -> {
                    log.info("[DB] Atividade {} já existe. Atualizando dados...", activity.getId());
                    if (existing.getUser() == null && user != null) {
                        existing.setUser(user);
                    }
                    existing.setName(activity.getName());
                    existing.setStartDate(activity.getStartDateLocal());
                    existing.setDistanceKm(activity.getDistance() / 1000.0);
                    existing.setAverageHeartRate(activity.getAverageHeartRate());
                    existing.setMaxHeartRate(activity.getMaxHeartRate());
                    existing.setSportType(activity.getSportType());
                    existing.setDominantZone(zone);
                    existing.setTotalTimeMinutes((int) (activity.getElapsedTime() / 60.0));
                    existing.setGeminiInsight(insight);

                    existing.getMinuteDetails().clear();
                    existing.getMinuteDetails().addAll(minuteEntities);

                    return existing;
                })
                .orElseGet(() -> {
                    log.info("[DB] Atividade {} não encontrada. Criando nova entidade...", activity.getId());
                    ActivityEntity nova = new ActivityEntity(
                            activity.getId(),
                            activity.getName(),
                            activity.getStartDateLocal(),
                            activity.getDistance() / 1000.0,
                            activity.getAverageHeartRate(),
                            activity.getMaxHeartRate(),
                            activity.getSportType(),
                            zone,
                            (int) (activity.getElapsedTime() / 60.0),
                            insight,
                            new ArrayList<>(minuteEntities)
                    );
                    nova.setUser(user);
                    return nova;
                });

        try {
            // 🎯 Força o envio imediato do SQL para o MySQL tratando concorrência
            activityRepository.saveAndFlush(entity);
            log.info("[DB] Atividade {} salva/atualizada com sucesso no banco.", entity.getId());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.warn("[DB] Concorrência detectada: Atividade {} já foi gravada por outra thread.", activity.getId());
        }
    }
    /**
     * Transforma dados de segundos em médias por minuto e identifica a zona cardíaca com base nos dados biométricos do atleta (MySQL).
     */
    public List<StravaActivity.MinuteAnalysis> aggregateStreamsByMinute(
            List<StravaActivity.ActivityStream> streams,
            List<StravaActivity.HeartRateZone> zones,
            String hrZoneSource,
            Integer lv1,
            Integer lv2,
            Integer hrMax,
            Integer hrResting) {

        List<StravaActivity.MinuteAnalysis> analysis = new ArrayList<>();

        List<Double> hrData = extractStream(streams, "heartrate");
        List<Double> altData = extractStream(streams, "altitude");
        List<Double> cadData = extractStream(streams, "cadence");
        List<Double> distData = extractStream(streams, "distance");

        if (hrData == null || hrData.isEmpty()) return analysis;

        for (int i = 0; i < hrData.size(); i += 60) {
            int end = Math.min(i + 60, hrData.size());
            List<Double> minuteSlice = hrData.subList(i, end);

            double avgHr = minuteSlice.stream().mapToDouble(d -> d).average().orElse(0.0);
            double maxHr = minuteSlice.stream().mapToDouble(d -> d).max().orElse(0.0);
            double avgAlt = getAverage(altData, i, end);
            double avgCad = getAverage(cadData, i, end);

            double normalizedSpeedMpm = calculateMinuteNormalizedSpeed(distData, altData, i, end);
            double normalizedPaceMinKm = normalizedSpeedMpm > 0 ? (1000.0 / normalizedSpeedMpm) : 0.0;

            int minuteNumber = (i / 60) + 1;
            int zoneDetected = resolveZoneForBpm(avgHr, hrZoneSource, lv1, lv2, hrMax, hrResting);

            analysis.add(new StravaActivity.MinuteAnalysis(minuteNumber, avgHr, maxHr, zoneDetected, avgAlt, avgCad,
                    normalizedSpeedMpm, normalizedPaceMinKm));
        }
        return analysis;
    }

    /**
     * Calcula a zona de Karvonen mais frequente durante o treino usando as métricas do MySQL.
     */
    public String calculateDominantZoneSummary(
            List<Double> hrData,
            String hrZoneSource,
            Integer lv1,
            Integer lv2,
            Integer hrMax,
            Integer hrResting) {
        if (hrData.isEmpty()) return "N/A";

        Map<Integer, Integer> zoneCounts = new HashMap<>();
        for (Double bpm : hrData) {
            int zone = resolveZoneForBpm(bpm, hrZoneSource, lv1, lv2, hrMax, hrResting);
            if (zone > 0) {
                zoneCounts.put(zone, zoneCounts.getOrDefault(zone, 0) + 1);
            }
        }

        if (zoneCounts.isEmpty()) return "N/A";

        int dominantZone = zoneCounts.entrySet().stream()
                .max((entry1, entry2) -> {
                    int compare = entry1.getValue().compareTo(entry2.getValue());
                    if (compare == 0) {
                        return entry1.getKey().compareTo(entry2.getKey());
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

    /**
     * Cálculo de Karvonen 100% dinâmico alimentado pelo MySQL.
     */
    public int calculateKarvonenZone(double bpm, int hrMax, int hrResting) {
        int hrReserve = hrMax - hrResting;
        if (hrReserve <= 0) return 0;

        int bpmInt = (int) Math.round(bpm);

        int z1Min = hrResting + (int) Math.round(0.50 * hrReserve);
        int z1Max = hrResting + (int) Math.round(0.59 * hrReserve);
        int z2Max = hrResting + (int) Math.round(0.72 * hrReserve);
        int z3Max = hrResting + (int) Math.round(0.82 * hrReserve);
        int z4Max = hrResting + (int) Math.round(0.92 * hrReserve);

        if (bpmInt < z1Min) return 0;
        if (bpmInt <= z1Max) return 1;
        if (bpmInt <= z2Max) return 2;
        if (bpmInt <= z3Max) return 3;
        if (bpmInt <= z4Max) return 4; // 🎯 Zona 4 Mapeada!

        return 5;
    }
    public record HeartRateRange(int minBpm, int maxBpm) {}

    public Map<Integer, HeartRateRange> calculateZonesFromThresholds(int lv1, int lv2, int hrMax) {
        int z1Max = (int) Math.round(lv1 * 0.90);
        int z1Min = Math.max(40, (int) Math.round(lv1 * 0.70));

        int z2Min = z1Max + 1;
        int z2Max = lv1;

        int meioLv1Lv2 = lv1 + (int) Math.round((lv2 - lv1) * 0.5);
        int z3Min = z2Max + 1;
        int z3Max = Math.max(z3Min, meioLv1Lv2);

        int z4Min = Math.max(z3Max + 1, lv2 - 4);
        int z4Max = Math.max(z4Min, lv2);

        int z5Min = Math.max(z4Max + 1, lv2 + 1);
        int z5Max = Math.max(z5Min, hrMax);

        Map<Integer, HeartRateRange> zones = new HashMap<>();
        zones.put(1, new HeartRateRange(z1Min, z1Max));
        zones.put(2, new HeartRateRange(z2Min, z2Max));
        zones.put(3, new HeartRateRange(z3Min, z3Max));
        zones.put(4, new HeartRateRange(z4Min, z4Max));
        zones.put(5, new HeartRateRange(z5Min, z5Max));
        return zones;
    }
    public int calculateThresholdZone(double bpm, int lv1, int lv2, int hrMax) {
        Map<Integer, HeartRateRange> zones = calculateZonesFromThresholds(lv1, lv2, hrMax);
        int bpmInt = (int) Math.round(bpm);

        for (int z = 1; z <= 5; z++) {
            HeartRateRange range = zones.get(z);
            if (range != null && bpmInt >= range.minBpm() && bpmInt <= range.maxBpm()) {
                return z;
            }
        }

        int melhorZona = 2;
        int menorDistancia = Integer.MAX_VALUE;

        for (int z = 1; z <= 5; z++) {
            HeartRateRange range = zones.get(z);
            if (range != null) {
                int centro = (range.minBpm() + range.maxBpm()) / 2;
                int distancia = Math.abs(bpmInt - centro);
                if (distancia < menorDistancia) {
                    menorDistancia = distancia;
                    melhorZona = z;
                }
            }
        }

        return melhorZona;
    }

    public int resolveZoneForBpm(double bpm, String hrZoneSource, Integer lv1, Integer lv2, Integer hrMax, Integer hrResting) {
        boolean hasThresholds = lv1 != null && lv2 != null && hrMax != null && lv1 > 0 && lv2 > lv1 && hrMax >= lv2;
        if ("EXAM".equalsIgnoreCase(hrZoneSource) && hasThresholds) {
            return calculateThresholdZone(bpm, lv1, lv2, hrMax);
        }

        int effectiveHrMax = (hrMax != null) ? hrMax : 0;
        int effectiveHrResting = (hrResting != null) ? hrResting : 0;
        return calculateKarvonenZone(bpm, effectiveHrMax, effectiveHrResting);
    }

    /**
     * Percentual por zona 100% dinâmico alimentado pelo MySQL.
     */
    public Map<Integer, Double> calculateZonePercentages(List<Double> hrData, int hrMax, int hrResting) {
        if (hrData == null || hrData.isEmpty()) return Map.of();

        Map<Integer, Long> zoneCounts = hrData.stream()
                .map(bpm -> calculateKarvonenZone(bpm, hrMax, hrResting))
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

    private double calculateMinuteNormalizedSpeed(List<Double> distData, List<Double> altData, int start, int end) {
        if (distData == null || distData.isEmpty() || altData == null || altData.isEmpty() || start >= distData.size()) {
            return 0.0;
        }

        int actualEnd = Math.min(end, distData.size());
        int actualAltEnd = Math.min(end, altData.size());

        double distDelta = distData.get(actualEnd - 1) - distData.get(start);
        double altDelta = altData.get(actualAltEnd - 1) - altData.get(Math.min(start, altData.size() - 1));

        if (distDelta <= 0) return 0.0;

        double grade = altDelta / distDelta;
        double minettiFactor = calculateMinettiCostFactor(grade);

        return distDelta * minettiFactor;
    }

    /**
     * Polinômio de Minetti et al. (2002) para cálculo do custo metabólico relativo de corrida em rampa.
     * @param grade Inclinação da rampa em decimal (ex: 0.05 para +5% de inclinação).
     * @return Fator multiplicador do esforço em relação ao plano (0% = 1.0).
     */
    public double calculateMinettiCostFactor(double grade) {
        double g = Math.max(-0.45, Math.min(0.45, grade));
        double cost = 155.4 * Math.pow(g, 5)
                - 30.4 * Math.pow(g, 4)
                - 43.3 * Math.pow(g, 3)
                + 46.3 * Math.pow(g, 2)
                + 19.5 * g
                + 3.6;
        return cost / 3.6;
    }
}