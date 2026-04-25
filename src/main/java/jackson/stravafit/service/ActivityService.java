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
        
        // Localiza o stream de batimento cardíaco
        List<Double> hrData = streams.stream()
                .filter(s -> "heartrate".equals(s.type()))
                .findFirst()
                .map(StravaActivity.ActivityStream::data)
                .orElse(List.of());

        if (hrData.isEmpty()) return analysis;

        // Pegamos a primeira zona disponível (padrão do Strava para HR)
        List<StravaActivity.ZoneBucket> buckets = zones.isEmpty() ? List.of() : zones.get(0).distribution();

        for (int i = 0; i < hrData.size(); i += 60) {
            int end = Math.min(i + 60, hrData.size());
            List<Double> minuteSlice = hrData.subList(i, end);
            
            double avgHr = minuteSlice.stream().mapToDouble(d -> d).average().orElse(0.0);
            int minuteNumber = (i / 60) + 1;
            
            // Identifica a zona baseada no BPM médio deste minuto
            int zoneDetected = 0;
            for (int z = 0; z < buckets.size(); z++) {
                if (avgHr >= buckets.get(z).min() && (buckets.get(z).max() == -1 || avgHr <= buckets.get(z).max())) {
                    zoneDetected = z + 1;
                    break;
                }
            }
            analysis.add(new StravaActivity.MinuteAnalysis(minuteNumber, avgHr, zoneDetected));
        }
        return analysis;
    }

    public record ActivityPageResponse(
            List<StravaActivity> activities,
            int rawCount
    ) {}
}