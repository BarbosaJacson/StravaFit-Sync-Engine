package jackson.stravafit.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "activity_performance_summary")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActivitySummaryEntity {

    @Id // O ID do Strava agora é a chave primária oficial
    @Column(name = "activity_id")
    private Long activityId;

    @Column(name = "start_date")
    private LocalDateTime startDate;

    @Column(name = "distance_km")
    private Double distanceKm;

    @Column(name = "total_time_minutes")
    private Integer totalTimeMinutes;

    @Column(name = "average_heart_rate")
    private Double averageHeartRate;

    @Column(name = "max_heart_rate")
    private Integer maxHeartRate;

    @Column(name = "dominant_zone")
    private Integer dominantZone;

    @Column(name = "efficiency_index")
    private Double efficiencyIndex;

    @Column(name = "ai_analysis_summary", columnDefinition = "TEXT")
    private String aiAnalysisSummary;

    @Column(name = "real_stimulus_type")
    private String realStimulusType;

    @Column(name = "detected_scenario")
    private Integer detectedScenario;

    @Column(name = "detected_level")
    private Integer detectedLevel;

}