package jackson.stravafit.model;

public enum GoalCategory {
    ROUTINE_CONSISTENCY("Rotina e Consistência", "ROUTINE_CONSISTENCY"),
    HIGH_PERFORMANCE("Alta Performance (Pace e Provas)", "HIGH_PERFORMANCE"),
    CARDIO_LONGEVITY("Saúde Cardíaca e Longevidade", "CARDIO_LONGEVITY");

    private final String label;
    private final String trainingStyleCode;

    GoalCategory(String label, String trainingStyleCode) {
        this.label = label;
        this.trainingStyleCode = trainingStyleCode;
    }

    public String getLabel() {
        return label;
    }

    public String getTrainingStyleCode() {
        return trainingStyleCode;
    }
}