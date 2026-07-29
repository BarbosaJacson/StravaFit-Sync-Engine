package jackson.stravafit.model;

import jakarta.persistence.*;
import lombok.Getter;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class UserEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "google_id", unique = true)
    private String googleId;

    @Column(name = "strava_athlete_id", unique = true)
    private Long stravaAthleteId;

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
    private Integer weeklyFrequency=3;

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

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getGoogleId() {
        return googleId;
    }

    public void setGoogleId(String googleId) {
        this.googleId = googleId;
    }

    public Long getStravaAthleteId() {
        return stravaAthleteId;
    }

    public void setStravaAthleteId(Long stravaAthleteId) {
        this.stravaAthleteId = stravaAthleteId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
    }

    public Gender getGender() {
        return gender;
    }

    public void setGender(Gender gender) {
        this.gender = gender;
    }

    public Integer getHrMax() {
        return hrMax;
    }

    public void setHrMax(Integer hrMax) {
        this.hrMax = hrMax;
    }

    public Integer getHrResting() {
        return hrResting;
    }

    public void setHrResting(Integer hrResting) {
        this.hrResting = hrResting;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Integer getWeeklyFrequency() {
        return weeklyFrequency;
    }

    public void setWeeklyFrequency(Integer weeklyFrequency) {
        this.weeklyFrequency = weeklyFrequency;
    }

    public String getTrainingDays() {
        return trainingDays;
    }

    public void setTrainingDays(String trainingDays) {
        this.trainingDays = trainingDays;
    }

    public String getTargetObjective() {
        return targetObjective;
    }

    public void setTargetObjective(String targetObjective) {
        this.targetObjective = targetObjective;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Long getTelegramChatId() {
        return telegramChatId;
    }

    public void setTelegramChatId(Long telegramChatId) {
        this.telegramChatId = telegramChatId;
    }
}
