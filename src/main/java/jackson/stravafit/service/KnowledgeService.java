package jackson.stravafit.service;

import jackson.stravafit.model.ScenarioEntity;
import jackson.stravafit.repository.ScenarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final ScenarioRepository scenarioRepository;

    public String getScientificContext() {
        List<ScenarioEntity> scenarios = scenarioRepository.findAll();
        if (scenarios.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("--- BASE DE CONHECIMENTO (CENÁRIOS DO MONGODB) ---\n\n");

        for (ScenarioEntity scenario : scenarios) {
            sb.append("CENÁRIO: ").append(scenario.getTitulo()).append("\n");
            sb.append("DIAGNÓSTICO: ").append(scenario.getDiagnostico()).append("\n");
            sb.append("ANÁLISE: ").append(scenario.getAnalise()).append("\n");
            sb.append("CONCLUSÃO: ").append(scenario.getConclusao()).append("\n");
            sb.append("REPOSIÇÃO: ").append(scenario.getReposicao()).append("\n");
            sb.append("PRÓXIMO TREINO: ").append(scenario.getProximoTreino()).append("\n\n");
        }

        return sb.toString();
    }
}
