package jackson.stravafit.config;

import lombok.Data;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.time.DayOfWeek;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "atleta")
@Validated
@Data
public class AthleteConfig {

    @Min(100)
    private int hrMax;
    @Min(30)
    private int hrResting;

    // Define os dias que o sistema deve esperar um treino (Ex: TUESDAY, THURSDAY, SATURDAY)
    private List<DayOfWeek> trainingDays;

}