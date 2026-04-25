package jackson.stravafit.service;

import jackson.stravafit.client.StravaClient;
import jackson.stravafit.model.StravaActivity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.ArrayList;

@Service
public class ActivityService {

    private final StravaClient stravaClient;

    public ActivityService(StravaClient stravaClient) {
        this.stravaClient = stravaClient;
    }

    /**
     * Recupera atividades do Strava e filtra apenas aquelas que possuem monitoramento de batimento cardíaco válido.
     */
    public ActivityPageResponse getActivitiesWithHeartRate(String token, int page) {
        List<StravaActivity> allActivities = stravaClient.getActivities(token, page);

        List<StravaActivity> filtered = allActivities.stream()
                .filter(activity -> Boolean.TRUE.equals(activity.hasHeartRate()))
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

        if (hrData.isEmpty()) return analysis;

        // Pegamos a primeira zona disponível (padrão do Strava para HR)
        List<StravaActivity.ZoneBucket> buckets = zones.isEmpty() ? List.of() : zones.get(0).distribution();

        for (int i = 0; i < hrData.size(); i += 60) {
            int end = Math.min(i + 60, hrData.size());
            List<Double> minuteSlice = hrData.subList(i, end);
            
            double avgHr = minuteSlice.stream().mapToDouble(d -> d).average().orElse(0.0);
            double avgAlt = getAverage(altData, i, end);
            double avgCad = getAverage(cadData, i, end);
            
            int minuteNumber = (i / 60) + 1;
            int zoneDetected = calculateZone(avgHr, buckets);

            analysis.add(new StravaActivity.MinuteAnalysis(minuteNumber, avgHr, zoneDetected, avgAlt, avgCad));
        }
        return analysis;
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

    private int calculateZone(double avgHr, List<StravaActivity.ZoneBucket> buckets) {
        for (int z = 0; z < buckets.size(); z++) {
            StravaActivity.ZoneBucket bucket = buckets.get(z);
            boolean acimaDoMinimo = avgHr >= bucket.min();
            boolean abaixoDoMaximo = bucket.max() == -1 || avgHr <= bucket.max();
            
            if (acimaDoMinimo && abaixoDoMaximo) {
                return z + 1;
            }
        }
        return 0;
    }

    public record ActivityPageResponse(
            List<StravaActivity> activities,
            int rawCount
    ) {}
}