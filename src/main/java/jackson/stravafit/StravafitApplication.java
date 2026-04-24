package jackson.stravafit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication; // <--- Import importante

@SpringBootApplication // <--- ESSA LINHA É OBRIGATÓRIA
public class StravafitApplication {

    public static void main(String[] args) {
        SpringApplication.run(StravafitApplication.class, args);
    }

}