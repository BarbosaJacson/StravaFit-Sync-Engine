package jackson.stravafit.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StravaActivity(
        Long id,
        String name,
        Double distance,

        @JsonProperty("moving_time")
        Integer movingTime,

        @JsonProperty("elapsed_time")
        Integer elapsedTime,

        @JsonProperty("total_elevation_gain")
        Double totalElevationGain,

        @JsonProperty("sport_type")
        String sportType,

        @JsonProperty("start_date_local")
        String startDateLocal,

        @JsonProperty("average_speed")
        Double averageSpeed,

        @JsonProperty("max_speed")
        Double maxSpeed,

        @JsonProperty("has_heartrate")
        Boolean hasHeartRate,

        // ADICIONADO: Este é o campo que o SyncScheduler estava procurando
        @JsonProperty("average_heartrate")
        Double averageHeartRate,

        @JsonProperty("max_heartrate")
        Double maxHeartRate,

        Double calories
) {
    public double distanceKm() {
        return distance != null ? distance / 1000 : 0.0;
    }

    public int elapsedTimeMinutes() {
        return elapsedTime != null ? elapsedTime / 60 : 0;
    }

    // DTOs auxiliares para dados detalhados
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HeartRateZone(
            @JsonProperty("distribution_buckets") List<ZoneBucket> distribution
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ZoneBucket(
            Double min,
            Double max,
            Integer time // tempo em segundos nesta zona
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ActivityStream(
            String type,
            List<Double> data
    ) {}

    public record MinuteAnalysis(
            int minute,
            double averageHeartRate,
            double maxHeartRate,
            int zone,
            double averageElevation,
            double averageCadence
    ) {}

    public record AthleteInsight(
            String athleteId,
            String lastActivityDate,
            String summarizedStatus, // Texto gerado pelo Gemini
            Double fitnessScore     // Uma métrica calculada pela sua lógica
    ) {}
}