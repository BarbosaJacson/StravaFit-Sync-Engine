package jackson.stravafit.model;

public record WeatherData(
        Double temperature,
        Double apparentTemperature,
        Integer humidity,
        Double windSpeed,
        String conditionText
) {
    /**
     * Retorna a string formatada em texto puro para a mensagem do Telegram.
     */
    public String toTelegramFormat() {
        if (!hasData()) {
            return "🌤️ CLIMA DURANTE O TREINO: Dados indisponíveis";
        }

        String condicao = (conditionText != null && !conditionText.isBlank())
                ? " - " + conditionText
                : "";

        return String.format(
                "🌤️ CLIMA DURANTE O TREINO: %.1f°C (Sensação: %.1f°C) | Umidade: %d%% | Vento: %.1f km/h%s",
                temperature, apparentTemperature, humidity, windSpeed, condicao
        );
    }

    /**
     * Verifica se os dados vitais do clima estão preenchidos para evitar NullPointerException.
     */
    public boolean hasData() {
        return temperature != null && apparentTemperature != null && humidity != null && windSpeed != null;
    }
}