package jackson.stravafit.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "atleta")
@Data
public class AthleteConfig {

    private int hrMax;
    private int hrResting;

}