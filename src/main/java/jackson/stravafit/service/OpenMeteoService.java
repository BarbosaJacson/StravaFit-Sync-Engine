package jackson.stravafit.service;

import jackson.stravafit.model.WeatherData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

@Slf4j
@Service
public class OpenMeteoService {

    private final RestTemplate restTemplate = new RestTemplate();

    public WeatherData getWeatherForLocation(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            log.warn("[CLIMA] Coordenadas de GPS indisponíveis para esta atividade.");
            return new WeatherData(null, null, null, null, null);
        }

        try {
            String url = UriComponentsBuilder.fromUriString("https://api.open-meteo.com/v1/forecast")
                    .queryParam("latitude", latitude)
                    .queryParam("longitude", longitude)
                    .queryParam("current", "temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m")
                    .queryParam("timezone", "America/Sao_Paulo")
                    .toUriString();

            Map<String, Object> response = restTemplate.getForObject(url, Map.class);

            // 🎯 Ajustado para "current"
            if (response != null && response.containsKey("current")) {
                Map<String, Object> current = (Map<String, Object>) response.get("current");

                Double temp = extractDouble(current.get("temperature_2m"));
                Double appTemp = extractDouble(current.get("apparent_temperature"));
                Integer humidity = extractInteger(current.get("relative_humidity_2m"));
                Double windSpeed = extractDouble(current.get("wind_speed_10m"));

                // 🎯 Converte o objeto para Integer com segurança antes de mapear o texto
                Integer weatherCode = extractInteger(current.get("weather_code"));
                String conditionText = extractConditionText(weatherCode);

                log.info("[CLIMA] Dados obtidos com sucesso: Temp: {}°C, Umidade: {}%, Vento: {} km/h - {}",
                        temp, humidity, windSpeed, conditionText);

                return new WeatherData(temp, appTemp, humidity, windSpeed, conditionText);
            }
        } catch (Exception e) {
            log.error("Falha ao consultar a API Open-Meteo: {}", e.getMessage());
        }

        return new WeatherData(null, null, null, null, null);
    }

    private Double extractDouble(Object obj) {
        return obj instanceof Number ? ((Number) obj).doubleValue() : null;
    }

    private Integer extractInteger(Object obj) {
        return obj instanceof Number ? ((Number) obj).intValue() : null;
    }

    private String extractConditionText(Integer code) {
        if (code == null) return "Condição Normal";
        return switch (code) {
            case 0 -> "Céu Limpo";
            case 1, 2, 3 -> "Parcialmente Nublado";
            case 45, 48 -> "Nevoeiro";
            case 51, 53, 55 -> "Garoa Fina";
            case 61, 63, 65 -> "Chuva";
            case 80, 81, 82 -> "Pancadas de Chuva";
            case 95, 96, 99 -> "Trovoada";
            default -> "Nublado";
        };
    }
}