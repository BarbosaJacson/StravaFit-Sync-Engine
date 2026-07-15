package jackson.stravafit;

import jackson.stravafit.service.BackfillService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // Ativa o suporte a agendamento
@EnableAsync
public class StravafitApplication {

    public static void main(String[] args) {
        SpringApplication.run(StravafitApplication.class, args);
    }


}
