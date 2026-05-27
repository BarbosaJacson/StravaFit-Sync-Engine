package jackson.stravafit.service;

import org.springframework.stereotype.Service;

@Service
public class SleepService {

    // TODO: Implementar integração real com Google Fit ou Garmin (via biblioteca não oficial)
    // Por enquanto, retorna dados simulados.
    public SleepData getTodaySleep() {
        // Simula um sono de 8 horas com boa qualidade
        return new SleepData(8.0, "Boa", "Nenhum problema detectado.");
    }

    public record SleepData(
        Double hours,
        String quality,
        String notes
    ) {}
}
