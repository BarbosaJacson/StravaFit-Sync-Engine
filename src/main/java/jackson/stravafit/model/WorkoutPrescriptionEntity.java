package jackson.stravafit.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime; // Import adicionado

@Entity
@Table(name = "workout_prescriptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkoutPrescriptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ID da atividade Strava para a qual esta prescrição foi gerada (após a análise desta atividade)
    @Column(name = "activity_id", nullable = false)
    private Long activityId;

    // Data para a qual o treino foi prescrito (ex: QUINTA-FEIRA, 30/04/2026)
    @Column(name = "scheduled_date", nullable = false)
    private LocalDate scheduledDate;

    @Column(name = "type")
    private String type; // Ex: Corrida de Base Aeróbica, Treino Intervalado

    @Column(name = "distance_km")
    private Double distanceKm;

    @Column(name = "duration")
    private String duration; // Ex: 60 a 75 minutos

    @Column(name = "intensity")
    private String intensity; // Ex: Zona 2 (124-139 bpm)

    @Column(name = "focus", columnDefinition = "TEXT")
    private String focus; // Foco principal do treino

    @Column(name = "pace_target")
    private String paceTarget; // Ex: 6:55-7:05 min/km

    @Column(name = "method", columnDefinition = "TEXT")
    private String method; // Ex: Contínuo em Z2, Incorporar 2x (30 segundos em Z3 / 2 minutos de recuperação)

    @Column(name = "hiit_details", columnDefinition = "TEXT")
    private String hiitDetails; // Detalhes do treino de choque, se houver

    @Column(name = "nutrition_details", columnDefinition = "TEXT")
    private String nutritionDetails; // Recomendações nutricionais

    @Column(name = "raw_gemini_response", columnDefinition = "TEXT")
    private String rawGeminiResponse; // A resposta completa do Gemini para referência

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
