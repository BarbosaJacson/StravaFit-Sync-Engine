package jackson.stravafit.model;

import jakarta.persistence.*;
import java.util.Objects;

@Entity
@Table(name = "activity_minutes")
public class MinuteAnalysisEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private Integer minute;
    private Double averageHeartRate;
    private Double maxHeartRate;
    private Integer zone;
    private Double averageElevation;
    private Double averageCadence;

    // Construtor padrão (necessário para JPA)
    public MinuteAnalysisEntity() {
    }

    // Construtor completo
    public MinuteAnalysisEntity(Long id, Integer minute, Double averageHeartRate, Double maxHeartRate, Integer zone, Double averageElevation, Double averageCadence) {
        this.id = id;
        this.minute = minute;
        this.averageHeartRate = averageHeartRate;
        this.maxHeartRate = maxHeartRate;
        this.zone = zone;
        this.averageElevation = averageElevation;
        this.averageCadence = averageCadence;
    }

    // Getters
    public Long getId() {
        return id;
    }

    public Integer getMinute() {
        return minute;
    }

    public Double getAverageHeartRate() {
        return averageHeartRate;
    }

    public Double getMaxHeartRate() {
        return maxHeartRate;
    }

    public Integer getZone() {
        return zone;
    }

    public Double getAverageElevation() {
        return averageElevation;
    }

    public Double getAverageCadence() {
        return averageCadence;
    }

    // Setters
    public void setId(Long id) {
        this.id = id;
    }

    public void setMinute(Integer minute) {
        this.minute = minute;
    }

    public void setAverageHeartRate(Double averageHeartRate) {
        this.averageHeartRate = averageHeartRate;
    }

    public void setMaxHeartRate(Double maxHeartRate) {
        this.maxHeartRate = maxHeartRate;
    }

    public void setZone(Integer zone) {
        this.zone = zone;
    }

    public void setAverageElevation(Double averageElevation) {
        this.averageElevation = averageElevation;
    }

    public void setAverageCadence(Double averageCadence) {
        this.averageCadence = averageCadence;
    }

    @Override
    public String toString() {
        return "MinuteAnalysisEntity{" +
               "id=" + id +
               ", minute=" + minute +
               ", averageHeartRate=" + averageHeartRate +
               ", maxHeartRate=" + maxHeartRate +
               ", zone=" + zone +
               ", averageElevation=" + averageElevation +
               ", averageCadence=" + averageCadence +
               '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MinuteAnalysisEntity that = (MinuteAnalysisEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
