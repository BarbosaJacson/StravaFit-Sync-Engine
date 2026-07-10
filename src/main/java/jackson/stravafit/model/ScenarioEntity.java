package jackson.stravafit.model;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "scenarios")
@Getter
@Setter
public class ScenarioEntity {

    @Id
    private String id;
    private String titulo;
    private String diagnostico;
    private String analise;
    private String conclusao;
    private String reposicao;
    private String proximoTreino;
}
