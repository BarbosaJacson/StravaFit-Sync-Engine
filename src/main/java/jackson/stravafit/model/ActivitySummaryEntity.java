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
    private Long activityId;

    private LocalDateTime startDate;

    private Double distanceKm;

    private Integer totalTimeMinutes;

    private Double averageHeartRate;

    private Integer maxHeartRate;

    private Integer dominantZone;

    private Double efficiencyIndex;

    @Column(columnDefinition = "TEXT")
    private String aiAnalysisSummary;

}