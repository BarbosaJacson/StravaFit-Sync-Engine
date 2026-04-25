package jackson.stravafit.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "activity_minutes")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
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
}