# S3 — Previsão por rodada específica: plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** entregar dois endpoints, servidos só do cache e do SQL Server (nunca da Nimbus):
- `GET /api/clima/previsoes/{regionId}/rodadas?de=&ate=`: listagem;
- `GET /api/clima/previsoes/{regionId}/rodadas/{rodada}?inicio=&fim=`: rodada específica.

**Architecture:** sobre o S2.
- O `PrevisaoHistoricoService` (app/previsao) lê o `RodadaPrevisaoRepositorio` (S2) e o `RegiaoRepository` (S1), este por `existsById`, porque regiões inativas e removidas também valem (S3-FR-07).
- A rodada específica passa por cache: L1 Caffeine e L2 Redis, na chave `clima:previsao:rodada:{regionId}:{runMs}`. A sobrescrita do S2 já apaga o L2 (D4 do S2) e passa a apagar também o L1 local.
- Os endpoints entram no `PrevisoesController` (S2), e os 404 próprios no `PrevisaoExceptionHandler` (S2).

**Tech Stack:** a do S2.

**Spec:** `docs/superpowers/specs/2026-10-15-clima-nimbus-previsao-por-rodada-spec.md`. Formato do item: spec S2 §5.1, já implementado em `PrevisaoRegiaoResponse.de`.

## Decisões da fase de plano (confirmar na aprovação)

- **E1. L1 com validade de 15 min.** A spec diz que a entrada da rodada não tem validade por tempo e é invalidada na sobrescrita. A invalidação, porém, só alcança o L2 (compartilhado) e o L1 **da instância que sobrescreveu**. Nos outros pods, o Caffeine continuaria servindo os valores antigos indefinidamente.
  - Proposta: L1 com `expireAfterWrite(15 min)`. A defasagem entre pods fica limitada a 15 min, o mesmo limite que o S2 já aceita para a rodada vigente.
  - Só a rodada vigente de um dia anterior pode ser sobrescrita (S2-FR-07/08). As demais rodadas são imutáveis, e para elas a validade só custa uma releitura do L2 a cada 15 min.
  - A alternativa é invalidar por pub/sub do Redis. É mais código e falha junto com o Redis.
- **E2. L2 com TTL de 24 h.** Sem TTL, cada rodada consultada fica no Redis para sempre, e o Redis é compartilhado com o `neoviapainel-backend`: são ~240 pontos por rodada, por região e por dia, com retenção indefinida. Com TTL de 24 h (renovado a cada gravação no L2), o histórico pouco usado sai do Redis e continua no SQL. Isso não muda a correção, porque o dado é o mesmo do SQL.
- **E3. A listagem confere a região antes da consulta.** Região inexistente dá 404 mesmo com período válido. Região existente sem rodadas dá 200 com `[]` (S3-FR-08/10). Validação de parâmetros (400) vem antes de tudo.
- **E4. Cache quente dispensa a checagem da região.** Na rodada específica, um acerto no cache responde sem consultar `regiao`, já que a FK garante que ela existe. Num acerto de cache, o p95 não paga a ida ao SQL.

## Global Constraints

- Tudo do S2 continua valendo (Global Constraints do plano do S2).
- **Sem tabela nova e sem migração.** O índice da listagem é o `uq_rodada_previsao_regiao_run` do S2.
- **Zero chamadas à Nimbus.** Todo IT confere que o delta de `nimbus.getRequestCount()` é 0.
- Datas: ISO-8601 **com fuso** via `ParametrosConsulta.instante` (S2). `de`/`ate` são obrigatórios, com `ate >= de` e `ate - de <= 90 dias`; exatamente 90 dias é aceito.
- `{rodada}` casa por igualdade exata do instante em UTC (S3-FR-05). `{rodada}` sem fuso ou mal formado → 400.
- Mensagens:
  - 400 "Parâmetros inválidos: <detalhe>";
  - 404 "Região não encontrada";
  - 404 "Rodada não encontrada para a região".
- Ordem da listagem: `run_datetime` decrescente. Cada item tem `rodada` e `obtidoEm`.
- Chaves: `clima:previsao:rodada:{regionId}:{runDatetimeEpochMs}`. L1 com `maximumSize(500)` e `expireAfterWrite(15 min)` (E1). L2 com TTL de 24 h (E2).
- Testes: `JAVA_HOME=/home/alvaro-zago/.jdks/temurin-17.0.20 sh ./mvnw -q test [-Dtest=...]`, e os ITs limpam também no `@AfterEach` (Ruling do Task 2 do S2).

## Review Focus

1. **`{rodada}` com offset ou com milissegundos.** `2026-10-14T00:00:00.000Z` e `2026-10-13T21:00:00-03:00` são o mesmo instante que `2026-10-14T00:00:00Z` e devem achar a rodada. Teste: `PrevisaoHistoricoApiIT.rodadaComOffsetEMilissegundos` (Task 3).
2. **Sobrescrita vista por outra instância.** O L1 de outro pod não é invalidado, por isso o L1 tem validade de 15 min (E1). Teste: `RodadaCacheIT.l1ExpiraEm15Min` (Task 1).
3. **Rodada do dia pedida por aqui enquanto o S2 ainda pode sobrescrevê-la.** O S2 apaga a entrada (L1 local + L2) depois do commit. Teste: `PrevisaoHistoricoServiceIT.sobrescritaInvalidaCache` (S3-AC-08, Task 2).
4. **Período no limite.** Exatamente 90 dias é aceito; 90 dias e 1 s é recusado. `de == ate` é aceito. Teste: `ParametrosConsultaTest.periodo` (Task 3).
5. **`regionId` não numérico, negativo ou fora de `long` no caminho.** Deve dar 400 "Parâmetros inválidos: …", não 500. Teste: `PrevisoesControllerTest.regionIdInvalido400` (Task 3).

---

### Task 1: Cache da rodada específica

**Files:**
- Modify: `src/main/java/br/com/neoviaengenharia/clima/infra/cache/PrevisaoCache.java`, `CacheConfig.java` (bean `previsaoRodadaL1`)
- Test: `src/test/java/br/com/neoviaengenharia/clima/infra/cache/RodadaCacheIT.java`; caso novo em `PrevisaoCacheRedisForaIT`

**Interfaces:**
- Consumes: `RodadaPrevisao` (S2).
- Produces, em `PrevisaoCache`:
  - `Optional<RodadaPrevisao> obterRodada(long regionId, Instant rodada)`: L1, depois L2 com promoção ao L1.
  - `void guardarRodada(RodadaPrevisao rodada)`: L1 + L2 com TTL de 24 h.
  - `void invalidarRodada(long regionId, Instant rodada)`, já existente: passa a apagar também o L1.
  - Falha do Redis: WARN `Falha no cache L2 (Redis): {}`.
  - `limparL1()` passa a limpar os dois L1.
- `Cache<String, RodadaPrevisao> previsaoRodadaL1` com `maximumSize(500)` e `expireAfterWrite(Duration.ofMinutes(15))`. Para o teste com relógio mutável, usar `.ticker(() -> clock.millis() * 1_000_000)` com o `Clock` injetado.

- [ ] **Step 1: Escrever os testes que falham** (`RodadaCacheIT` extends `IntegracaoBase`)
  - `guardaEDevolveDoL1`, `promoveDoL2`.
  - `chaveETtlNoRedis`: a chave é `clima:previsao:rodada:1359:1792022400000`, com TTL > 23 h e ≤ 24 h.
  - `preservaNulosENumeros`, como no `PrevisaoCacheIT` do S2.
  - `invalidarApagaL1EL2`: guarda e invalida → `obterRodada` vazio, mesmo com o L2 desligado do jogo, ou seja, sem depender da promoção do L2.
  - `l1ExpiraEm15Min`: guarda, apaga a chave do Redis direto (como faria outra instância) e avança o relógio 14 min → ainda presente pelo L1. Mais 1 min → vazio.
  - `PrevisaoCacheRedisForaIT.rodadaComRedisForaNaoLanca`: `guardarRodada`/`obterRodada`/`invalidarRodada` não lançam, e o L1 serve.
- [ ] **Step 2:** rodar `-Dtest='RodadaCacheIT,PrevisaoCacheRedisForaIT,PrevisaoCacheIT'`. Esperado: FAIL.
- [ ] **Step 3:** implementar.
- [ ] **Step 4:** rodar o mesmo. Esperado: PASS.
- [ ] **Step 5:** commit `feat: cache Caffeine → Redis da rodada específica`.

### Task 2: Serviço do histórico de previsões

**Files:**
- Modify: `src/main/java/br/com/neoviaengenharia/clima/infra/repo/RodadaPrevisaoRepositorio.java` (acrescentar `listar`)
- Create em `src/main/java/br/com/neoviaengenharia/clima/domain/previsao/`: `RodadaResumo.java`
- Create em `src/main/java/br/com/neoviaengenharia/clima/app/previsao/`: `PrevisaoHistoricoService.java`, `RegiaoNaoEncontradaException.java`, `RodadaNaoEncontradaException.java`
- Test: `src/test/java/br/com/neoviaengenharia/clima/app/previsao/PrevisaoHistoricoServiceIT.java`, `PrevisaoHistoricoServiceRedisForaIT.java`; caso novo em `RodadaPrevisaoRepositorioIT`

**Interfaces:**
- Consumes:
  - `RodadaPrevisaoRepositorio.buscar(long, Instant)` (S2);
  - `PrevisaoCache` (Task 1);
  - `RegiaoRepository.existsById` (S1);
  - `FiltroJanela` e `ItemPrevisao` (S2).
- Produces:
  - `record RodadaResumo(Instant rodada, Instant obtidoEm)`.
  - `List<RodadaResumo> RodadaPrevisaoRepositorio.listar(long regionId, Instant de, Instant ate)`: `run_datetime BETWEEN de AND ate ORDER BY run_datetime DESC`, sem ler os pontos.
  - `List<RodadaResumo> PrevisaoHistoricoService.listar(long regionId, Instant de, Instant ate)`. Região inexistente em `regiao` → `RegiaoNaoEncontradaException`.
  - `ItemPrevisao PrevisaoHistoricoService.rodada(long regionId, Instant rodada, Instant inicio, Instant fim)`:
    - ordem: cache, depois `existsById` (falso → `RegiaoNaoEncontradaException`), depois `buscar` (vazio → `RodadaNaoEncontradaException`) e `guardarRodada`;
    - devolve `ItemPrevisao(regionId, rodada filtrada, false, null)`.
  - `RegiaoNaoEncontradaException` ("Região não encontrada") e `RodadaNaoEncontradaException` ("Rodada não encontrada para a região"), ambas `RuntimeException`.
- A visibilidade dos factories de `ItemPrevisao` pode ser ampliada, se preciso.

- [ ] **Step 1: Escrever os testes que falham.** Reusar o `PrevisaoFixture` do S2 (catálogo com 1359 ativa, 1706 inativa e 1800 removida via `Regiao.marcarRemovida`). Todos conferem 0 requisições à Nimbus.
  - `RodadaPrevisaoRepositorioIT.listarNoPeriodoDecrescente`: rodadas de 10 a 15/10; período 12 a 14 → [14, 13, 12], com `obtidoEm`.
  - `listagemNoPeriodo` (S3-AC-01).
  - `periodoSemRodadas` (S3-AC-09): setembro → lista vazia.
  - `rodadaEspecifica` (S3-AC-03): a rodada 14/10 com pontos e variáveis nos nomes originais. A tradução é do DTO.
  - `filtroNaRodada` (S3-AC-04): 240 pontos horários a partir de 14/10T00Z; janela 06–08 → 3 pontos.
  - `rodadaInexistente` (S3-AC-05): `RodadaNaoEncontradaException`.
  - `regiaoInexistente` (S3-AC-06): 99999 → `RegiaoNaoEncontradaException` nas duas operações.
  - `inativaERemovidaComHistorico` (S3-AC-07): 1706 e 1800 → listagem e rodada normais.
  - `rodadaComHorarioDiferenteNaoCasa` (S3-FR-05): `14/10T01:00Z` → `RodadaNaoEncontradaException`.
  - `segundaLeituraVemDoCache`: depois da primeira leitura, apagar as linhas do SQL direto → a segunda ainda responde.
  - `sobrescritaInvalidaCache` (S3-AC-08):
    1. a rodada 15/10 com `temperature=35`, lida pelo S3, entra no cache;
    2. relógio em `2026-10-16T01:30Z` e tentativa antiga;
    3. `PrevisaoVigenteService.consultar([1359])`, com a Nimbus devolvendo 15/10 e `temperature=36`;
    4. `rodada(1359, 15/10)` → 36.
  - `PrevisaoHistoricoServiceRedisForaIT.redisForaServeDoSql` (S3-AC-10).
- [ ] **Step 2:** rodar `-Dtest='PrevisaoHistoricoServiceIT,PrevisaoHistoricoServiceRedisForaIT,RodadaPrevisaoRepositorioIT'`. Esperado: FAIL.
- [ ] **Step 3:** implementar.
- [ ] **Step 4:** rodar o mesmo. Esperado: PASS.
- [ ] **Step 5:** commit `feat: serviço do histórico de rodadas de previsão`.

### Task 3: Endpoints REST e OpenAPI

**Files:**
- Modify:
  - `src/main/java/br/com/neoviaengenharia/clima/ui/rest/PrevisoesController.java` (dois `@GetMapping` novos);
  - `ParametrosConsulta.java` (`obrigatorio`, `periodo`);
  - `src/main/java/br/com/neoviaengenharia/clima/ui/exceptions/PrevisaoExceptionHandler.java` (404 da região e da rodada; `MethodArgumentTypeMismatchException` → 400).
- Create em `src/main/java/br/com/neoviaengenharia/clima/ui/dto/`: `RodadasResponse.java`, `RodadaResumoResponse.java`
- Test:
  - `src/test/java/br/com/neoviaengenharia/clima/ui/rest/ParametrosConsultaTest.java`;
  - casos novos em `PrevisoesControllerTest`;
  - `src/test/java/br/com/neoviaengenharia/clima/ui/rest/PrevisaoHistoricoApiIT.java`.

**Interfaces:**
- Consumes: `PrevisaoHistoricoService` (Task 2), `PrevisaoRegiaoResponse.de` (S2).
- Produces:
  - `record RodadasResponse(long regionId, List<RodadaResumoResponse> rodadas)` e `record RodadaResumoResponse(Instant rodada, Instant obtidoEm)`, no formato da spec §5.1;
  - `GET /{regionId}/rodadas` → `RodadasResponse`;
  - `GET /{regionId}/rodadas/{rodada}` → `PrevisaoRegiaoResponse`, no formato da spec §5.2;
  - `ParametrosConsulta`:
    - `static Instant obrigatorio(String valor, String nome)`: ausente → "<nome> é obrigatório";
    - `static void periodo(Instant de, Instant ate)`: `ate < de` → "ate deve ser maior ou igual a de"; `> 90 dias` → "o período máximo é de 90 dias".
- `{regionId}` é `long` no `@PathVariable`. O tipo inválido vira `MethodArgumentTypeMismatchException`, e o handler do controller responde 400 "Parâmetros inválidos: regionId deve ser inteiro". `{rodada}` é `String` e passa por `ParametrosConsulta.instante(valor, "rodada")`.

- [ ] **Step 1: Escrever os testes que falham**
  - `ParametrosConsultaTest.periodo`:
    - `de == ate` OK;
    - exatamente 90 dias OK;
    - 90 dias + 1 s → exceção;
    - `ate < de` → exceção;
    - ausente → "de é obrigatório".
  - `PrevisoesControllerTest`, com `PrevisaoHistoricoService` mockado:
    - `listagemFormato`: JSON igual ao da spec §5.1;
    - `rodadaFormato`: JSON igual ao da spec §5.2, com `temperatura` e `precipitacao` traduzidas;
    - `periodoAcimaDe90Dias400` (S3-AC-02: `de=2026-01-01T00:00:00Z&ate=2026-10-15T00:00:00Z`);
    - `deAusente400`, `rodadaSemFuso400`;
    - `regionIdInvalido400` (`abc`, `99999999999999999999`);
    - `regiaoInexistente404`: `{"status":"NOT_FOUND","message":"Região não encontrada"}`;
    - `rodadaInexistente404`: `{"status":"NOT_FOUND","message":"Rodada não encontrada para a região"}`.
  - `PrevisaoHistoricoApiIT`, ponta a ponta com o SQL semeado, sempre com 0 requisições à Nimbus:
    - listagem 200;
    - rodada 200;
    - `rodadaComOffsetEMilissegundos`: `/rodadas/2026-10-13T21:00:00-03:00` e `/rodadas/2026-10-14T00:00:00.000Z` → 200;
    - `/api-docs` contém `/api/clima/previsoes/{regionId}/rodadas` e `/api/clima/previsoes/{regionId}/rodadas/{rodada}` (S3-FR-13).
- [ ] **Step 2:** rodar `-Dtest='ParametrosConsultaTest,PrevisoesControllerTest,PrevisaoHistoricoApiIT'`. Esperado: FAIL.
- [ ] **Step 3:** implementar, com `@Operation`/`@ApiResponse` (200, 400, 404) e `@Parameter` nos novos parâmetros.
- [ ] **Step 4:** rodar `./mvnw -q test` (suíte toda). Esperado: PASS.
- [ ] **Step 5:** commit `feat: endpoints REST do histórico de rodadas`.
