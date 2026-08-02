package jackson.stravafit.service;

import jackson.stravafit.model.WeatherData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class OpenMeteoService {

    private final RestTemplate restTemplate = new RestTemplate();

    public WeatherData getWeatherForLocation(Double latitude, Double longitude, LocalDateTime activityStartDate) {
        if (latitude == null || longitude == null || activityStartDate == null) {
            log.warn("[CLIMA] Coordenadas de GPS ou Data/Hora indisponíveis para esta atividade.");
            return new WeatherData(null, null, null, null, null);
        }

        try {
            // Formata a data no padrão YYYY-MM-DD exigido pela Open-Meteo
            String dateStr = activityStartDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
            int targetHour = activityStartDate.getHour(); // Hora exata em que o treino começou (ex: 6 para 06:15)

            // Endpoint de HISTÓRICO da Open-Meteo
            String url = UriComponentsBuilder.fromUriString("https://archive-api.open-meteo.com/v1/archive")
                    .queryParam("latitude", latitude)
                    .queryParam("longitude", longitude)
                    .queryParam("start_date", dateStr)
                    .queryParam("end_date", dateStr)
                    .queryParam("hourly", "temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m")
                    .queryParam("timezone", "America/Sao_Paulo")
                    .toUriString();

            Map<String, Object> response = restTemplate.getForObject(url, Map.class);

            if (response != null && response.containsKey("hourly")) {
                Map<String, Object> hourly = (Map<String, Object>) response.get("hourly");

                List<Double> temperatures = (List<Double>) hourly.get("temperature_2m");
                List<Double> apparentTemps = (List<Double>) hourly.get("apparent_temperature");
                List<Integer> humidities = (List<Integer>) hourly.get("relative_humidity_2m");
                List<Double> windSpeeds = (List<Double>) hourly.get("wind_speed_10m");
                List<Integer> weatherCodes = (List<Integer>) hourly.get("weather_code");

                // Pega o valor exatamente no índice da hora correspondente (0h até 23h)
                Double temp = extractDoubleFromList(temperatures, targetHour);
                Double appTemp = extractDoubleFromList(apparentTemps, targetHour);
                Integer humidity = extractIntegerFromList(humidities, targetHour);
                Double windSpeed = extractDoubleFromList(windSpeeds, targetHour);
                Integer weatherCode = extractIntegerFromList(weatherCodes, targetHour);

                String conditionText = extractConditionText(weatherCode);

                log.info("[CLIMA HISTÓRICO] Dados para {} às {}h: Temp: {}°C, Sensation: {}°C, Umidade: {}%, Vento: {} km/h - {}",
                        dateStr, targetHour, temp, appTemp, humidity, windSpeed, conditionText);

                return new WeatherData(temp, appTemp, humidity, windSpeed, conditionText);
            }
        } catch (Exception e) {
            log.error("Falha ao consultar o histórico de clima na Open-Meteo: {}", e.getMessage());
        }

        return new WeatherData(null, null, null, null, null);
    }

    private Double extractDoubleFromList(List<?> list, int index) {
        if (list != null && index < list.size() && list.get(index) instanceof Number num) {
            return num.doubleValue();
        }
        return null;
    }

    private Integer extractIntegerFromList(List<?> list, int index) {
        if (list != null && index < list.size() && list.get(index) instanceof Number num) {
            return num.intValue();
        }
        return null;
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