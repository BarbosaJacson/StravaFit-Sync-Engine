package jackson.stravafit.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AthleteConfig {

    private final int hrMax;
    private final int hrResting;

    public AthleteConfig(@Value("${atleta.hr-max}") int hrMax,
                         @Value("${atleta.hr-resting}") int hrResting) {
        this.hrMax = hrMax;
        this.hrResting = hrResting;
    }

    public int getHrMax() {
        return hrMax;
    }

    public int getHrResting() {
        return hrResting;
    }
}
