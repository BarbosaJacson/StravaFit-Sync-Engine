const loadingEl = document.getElementById("loading-screen");
const loadingTextEl = document.getElementById("loading-text");
const aguardar = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function mostrarCarregamento(texto) {
    if (loadingTextEl) loadingTextEl.textContent = texto || "Carregando...";
    if (loadingEl) loadingEl.classList.remove("hidden");
}

function esconderCarregamento() {
    if (loadingEl) loadingEl.classList.add("hidden");
}

const zoneSourceSelectEl = document.getElementById("select-zone-source");
const camposFormulaEl = document.getElementById("campos-formula");
const camposExameEl = document.getElementById("campos-exame");

function atualizarCamposZona() {
    const isExam = zoneSourceSelectEl && zoneSourceSelectEl.value === "EXAM";
    if (camposFormulaEl) camposFormulaEl.style.display = isExam ? "none" : "block";
    if (camposExameEl) camposExameEl.style.display = isExam ? "block" : "none";
}

function preencherFormularioPerfil(user) {
    if (!user) return;

    const nameEl = document.getElementById("input-name");
    const birthDateEl = document.getElementById("input-birth-date");
    const emailEl = document.getElementById("input-email");
    const hrMaxFormulaEl = document.getElementById("input-hr-max-formula");
    const hrRestingEl = document.getElementById("input-hr-resting");
    const lv1El = document.getElementById("input-lv1");
    const lv2El = document.getElementById("input-lv2");
    const hrMaxExamEl = document.getElementById("input-hr-max-exam");

    if (nameEl) nameEl.value = user.name ?? "";
    if (birthDateEl) birthDateEl.value = user.birthDate ?? "";
    if (emailEl) emailEl.value = user.email ?? "";

    if (zoneSourceSelectEl) {
        zoneSourceSelectEl.value = user.hrZoneSource === "EXAM" ? "EXAM" : "FORMULA";
    }
    atualizarCamposZona();

    if (user.hrZoneSource === "EXAM") {
        if (lv1El && user.z2MaxBpm != null) lv1El.value = user.z2MaxBpm;
        if (lv2El && user.z4MaxBpm != null) lv2El.value = user.z4MaxBpm;
        if (hrMaxExamEl && user.hrMax != null) hrMaxExamEl.value = user.hrMax;
    } else {
        if (hrMaxFormulaEl && user.hrMax != null) hrMaxFormulaEl.value = user.hrMax;
        if (hrRestingEl && user.hrResting != null) hrRestingEl.value = user.hrResting;
    }
}

if (zoneSourceSelectEl) {
    zoneSourceSelectEl.addEventListener("change", atualizarCamposZona);
}
atualizarCamposZona();

const formPerfilEl = document.getElementById("form-perfil-zonas");
if (formPerfilEl) {
    formPerfilEl.addEventListener("submit", async (event) => {
        event.preventDefault();
        const statusSalvarEl = document.getElementById("status-salvar-perfil");
        const hrZoneSource = zoneSourceSelectEl ? zoneSourceSelectEl.value : "FORMULA";

        const payload = {
            name: document.getElementById("input-name")?.value?.trim() || null,
            birthDate: document.getElementById("input-birth-date")?.value || null,
            email: document.getElementById("input-email")?.value?.trim() || null,
            hrZoneSource
        };

        if (hrZoneSource === "EXAM") {
            payload.lv1 = Number(document.getElementById("input-lv1")?.value) || null;
            payload.lv2 = Number(document.getElementById("input-lv2")?.value) || null;
            payload.hrMax = Number(document.getElementById("input-hr-max-exam")?.value) || null;
        } else {
            payload.hrMax = Number(document.getElementById("input-hr-max-formula")?.value) || null;
            payload.hrResting = Number(document.getElementById("input-hr-resting")?.value) || null;
        }

        if (statusSalvarEl) statusSalvarEl.textContent = "Salvando dados...";
        mostrarCarregamento("Salvando perfil e zonas...");
        try {
            const resp = await fetch("/api/me", {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                credentials: "same-origin",
                body: JSON.stringify(payload)
            });
            if (!resp.ok) {
                if (statusSalvarEl) statusSalvarEl.textContent = `Falha ao salvar (status ${resp.status}).`;
                return;
            }
            if (statusSalvarEl) statusSalvarEl.textContent = "Dados salvos com sucesso!";
            await carregarPerfil();
        } catch (err) {
            if (statusSalvarEl) statusSalvarEl.textContent = "Erro ao enviar dados: " + err.message;
        } finally {
            esconderCarregamento();
        }
    });
}

async function carregarPerfil() {
    const statusEl = document.getElementById("status-perfil");
    const dadosEl = document.getElementById("dados-perfil");
    mostrarCarregamento("Carregando perfil do atleta...");
    try {
        const [resp] = await Promise.all([
            fetch("/api/me", { credentials: "same-origin" }),
            aguardar(1200)
        ]);
        if (!resp.ok) {
            statusEl.textContent = "Sessão não autenticada (status " + resp.status + ").";
            return;
        }
        const user = await resp.json();
        preencherFormularioPerfil(user);
        statusEl.textContent = `Logado como ${user.name} (${user.email}) | Strava conectado: ${user.stravaConnected ? "Sim" : "Não"}`;
        dadosEl.textContent = JSON.stringify(user, null, 2);
    } catch (err) {
        statusEl.textContent = "Erro ao consultar /api/me: " + err.message;
    } finally {
        esconderCarregamento();
    }
}

const btnSincronizarEl = document.getElementById("btn-sincronizar");
if (btnSincronizarEl) {
    btnSincronizarEl.addEventListener("click", async () => {
        const syncEl = document.getElementById("status-sync");
        if (syncEl) syncEl.textContent = "Disparando sincronização...";
        mostrarCarregamento("Sincronizando treinos...");
        try {
            const [resp] = await Promise.all([
                fetch("/api/sync", {
                    method: "POST",
                    credentials: "same-origin"
                }),
                aguardar(1200)
            ]);
            if (!resp.ok) {
                if (syncEl) syncEl.textContent = `Falha na sincronização (status ${resp.status}).`;
                return;
            }
            const data = await resp.json();
            if (syncEl) syncEl.textContent = `${data.status}: ${data.message}`;
        } catch (err) {
            if (syncEl) syncEl.textContent = "Erro ao disparar sincronização: " + err.message;
        } finally {
            esconderCarregamento();
        }
    });
}
const FREQUENCIA_RECOMENDADA_POR_OBJETIVO = {
    ROUTINE_CONSISTENCY: 3,
    CARDIO_LONGEVITY: 3,
    HIGH_PERFORMANCE: 4
};

function atualizarRecomendacaoPorCategoria(category) {
    const recEl = document.getElementById("recomendacao-frequencia");
    if (!recEl) return;
    const diasSugeridos = FREQUENCIA_RECOMENDADA_POR_OBJETIVO[category] ?? 3;
    recEl.textContent = `Frequência semanal recomendada: ${diasSugeridos} dias por semana`;
}

async function carregarObjetivoAtivo() {
    const resumoEl = document.getElementById("resumo-objetivo-atual");
    const selectCatEl = document.getElementById("select-goal-category");
    const eventNameEl = document.getElementById("input-event-name");
    const eventDateEl = document.getElementById("input-event-date");
    const recEl = document.getElementById("recomendacao-frequencia");

    try {
        const resp = await fetch("/api/goals/active", { credentials: "same-origin" });
        if (!resp.ok) {
            if (resumoEl) resumoEl.textContent = "Objetivo ativo: faça login para visualizar ou definir.";
            if (selectCatEl) atualizarRecomendacaoPorCategoria(selectCatEl.value);
            return;
        }

        const data = await resp.json();
        const cat = data.category || "ROUTINE_CONSISTENCY";

        if (selectCatEl) selectCatEl.value = cat;
        if (eventNameEl) eventNameEl.value = data.eventName ?? "";
        if (eventDateEl) eventDateEl.value = data.eventDate ?? "";

        const freqBackend = data.recommendedWeeklyFrequency ?? FREQUENCIA_RECOMENDADA_POR_OBJETIVO[cat] ?? 3;
        if (recEl) {
            recEl.textContent = `Frequência semanal recomendada: ${freqBackend} dias por semana`;
        }

        if (resumoEl) {
            const label = data.categoryLabel || cat;
            const eventoTexto = data.eventName
                ? ` | Evento: ${data.eventName}${data.eventDate ? " (" + data.eventDate + ")" : ""}`
                : "";
            resumoEl.textContent = data.hasActiveGoal
                ? `Objetivo ativo: ${label}${eventoTexto}`
                : `Nenhum objetivo salvo ainda (padrão sugerido: ${label}).`;
        }
    } catch (err) {
        if (resumoEl) resumoEl.textContent = "Erro ao carregar objetivo ativo: " + err.message;
    }
}

const selectGoalCategoryEl = document.getElementById("select-goal-category");
if (selectGoalCategoryEl) {
    selectGoalCategoryEl.addEventListener("change", (event) => {
        atualizarRecomendacaoPorCategoria(event.target.value);
    });
}

carregarObjetivoAtivo();

carregarPerfil();