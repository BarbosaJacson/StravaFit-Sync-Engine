package jackson.stravafit.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
@Getter
public class AthleteConfig {

    private int hrMax;
    private int hrResting;

    public AthleteConfig(@Value("${atleta.hr-max:173}") int hrMax,
                         @Value("${atleta.hr-resting:53}") int hrResting) {
        this.hrMax = hrMax;
        this.hrResting = hrResting;
    }

    public void setHrMax(int hrMax) {
        this.hrMax = hrMax;
    }

    public void setHrResting(int hrResting) {
        this.hrResting = hrResting;
    }

    public int getHrMax() {
        return hrMax;
    }

    public int getHrResting() {
        return hrResting;
    }
}
