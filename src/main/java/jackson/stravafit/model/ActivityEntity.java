package jackson.stravafit.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;

@Entity
@Table(name = "activities")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActivityEntity {
    @Id
    private Long id;
    @Column(name = "name")
    private String name;
    @Column(name = "start_date")
    private LocalDateTime startDate;
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
    @ToString.Exclude // Evita que o log fique gigantesco ao imprimir a entidade
    @EqualsAndHashCode.Exclude // Evita problemas de performance ao comparar entidades
    @Builder.Default
    private List<MinuteAnalysisEntity> minuteDetails = new ArrayList<>();
}
