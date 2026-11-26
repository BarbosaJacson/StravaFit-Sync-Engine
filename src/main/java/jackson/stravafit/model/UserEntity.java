package jackson.stravafit.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter
@Setter
public class UserEntity {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserEntity user;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "google_id", unique = true)
    private String googleId;

    @Column(name = "strava_athlete_id", unique = true)
    private Long stravaAthleteId;

    @Column(name = "strava_access_token", columnDefinition = "TEXT")
    private String stravaAccessToken;

    @Column(name = "strava_refresh_token", columnDefinition = "TEXT")
    private String stravaRefreshToken;

    @Column(name = "strava_token_expires_at")
    private Long stravaTokenExpiresAt;

    @Column(nullable = false)
    private String name;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender")
    private Gender gender;

    @Column(name = "hr_max", nullable = false)
    private Integer hrMax;

    @Column(name = "hr_resting", nullable = false)
    private Integer hrResting;

    @Column(nullable = false)
    private String email;

    @Column(name = "weekly_frequency", nullable = false)
    private Integer weeklyFrequency = 3;

    @Column(name = "training_days", nullable = false)
    private String trainingDays = "TUESDAY,THURSDAY,SATURDAY";

    @Column(name = "target_objective")
    private String targetObjective;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "telegram_chat_id")
    private Long telegramChatId;

    @Column(name = "hr_zone_source", length = 20)
    private String hrZoneSource;

    @Column(name = "z1_min_bpm")
    private Integer z1MinBpm;

    @Column(name = "z1_max_bpm")
    private Integer z1MaxBpm;

    @Column(name = "z2_min_bpm")
    private Integer z2MinBpm;

    @Column(name = "z2_max_bpm")
    private Integer z2MaxBpm;

    @Column(name = "z3_min_bpm")
    private Integer z3MinBpm;

    @Column(name = "z3_max_bpm")
    private Integer z3MaxBpm;

    @Column(name = "z4_min_bpm")
    private Integer z4MinBpm;

    @Column(name = "z4_max_bpm")
    private Integer z4MaxBpm;

    @Column(name = "z5_min_bpm")
    private Integer z5MinBpm;

    @Column(name = "z5_max_bpm")
    private Integer z5MaxBpm;

    public UserEntity() {
    }
}
