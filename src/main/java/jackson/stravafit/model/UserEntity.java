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
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "google_id", unique = true)
    private String googleId;

    @Column(name = "strava_athlete_id", unique = true)
    private Long stravaAthleteId;

    @Column(name = "strava_access_token")
    private String stravaAccessToken;

    @Column(name = "strava_refresh_token")
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

    public UserEntity() {
    }
}
