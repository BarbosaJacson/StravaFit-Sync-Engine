package jackson.stravafit.model;

import jakarta.persistence.*;
import lombok.*;
import java.util.List;
import java.util.ArrayList;

@Entity
@Table(name = "activities")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ActivityEntity {
    @Id
    private Long id;
    private String name;
    private String startDate;
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
    @Builder.Default
    private List<MinuteAnalysisEntity> minuteDetails = new ArrayList<>();
}
