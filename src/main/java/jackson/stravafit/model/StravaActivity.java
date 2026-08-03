package jackson.stravafit.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public class StravaActivity {
    private Long id;
    private String name;
    private Double distance;

    @JsonProperty("moving_time")
    private Integer movingTime;

    @JsonProperty("elapsed_time")
    private Integer elapsedTime;

    @JsonProperty("total_elevation_gain")
    private Double totalElevationGain;

    @JsonProperty("sport_type")
    private String sportType;

    @JsonProperty("start_date_local")
    private String startDateLocal;

    @JsonProperty("average_speed")
    private Double averageSpeed;

    @JsonProperty("max_speed")
    private Double maxSpeed;

    @JsonProperty("has_heartrate")
    private Boolean hasHeartRate;

    @JsonProperty("average_heartrate")
    private Double averageHeartRate;

    @JsonProperty("max_heartrate")
    private Double maxHeartRate;

    @JsonProperty("athlete")
    private StravaAthlete athlete;

    private Double calories;

    @JsonProperty("start_latlng")
    private List<Double> startLatlng;

    // Construtor padrão
    public StravaActivity() {
    }

    // Construtor com todos os argumentos (incluindo startLatlng)
    public StravaActivity(Long id, String name, Double distance, Integer movingTime, Integer elapsedTime, Double totalElevationGain, String sportType, String startDateLocal, Double averageSpeed, Double maxSpeed, Boolean hasHeartRate, Double averageHeartRate, Double maxHeartRate, StravaAthlete athlete, Double calories, List<Double> startLatlng) {
        this.id = id;
        this.name = name;
        this.distance = distance;
        this.movingTime = movingTime;
        this.elapsedTime = elapsedTime;
        this.totalElevationGain = totalElevationGain;
        this.sportType = sportType;
        this.startDateLocal = startDateLocal;
        this.averageSpeed = averageSpeed;
        this.maxSpeed = maxSpeed;
        this.hasHeartRate = hasHeartRate;
        this.averageHeartRate = averageHeartRate;
        this.maxHeartRate = maxHeartRate;
        this.athlete = athlete;
        this.calories = calories;
        this.startLatlng = startLatlng;
    }

    // Getters
    public Long getId() { return id; }
    public String getName() { return name; }
    public Double getDistance() { return distance; }
    public Integer getMovingTime() { return movingTime; }
    public Integer getElapsedTime() { return elapsedTime; }
    public Double getTotalElevationGain() { return totalElevationGain; }
    public String getSportType() { return sportType; }
    public String getStartDateLocal() { return startDateLocal; }
    public Double getAverageSpeed() { return averageSpeed; }
    public Double getMaxSpeed() { return maxSpeed; }
    public Boolean getHasHeartRate() { return hasHeartRate; }
    public Double getAverageHeartRate() { return averageHeartRate; }
    public Double getMaxHeartRate() { return maxHeartRate; }
    public StravaAthlete getAthlete() { return athlete; }
    public Double getCalories() { return calories; }
    public List<Double> getStartLatlng() { return startLatlng; }

    // Helpers para clima / localização
    public Double getLatitude() {
        return (startLatlng != null && startLatlng.size() >= 2) ? startLatlng.get(0) : null;
    }

    public Double getLongitude() {
        return (startLatlng != null && startLatlng.size() >= 2) ? startLatlng.get(1) : null;
    }

    // Setters
    public void setId(Long id) { this.id = id; }
    public void setName(String name) { this.name = name; }
    public void setDistance(Double distance) { this.distance = distance; }
    public void setMovingTime(Integer movingTime) { this.movingTime = movingTime; }
    public void setElapsedTime(Integer elapsedTime) { this.elapsedTime = elapsedTime; }
    public void setTotalElevationGain(Double totalElevationGain) { this.totalElevationGain = totalElevationGain; }
    public void setSportType(String sportType) { this.sportType = sportType; }
    public void setStartDateLocal(String startDateLocal) { this.startDateLocal = startDateLocal; }
    public void setAverageSpeed(Double averageSpeed) { this.averageSpeed = averageSpeed; }
    public void setMaxSpeed(Double maxSpeed) { this.maxSpeed = maxSpeed; }
    public void setHasHeartRate(Boolean hasHeartRate) { this.hasHeartRate = hasHeartRate; }
    public void setAverageHeartRate(Double averageHeartRate) { this.averageHeartRate = averageHeartRate; }
    public void setMaxHeartRate(Double maxHeartRate) { this.maxHeartRate = maxHeartRate; }
    public void setAthlete(StravaAthlete athlete) { this.athlete = athlete; }
    public void setCalories(Double calories) { this.calories = calories; }
    public void setStartLatlng(List<Double> startLatlng) { this.startLatlng = startLatlng; }

    public double distanceKm() {
        return distance != null ? distance / 1000 : 0.0;
    }

    public int elapsedTimeMinutes() {
        return elapsedTime != null ? elapsedTime / 60 : 0;
    }

    // DTOs auxiliares para dados detalhados
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HeartRateZone {
        @JsonProperty("distribution_buckets")
        private List<ZoneBucket> distribution;

        public HeartRateZone() {}
        public HeartRateZone(List<ZoneBucket> distribution) { this.distribution = distribution; }
        public List<ZoneBucket> getDistribution() { return distribution; }
        public void setDistribution(List<ZoneBucket> distribution) { this.distribution = distribution; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ZoneBucket {
        private Double min;
        private Double max;
        private Integer time;

        public ZoneBucket() {}
        public ZoneBucket(Double min, Double max, Integer time) { this.min = min; this.max = max; this.time = time; }
        public Double getMin() { return min; }
        public Double getMax() { return max; }
        public Integer getTime() { return time; }
        public void setMin(Double min) { this.min = min; }
        public void setMax(Double max) { this.max = max; }
        public void setTime(Integer time) { this.time = time; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ActivityStream {
        private String type;
        private List<Double> data;

        public ActivityStream() {}
        public ActivityStream(String type, List<Double> data) { this.type = type; this.data = data; }
        public String getType() { return type; }
        public List<Double> getData() { return data; }
        public void setType(String type) { this.type = type; }
        public void setData(List<Double> data) { this.data = data; }
    }

    public static class MinuteAnalysis {
        private int minute;
        private double averageHeartRate;
        private double maxHeartRate;
        private int zone;
        private double averageElevation;
        private double averageCadence;
        private double normalizedSpeedMpm;
        private double normalizedPaceMinKm;

        public MinuteAnalysis() {}
        public MinuteAnalysis(int minute, double averageHeartRate, double maxHeartRate, int zone, double averageElevation, double averageCadence, double normalizedSpeedMpm, double normalizedPaceMinKm) {
            this.minute = minute;
            this.averageHeartRate = averageHeartRate;
            this.maxHeartRate = maxHeartRate;
            this.zone = zone;
            this.averageElevation = averageElevation;
            this.averageCadence = averageCadence;
            this.normalizedSpeedMpm = normalizedSpeedMpm;
            this.normalizedPaceMinKm = normalizedPaceMinKm;

        }

        public int getMinute() { return minute; }
        public double getAverageHeartRate() { return averageHeartRate; }
        public double getMaxHeartRate() { return maxHeartRate; }
        public int getZone() { return zone; }
        public double getAverageElevation() { return averageElevation; }
        public double getAverageCadence() { return averageCadence; }
        public double getNormalizedSpeedMpm() { return normalizedSpeedMpm; }
        public double getNormalizedPaceMinKm() { return normalizedPaceMinKm; }

        public void setMinute(int minute) { this.minute = minute; }
        public void setAverageHeartRate(double averageHeartRate) { this.averageHeartRate = averageHeartRate; }
        public void setMaxHeartRate(double maxHeartRate) { this.maxHeartRate = maxHeartRate; }
        public void setZone(int zone) { this.zone = zone; }
        public void setAverageElevation(double averageElevation) { this.averageElevation = averageElevation; }
        public void setAverageCadence(double averageCadence) { this.averageCadence = averageCadence; }
        public void setNormalizedSpeedMpm(double normalizedSpeedMpm) { this.normalizedSpeedMpm = normalizedSpeedMpm; }
        public void setNormalizedPaceMinKm(double normalizedPaceMinKm) { this.normalizedPaceMinKm = normalizedPaceMinKm; }
    }

    public static class AthleteInsight {
        private String athleteId;
        private String lastActivityDate;
        private String summarizedStatus;
        private Double fitnessScore;

        public AthleteInsight() {}
        public AthleteInsight(String athleteId, String lastActivityDate, String summarizedStatus, Double fitnessScore) {
            this.athleteId = athleteId;
            this.lastActivityDate = lastActivityDate;
            this.summarizedStatus = summarizedStatus;
            this.fitnessScore = fitnessScore;
        }

        public String getAthleteId() { return athleteId; }
        public String getLastActivityDate() { return lastActivityDate; }
        public String getSummarizedStatus() { return summarizedStatus; }
        public Double getFitnessScore() { return fitnessScore; }

        public void setAthleteId(String athleteId) { this.athleteId = athleteId; }
        public void setLastActivityDate(String lastActivityDate) { this.lastActivityDate = lastActivityDate; }
        public void setSummarizedStatus(String summarizedStatus) { this.summarizedStatus = summarizedStatus; }
        public void setFitnessScore(Double fitnessScore) { this.fitnessScore = fitnessScore; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StravaAthlete {
        private Long id;

        public StravaAthlete() {}
        public StravaAthlete(Long id) { this.id = id; }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StravaActivity that = (StravaActivity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "StravaActivity{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", distance=" + distance +
                ", sportType='" + sportType + '\'' +
                ", startDateLocal='" + startDateLocal + '\'' +
                ", averageSpeed=" + averageSpeed +
                ", averageHeartRate=" + averageHeartRate +
                '}';
    }
}