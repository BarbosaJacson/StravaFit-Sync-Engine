package jackson.stravafit.model;

import jakarta.persistence.*;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;

@Entity
@Table(name = "activities")
public class ActivityEntity {
    @Id
    private Long id;
    @Column(name = "name")
    private String name;
    @Column(name = "start_date")
    private String startDate; // Mantido como String para persistência
    private Double distanceKm;
    private Double averageHeartRate;
    private Double maxHeartRate;
    private String sportType;
    private String dominantZone;
    private Integer totalTimeMinutes;

    @Column(columnDefinition = "TEXT")
    private String geminiInsight;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "activity_id")
    private List<MinuteAnalysisEntity> minuteDetails = new ArrayList<>();

    // Construtor padrão (necessário para JPA)
    public ActivityEntity() {
    }

    // Construtor completo
    public ActivityEntity(Long id, String name, String startDate, Double distanceKm, Double averageHeartRate, Double maxHeartRate, String sportType, String dominantZone, Integer totalTimeMinutes, String geminiInsight, List<MinuteAnalysisEntity> minuteDetails) {
        this.id = id;
        this.name = name;
        this.startDate = startDate;
        this.distanceKm = distanceKm;
        this.averageHeartRate = averageHeartRate;
        this.maxHeartRate = maxHeartRate;
        this.sportType = sportType;
        this.dominantZone = dominantZone;
        this.totalTimeMinutes = totalTimeMinutes;
        this.geminiInsight = geminiInsight;
        this.minuteDetails = minuteDetails != null ? new ArrayList<>(minuteDetails) : new ArrayList<>();
    }

    // Getters
    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getStartDate() {
        return startDate;
    }

    public Double getDistanceKm() {
        return distanceKm;
    }

    public Double getAverageHeartRate() {
        return averageHeartRate;
    }

    public Double getMaxHeartRate() {
        return maxHeartRate;
    }

    public String getSportType() {
        return sportType;
    }

    public String getDominantZone() {
        return dominantZone;
    }

    public Integer getTotalTimeMinutes() {
        return totalTimeMinutes;
    }

    public String getGeminiInsight() {
        return geminiInsight;
    }

    public List<MinuteAnalysisEntity> getMinuteDetails() {
        return minuteDetails;
    }

    // Setters
    public void setId(Long id) {
        this.id = id;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setStartDate(String startDate) {
        this.startDate = startDate;
    }

    public void setDistanceKm(Double distanceKm) {
        this.distanceKm = distanceKm;
    }

    public void setAverageHeartRate(Double averageHeartRate) {
        this.averageHeartRate = averageHeartRate;
    }

    public void setMaxHeartRate(Double maxHeartRate) {
        this.maxHeartRate = maxHeartRate;
    }

    public void setSportType(String sportType) {
        this.sportType = sportType;
    }

    public void setDominantZone(String dominantZone) {
        this.dominantZone = dominantZone;
    }

    public void setTotalTimeMinutes(Integer totalTimeMinutes) {
        this.totalTimeMinutes = totalTimeMinutes;
    }

    public void setGeminiInsight(String geminiInsight) {
        this.geminiInsight = geminiInsight;
    }

    public void setMinuteDetails(List<MinuteAnalysisEntity> minuteDetails) {
        this.minuteDetails = minuteDetails != null ? new ArrayList<>(minuteDetails) : new ArrayList<>();
    }

    @Override
    public String toString() {
        return "ActivityEntity{" +
               "id=" + id +
               ", name='" + name + '\'' +
               ", startDate='" + startDate + '\'' +
               ", distanceKm=" + distanceKm +
               ", averageHeartRate=" + averageHeartRate +
               ", maxHeartRate=" + maxHeartRate +
               ", sportType='" + sportType + '\'' +
               ", dominantZone='" + dominantZone + '\'' +
               ", totalTimeMinutes=" + totalTimeMinutes +
               ", geminiInsight='" + (geminiInsight != null ? geminiInsight.substring(0, Math.min(geminiInsight.length(), 50)) + "..." : "null") + '\'' +
               ", minuteDetailsSize=" + minuteDetails.size() +
               '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ActivityEntity that = (ActivityEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
