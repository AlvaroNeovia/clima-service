# S4 — Precipitação observada: plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** entregar `GET /api/clima/precipitacoes-observadas/{regionId}?inicio=&fim=`, com a série horária observada servida por Caffeine → Redis → SQL e a Nimbus chamada no máximo uma vez por hora e região, só para as horas que as regras S4-FR-05..09 exigem.

**Architecture:** sobre o S1, no layout `domain/app/infra/ui`.
- `domain/observado` guarda as regras puras: janela, estado de cada hora (definitiva, a buscar), intervalo da chamada e aplicação do resultado (dado, `sem_dado`, tentativa). Tudo recebe `agora` como parâmetro.
- `PrecipitacaoObservadaService` (app) lê o estado de cada hora em `ObservadoCache` (L1/L2) e completa as faltas no SQL. Se houver horas a buscar, abre uma transação que trava a linha da região em `regiao` (lock pessimista), relê o SQL, chama a Nimbus uma vez, grava e só então atualiza L1/L2.
- A validade do cache de uma hora não definitiva é `ultimaTentativaEm + 1h`, o mesmo instante em que ela volta a poder ser buscada. Por isso o L1 de outra instância nunca fica com uma hora mais velha do que o SQL, sem precisar de invalidação distribuída.

**Tech Stack:** a do S1 (Java 17, Spring Boot 3.3.2, `RestClient`, Spring Data JPA, Flyway, H2 modo `MSSQLServer` nos testes, Caffeine, Spring Data Redis, springdoc 2.2.0, JUnit 5, Testcontainers Redis, OkHttp `mockwebserver`).

**Spec:** `docs/superpowers/specs/2026-10-15-clima-nimbus-precipitacao-observada-spec.md` (PRD `docs/prd/2026-10-15-clima-nimbus-prd.md`).

## Global Constraints

- Tudo das Global Constraints do plano do S1 continua valendo (pacote, rotas `/api/clima/`, JSON camelCase, instantes ISO-8601 UTC, sem credenciais em log ou erro, SQL portátil SQL Server/H2).
- Migração: **`V3__criar_precipitacao_observada.sql`**. A V2 é do S2.
- Coordenação de merge: não editar os arquivos que o S1 ainda vai criar (`ExceptionManager`, `RegiaoRecusadaException`, `Response`, `RegioesController`, config Jackson, Dockerfile/k8s/CI). Em arquivos do S1 que o S2 também deve tocar (`NimbusClient`, `RegiaoRepository`, `CacheConfig`), preferir arquivo novo; no `NimbusClient`, o acréscimo fica em um bloco contíguo no fim da classe, sem mexer em `listarRegioes`.
- Chave de cache por hora: `clima:observado:{regionId}:{dataHoraEpochMs}`.
- Janela: `inicio`/`fim` ISO-8601 com fuso, hora cheia (em UTC), `fim > inicio`, `fim - inicio <= 7 dias`, `fim` exclusivo.
- Hora corrente = `agora` truncado à hora. Recente = `H+1h >= agora-6h` e não corrente. Antiga = `H+1h < agora-6h`.
- Limite por hora e região: busca permitida se `ultimaTentativaEm == null` ou `agora > ultimaTentativaEm + 1h` (literal: "há mais de 1 hora").
- Mensagens: "Parâmetros inválidos: <detalhe>" (400), "Janela máxima de 7 dias" (400), "Região não encontrada" (404), "Fonte de dados climáticos indisponível" (503).
- Comandos: `JAVA_HOME=/home/alvaro-zago/.jdks/temurin-17.0.20 sh ./mvnw -q test` (suíte) e o mesmo com `-Dtest=<Classe>` (focado). Docker ativo.
- Testes de integração com o `MockWebServer` estático `nimbus`: conferir a contagem por delta (`getRequestCount()` antes/depois) e limpar a fila com `setDispatcher(new QueueDispatcher())` no `@BeforeEach`.

## Decisões de plano (a confirmar na aprovação)

- **D1. Região não atribuída na Nimbus.** A doc da Nimbus diz que a região "é recusada", sem dizer o status. Proposta: HTTP **404** do `data-consistency` vira `NimbusRegiaoRecusadaException`; o serviço loga WARN e responde 404 "Região não encontrada", sem gravar tentativa nem `sem_dado`. Os outros 4xx seguem como falha comum (`ERRO_HTTP`).
- **D2. Existência e situação da região.** Antes de ler `regiao`, o serviço chama `CatalogoRegioesService.obter()` (na prática um acerto de L1), para que o catálogo vencido seja renovado como em S1-FR-08. Se o catálogo nunca foi obtido e a Nimbus está fora, sai 503 por `FonteIndisponivelException`. Depois lê `regiao` direto: ausente → 404; `inativa` ou `removida` → só o gravado.
- **D3. "Horas gravadas" em S4-FR-12/13** = horas com dado na janela considerada. Uma janela só com `sem_dado` e a Nimbus fora → 503.
- **D4. Retenção operacional do cache.** As horas definitivas não vencem por regra, mas o L2 é o Redis compartilhado com o `neoviapainel-backend`. Proposta: TTL de 30 dias no Redis para horas definitivas (refeitas do SQL se expirarem) e L1 limitado a 200 000 entradas. Isso não muda o comportamento visível.
- **D5. Trava.** Lock pessimista na linha da região em `regiao` (timeout de 15 s, como o S1), sem tabela nova. Se o lock esgotar o tempo, o serviço trata como falha da Nimbus (S4-FR-12/13), em vez de 500.

## Review Focus

1. **Hora recente que vira antiga com tentativa anterior.** A hora 11:00Z, tentada às 17:10Z e não devolvida, já é antiga às 18:20Z. Ela deve ser tentada mais uma vez e, se continuar ausente, virar `sem_dado`. Ela não pode ficar sem registro para sempre nem virar `sem_dado` sem nova tentativa. Teste: `EstadoHoraTest.antigaSemRegistroComTentativaVelhaBusca` (Task 1) e `PrecipitacaoObservadaServiceIT.recenteQueEnvelheceViraSemDado` (Task 5).
2. **Valores decimais e nulos na volta do banco.** `0` da Nimbus e `0.0000` do `DECIMAL` devem sair iguais no JSON. Nada de `1E+2`, e `null` continua `null`. Teste: `ObservadoPersistenciaIT.decimalVoltaNormalizado` (Task 2) e `PrecipitacaoObservadaApiIT.formatoDaResposta` (Task 7).
3. **Nimbus devolve itens inesperados.** Hora fora do intervalo, hora fora de hora cheia, hora definitiva dentro do intervalo e `datetime` repetido não são gravados nem regravados. Teste: `AplicacaoBuscaTest.ignoraHorasNaoPedidas` (Task 1).
4. **Lock esgotado com a Nimbus lenta.** Requisições na fila não podem virar 500. Teste: `PrecipitacaoObservadaServiceIT.lockEsgotadoServeGravado` (Task 5, com o `TravaRegiaoRepository` espionado lançando `PessimisticLockingFailureException`).
5. **Fuso diferente de UTC.** `inicio=2026-10-14T00:00:00-03:00` é válido e vira 03:00Z. Já `+05:30` deixa a hora fora de hora cheia em UTC e dá 400. Teste: `JanelaObservadaTest.aceitaOffsetEConverteParaUtc` e `offsetMeiaHoraForaDeHoraCheia` (Task 1).

---

### Task 1: Regras de domínio do observado

**Files:**
- Create: `src/main/java/br/com/neoviaengenharia/clima/domain/observado/` → `Estacao.java`, `LeituraHora.java`, `HoraNimbus.java`, `EstadoHora.java`, `JanelaObservada.java`, `ParametrosInvalidosException.java`, `JanelaExcedidaException.java`, `PlanoBusca.java`, `AplicacaoBusca.java`
- Test: `src/test/java/br/com/neoviaengenharia/clima/domain/observado/` → `JanelaObservadaTest.java`, `EstadoHoraTest.java`, `PlanoBuscaTest.java`, `AplicacaoBuscaTest.java`

**Interfaces:**
- Produces:
  - `record Estacao(long id, String nome, String codigo)`.
  - `record LeituraHora(Estacao estacao, BigDecimal precipitacaoAcumulada, BigDecimal precipitacaoMaxima, Instant dataHoraMaxima, BigDecimal primeiraLeitura, Instant dataHoraPrimeiraLeitura, Boolean interpolado)`. O construtor compacto normaliza os `BigDecimal` não nulos com `setScale(4, HALF_UP).stripTrailingZeros()` e, se a escala ficar negativa, `setScale(0)`.
  - `record HoraNimbus(Instant dataHora, LeituraHora leitura)`: item já validado vindo da Nimbus.
  - `record EstadoHora(Instant dataHora, LeituraHora leitura, Instant obtidoEm, boolean semDado, Instant ultimaTentativaEm)`, em que `leitura`/`obtidoEm` são nulos sem dado e `ultimaTentativaEm` é nulo se nunca tentada. Métodos:
    - `static EstadoHora vazio(Instant dataHora)`;
    - `boolean temDado()`;
    - `boolean definitiva(Instant agora)` = `semDado || (temDado() && antiga(agora))`;
    - `boolean deveBuscar(Instant agora)` = `!definitiva(agora) && (ultimaTentativaEm == null || agora.isAfter(ultimaTentativaEm.plus(1h)))`;
    - `Optional<Instant> validoAte(Instant agora)`: vazio para definitiva (sem prazo); `ultimaTentativaEm + 1h` se houver tentativa; sem tentativa e não definitiva, a hora não é cacheável e o método devolve `Optional.of(Instant.MIN)`;
    - `static boolean antiga(Instant dataHora, Instant agora)`.
  - `record JanelaObservada(Instant inicio, Instant fim)` com `static JanelaObservada de(String inicio, String fim)` e `List<Instant> horasAte(Instant agora)` (horas de `[inicio, min(fim, horaCorrente+1h))`, em ordem).
  - `ParametrosInvalidosException(String detalhe)`, com mensagem "Parâmetros inválidos: " + detalhe, e `JanelaExcedidaException`, com mensagem "Janela máxima de 7 dias". As duas são `RuntimeException`.
  - `record PlanoBusca(Instant inicio, Instant fimExclusivo)` com `static PlanoBusca de(Collection<Instant> horasABuscar)` (menor; maior + 1h).
  - `AplicacaoBusca.aplicar(Map<Instant, EstadoHora> atuais, Set<Instant> buscadas, List<HoraNimbus> recebidas, Instant agora) -> Map<Instant, EstadoHora>`: devolve só os estados alterados. Recebida e buscada → leitura nova, `obtidoEm=agora`, `semDado=false`. Buscada, ausente e antiga → `semDado=true`, sem leitura. Buscada, ausente e não antiga → mantém a leitura que tinha. Em todos os casos `ultimaTentativaEm=agora`. Recebida e não buscada → ignorada.

- [ ] **Step 1: Escrever os testes que falham**
  - `JanelaObservadaTest`: `aceitaJanelaDeUmDia` (`2026-10-14T00:00:00Z`..`2026-10-15T00:00:00Z`); `aceitaExatamente7Dias`; `recusaMaisDe7Dias` (`2026-10-01T00:00:00Z`..`2026-10-09T00:00:00Z` → `JanelaExcedidaException`, "Janela máxima de 7 dias"); `recusaForaDeHoraCheia` (`2026-10-14T00:30:00Z` → `ParametrosInvalidosException`); `recusaSemFuso` (`2026-10-14T00:00:00`); `recusaFimIgualOuAntesDoInicio`; `recusaAusente` (`null` ou em branco; o detalhe cita o nome do parâmetro); `aceitaOffsetEConverteParaUtc`; `offsetMeiaHoraForaDeHoraCheia`; `horasAteCortaNaHoraCorrente` (S4-AC-09: agora `2026-10-15T18:20Z`, janela 12:00Z..`2026-10-16T00:00Z` → 12:00..18:00, 7 horas); `janelaTodaNoFuturoNaoTemHoras`.
  - `EstadoHoraTest` (agora `2026-10-15T18:20Z`): `correnteSemTentativaBusca` (18:00); `recenteComTentativaHa70MinBusca` (S4-AC-04: 12:00, tentativa 17:10); `recenteComTentativaHa20MinNaoBusca` (S4-AC-05: tentativa 18:00); `tentativaHaExatamente1hNaoBusca`; `antigaComDadoEhDefinitiva` (11:00 com leitura); `semDadoEhDefinitiva`; `antigaNuncaTentadaBusca` (S4-FR-07); `antigaSemRegistroComTentativaVelhaBusca`; `limiteRecenteAntiga` (11:00 é antiga e 12:00 é recente, com agora 18:20); `validoAteDefinitivaSemPrazo`; `validoAteRecente` (tentativa 17:10 → 18:10).
  - `PlanoBuscaTest`: `intervaloDaMenorAMaiorMaisUmaHora` (S4-AC-02: as 24 horas de 2026-10-10 → `2026-10-10T00:00Z`..`2026-10-11T00:00Z`); `umaHoraSo`.
  - `AplicacaoBuscaTest` (agora `2026-10-15T18:00Z`): `gravaRecebidasEMarcaAntigaAusenteComoSemDado` (S4-AC-02: 24 buscadas em 2026-10-10, 23 recebidas → 23 com leitura, 1 `semDado`, as 24 com `ultimaTentativaEm=agora`); `recenteAusenteNaoViraSemDado` (S4-AC-06); `recenteAusenteMantemLeituraAnterior`; `recebidaSubstituiGravada` (S4-FR-06); `ignoraHorasNaoPedidas`; `estacaoPorHora` (S4-AC-10: 00–11 com 3078 e 12–23 com 3090).
  - `LeituraHoraTest.normalizaDecimais`: `0.0000` → `0`, `100.0000` → `100` (com `toString()` `"100"`), `0.12345` → `0.1235` e `null` → `null`.
- [ ] **Step 2:** rodar `-Dtest='JanelaObservadaTest,EstadoHoraTest,PlanoBuscaTest,AplicacaoBuscaTest,LeituraHoraTest'`. Esperado: FAIL na compilação.
- [ ] **Step 3:** implementar. `JanelaObservada.de` usa `OffsetDateTime.parse` (que exige offset) → `toInstant()`; hora cheia é `epochSecond % 3600 == 0 && nano == 0`. A ordem das checagens é ausente → formato → hora cheia → ordem → 7 dias.
- [ ] **Step 4:** rodar o mesmo. Esperado: PASS.
- [ ] **Step 5:** commit `feat: regras de domínio da precipitação observada`.

### Task 2: Migração e persistência do observado

**Files:**
- Create: `src/main/resources/db/migration/V3__criar_precipitacao_observada.sql`
- Create: `src/main/java/br/com/neoviaengenharia/clima/infra/repo/HoraObservada.java`, `HoraObservadaRepository.java`, `TentativaObservado.java`, `TentativaObservadoId.java`, `TentativaObservadoRepository.java`, `TravaRegiaoRepository.java`
- Test: `src/test/java/br/com/neoviaengenharia/clima/infra/repo/ObservadoPersistenciaIT.java`

**Interfaces:**
- Consumes: `EstadoHora`, `LeituraHora` e `Estacao` (Task 1).
- Produces:
  - Tabelas da spec §7.1 e §7.2. `precipitation_*` são `DECIMAL(12,4)`, `station_name` é `NVARCHAR(200)` e `station_code` é `NVARCHAR(100)`. Há FKs para `regiao(region_id)`, a constraint `uq_hora_observada UNIQUE (region_id, data_hora)` e a PK `(region_id, data_hora)` em `tentativa_observado`.
  - `@Entity HoraObservada` (`hora_observada`), com `static HoraObservada de(long regionId, EstadoHora e)`, `void atualizarCom(EstadoHora e)` e `LeituraHora leitura()` (nula se `semDado`).
  - `HoraObservadaRepository.findAllByRegionIdAndDataHoraGreaterThanEqualAndDataHoraLessThan(long regionId, Instant de, Instant ate): List<HoraObservada>`.
  - `@Entity TentativaObservado` (`@IdClass(TentativaObservadoId)`), com `registrar(Instant)`, e `TentativaObservadoRepository.findAllByRegionIdAndDataHoraGreaterThanEqualAndDataHoraLessThan(...)`.
  - `TravaRegiaoRepository extends Repository<Regiao, Long>`: `@Lock(PESSIMISTIC_WRITE)`, hint `jakarta.persistence.lock.timeout=15000`, `Optional<Regiao> travar(long regionId)`. Fica em arquivo novo para não tocar o `RegiaoRepository` do S1.

- [ ] **Step 1: Escrever os testes que falham** (`@SpringBootTest` + profile `test`, como o `CatalogoPersistenciaIT`)
  - `migracaoValidaContraEntidades`
  - `gravaELeHoraComDadoENulos` (`primeiraLeitura=null`, `interpolado=false`)
  - `gravaSemDado` (campos de dado nulos)
  - `unicoPorRegiaoEHora` (segundo insert da mesma `(region_id, data_hora)` → `DataIntegrityViolationException`)
  - `buscaPorJanelaEhFimExclusivo`
  - `decimalVoltaNormalizado` (grava `0` e `1.25`; `leitura()` devolve `0` e `1.25`)
  - `travarDevolveRegiaoDentroDeTransacao`
- [ ] **Step 2:** rodar `-Dtest=ObservadoPersistenciaIT`. Esperado: FAIL.
- [ ] **Step 3:** implementar a migração e as entidades. Os instantes vão em `DATETIME2` (UTC, por `hibernate.jdbc.time_zone`).
- [ ] **Step 4:** rodar o mesmo e depois a suíte toda. Esperado: PASS.
- [ ] **Step 5:** commit `feat: persistência da precipitação observada`.

### Task 3: Cliente da Nimbus — data-consistency

**Files:**
- Modify: `src/main/java/br/com/neoviaengenharia/clima/infra/nimbus/NimbusClient.java` (só acrescenta um bloco no fim da classe)
- Create: `src/main/java/br/com/neoviaengenharia/clima/infra/nimbus/NimbusRegiaoRecusadaException.java`
- Test: `src/test/java/br/com/neoviaengenharia/clima/infra/nimbus/NimbusObservadoClientTest.java`

**Interfaces:**
- Consumes: `HoraNimbus`, `LeituraHora` e `Estacao` (Task 1).
- Produces:
  - `NimbusClient.buscarObservado(long regionId, Instant inicio, Instant fimExclusivo): List<HoraNimbus>`;
  - `NimbusRegiaoRecusadaException(long regionId)` (D1), com a mensagem "Nimbus recusou a região <id>".
  - As falhas seguem como `NimbusIndisponivelException` com o mesmo `Motivo` do S1.

- [ ] **Step 1: Escrever os testes que falham** (MockWebServer próprio, como o `NimbusClientTest`)
  - `enviaQueryEHeaders`: path `/manage/representative-stations/1674/data-consistency?start_date=1790121600000&end_date=1790208000000&aggregation=hourly&variable=precipitation`, mais `api_key` e `authorization` exatos.
  - `traduzCampos` (S4-AC-15): o item do exemplo da doc vira `HoraNimbus(2026-09-23T00:00Z, LeituraHora(Estacao(3078,"Prudentópolis","mg_h-823"), 0, 0, 2026-09-23T00:00Z, null, null, false))`.
  - `ignoraPeriodMetadataECampoDesconhecido`
  - `dataVaziaEhListaVazia` (`"data": []` → lista vazia, sem erro)
  - `regionIdDivergenteEhCorpoInvalido` (S4-FR-17: raiz com `region_id` 1675 → `CORPO_INVALIDO`; item com `region_id` divergente → `CORPO_INVALIDO`)
  - `semDataEhCorpoInvalido`; `itemSemDatetimeOuStationIdEhCorpoInvalido`
  - `status404EhRegiaoRecusada` (D1); `status401e403` (`CREDENCIAL_RECUSADA` + log ERROR "recusou as credenciais"); `status503`; `timeout`; `corpoNaoJsonEhCorpoInvalido`
  - `logTemRegiaoIntervaloStatusEDuracaoSemCredencial` (`OutputCaptureExtension`: contém `data-consistency`, `regiao=1674`, os dois epoch, `status=200`, `duracaoMs` e nenhuma das chaves)
- [ ] **Step 2:** rodar `-Dtest=NimbusObservadoClientTest`. Esperado: FAIL na compilação.
- [ ] **Step 3: Implementar**
  - O bloco novo traz o método público, um `private <T> T executarObservado(...)` com o mesmo mapeamento de erros do `listarRegioes` (mais o 404 → `NimbusRegiaoRecusadaException`) e os DTOs `ObservadoDto` e `HoraObservadaDto`. Os DTOs usam `@JsonIgnoreProperties(ignoreUnknown = true)` e campos boxed.
  - Log INFO: `nimbus GET /manage/representative-stations/{id}/data-consistency regiao={} inicio={} fim={} status={} duracaoMs={}`.
  - Unificar o tratamento de erros com o `listarRegioes` fica como follow-up, depois do merge com o S2.
- [ ] **Step 4:** rodar o mesmo e o `NimbusClientTest`. Esperado: PASS.
- [ ] **Step 5:** commit `feat: cliente da Nimbus para a precipitação observada`.

### Task 4: Cache multinível das horas observadas

**Files:**
- Create: `src/main/java/br/com/neoviaengenharia/clima/infra/cache/ObservadoCacheConfig.java`, `ObservadoCache.java`
- Test: `src/test/java/br/com/neoviaengenharia/clima/infra/cache/ObservadoCacheIT.java`, `ObservadoCacheRedisForaIT.java`

**Interfaces:**
- Consumes: `EstadoHora` (Task 1).
- Produces:
  - bean `Cache<String, EstadoHora> observadoL1` (Caffeine, `maximumSize(200_000)`, `Expiry` por entrada até `validoAte`, sem prazo quando ele vier vazio);
  - `ObservadoCache.obter(long regionId, List<Instant> horas): Map<Instant, EstadoHora>`, que traz só os acertos ainda válidos para `clock.instant()`. Busca no L1 e depois, para as faltas, faz um `multiGet` no L2, promovendo o que achar para o L1;
  - `ObservadoCache.guardar(long regionId, Collection<EstadoHora> estados)`: ignora os não cacheáveis (`validoAte` ≤ agora). L2 com TTL = `validoAte - agora`, ou 30 dias para as definitivas (D4);
  - `static String chave(long regionId, Instant dataHora)` = `clima:observado:{regionId}:{epochMs}`;
  - `void limparL1()` (package-private, para testes).
  - Falha do Redis → WARN `Falha no cache L2 (Redis): {}` e segue (S4-FR-15).

- [ ] **Step 1: Escrever os testes que falham**
  - `ObservadoCacheIT` (extends `IntegracaoBase`): `chaveNoFormatoDaSpec` (`clima:observado:1674:1790121600000`); `guardaEDevolveDoL1`; `promoveDoL2` (com um `multiGet`); `recenteVenceEmTentativaMaisUmaHora`; `definitivaNaoVenceETemTtlDe30DiasNoRedis` (`getExpire` entre 29 e 30 dias); `naoGuardaNaoCacheavel`; `nulosSobrevivemAoRedis` (`primeiraLeitura=null`).
  - `ObservadoCacheRedisForaIT` (extends `IntegracaoRedisForaBase`): `redisForaNaoLancaELoga`.
- [ ] **Step 2:** rodar `-Dtest='ObservadoCacheIT,ObservadoCacheRedisForaIT'`. Esperado: FAIL.
- [ ] **Step 3:** implementar seguindo o `CatalogoCache` (JSON via o `ObjectMapper` do Spring).
- [ ] **Step 4:** rodar o mesmo. Esperado: PASS.
- [ ] **Step 5:** commit `feat: cache Caffeine → Redis das horas observadas`.

### Task 5: Serviço da precipitação observada

**Files:**
- Create: `src/main/java/br/com/neoviaengenharia/clima/app/observado/PrecipitacaoObservadaService.java`, `ObservadoResultado.java`, `RegiaoNaoEncontradaException.java`
- Test: `src/test/java/br/com/neoviaengenharia/clima/app/observado/PrecipitacaoObservadaServiceIT.java`, `ObservadoFixture.java`, `PrecipitacaoObservadaServiceRedisForaIT.java`

**Interfaces:**
- Consumes: Tasks 1–4; `CatalogoRegioesService.obter()`, `RegiaoRepository`, `FonteIndisponivelException` (S1).
- Produces:
  - `record ObservadoResultado(long regionId, JanelaObservada janela, boolean desatualizado, List<EstadoHora> horas)`: só horas com dado, em ordem crescente;
  - `PrecipitacaoObservadaService.consultar(long regionId, JanelaObservada janela): ObservadoResultado`;
  - `RegiaoNaoEncontradaException extends RuntimeException` ("Região não encontrada").

Algoritmo (o que os testes não determinam sozinhos):

```
agora = clock.instant(); horas = janela.horasAte(agora)
catalogo.obter()                                  // D2; FonteIndisponivelException sobe (503)
regiao = regioes.findById(id) ou RegiaoNaoEncontradaException
se horas vazio → resultado vazio, desatualizado=false
estados = cache.obter(id, horas); faltas → SQL (horas + tentativas de [min, max+1h)) → cache.guardar(lidos)
se regiao.inativa || regiao.removida → resultado(estados), false     // S4-FR-04, sem Nimbus
se nenhuma h com estados[h].deveBuscar(agora) → resultado(estados), false
r = tx { travaRegiao.travar(id); estados = SQL da janela inteira;
         aBuscar = horas com deveBuscar(agora); se vazio → Ok(estados)
         try recebidas = nimbus.buscarObservado(id, PlanoBusca.de(aBuscar))
         catch NimbusIndisponivelException → Falhou(estados)           // nada gravado (S4-FR-12)
         alterados = AplicacaoBusca.aplicar(estados, aBuscar, recebidas, agora); salvar; Ok(estados ∪ alterados, alterados) }
  catch PessimisticLockingFailureException → Falhou(estados lidos fora da trava)   // D5
  NimbusRegiaoRecusadaException → log WARN "Nimbus recusou a região {}"; RegiaoNaoEncontradaException   // D1
Ok → cache.guardar(alterados) depois do commit; resultado(estados), false
Falhou → algum com dado ? resultado(estados), true : FonteIndisponivelException
```

- [ ] **Step 1: Escrever os testes que falham** (extends `IntegracaoBase`, `relogio` e contagem por delta do `nimbus`; o `ObservadoFixture` limpa as tabelas novas, o L1/L2 do observado e a fila, e cadastra as regiões 1674 ativa e 1706 inativa no catálogo, com uma `BuscaCatalogo` vigente, para o `catalogo.obter()` não chamar a Nimbus)
  - `janelaDefinitivaNaoChamaNimbus` (S4-AC-01)
  - `primeiraConsultaDeJanelaAntiga` (S4-AC-02: 1 chamada com `start_date=1791590400000` e `end_date=1791676800000`, ou seja 2026-10-10T00:00Z e 2026-10-11T00:00Z; 23 horas gravadas, 1 `sem_dado` e 23 na resposta)
  - `semDadoNaoVoltaANimbus` (S4-AC-03)
  - `reconciliacaoDasUltimas6h` (S4-AC-04: query de 12:00Z a 19:00Z; o 12:00 recebido sobrescreve o gravado; 06–11 vêm do gravado)
  - `limiteDeUmaVezPorHora` (S4-AC-05)
  - `recenteAusenteTentaDeNovo` (S4-AC-06) e `recenteQueEnvelheceViraSemDado` (Review Focus 1)
  - `janelaNoFuturo` (S4-AC-09)
  - `estacaoVariavel` (S4-AC-10)
  - `inativaServeGravadoSemNimbus` (S4-AC-11), `removidaServeGravadoSemNimbus` e `inativaSemNadaDevolveVazio`
  - `regiaoInexistente` (S4-AC-12: `RegiaoNaoEncontradaException` e 0 chamadas)
  - `nimbusForaComGravado` (S4-AC-13: `desatualizado=true`, nenhum `sem_dado` e nenhuma tentativa gravada)
  - `nimbusForaSemGravado` (S4-AC-14: timeout → `FonteIndisponivelException`)
  - `nimbusRecusaRegiao` (D1: 404 da Nimbus → `RegiaoNaoEncontradaException` e log WARN)
  - `segundaLeituraVemDoCache` (sem SQL: apaga as linhas por baixo depois da 1ª chamada e a 2ª devolve igual)
  - `lockEsgotadoServeGravado` (Review Focus 4)
  - `concorrenciaFazUmaChamada` (S4-AC-16: 8 threads, resposta com 300 ms de atraso, exatamente 1 chamada)
  - `PrecipitacaoObservadaServiceRedisForaIT.redisForaServeDoSql` (S4-AC-17: 200 normal, 0 chamadas e log "Falha no cache L2")
- [ ] **Step 2:** rodar `-Dtest='PrecipitacaoObservadaServiceIT,PrecipitacaoObservadaServiceRedisForaIT'`. Esperado: FAIL.
- [ ] **Step 3:** implementar pelo algoritmo, com `TransactionTemplate`, como o `CatalogoRegioesService`.
- [ ] **Step 4:** rodar o mesmo e depois a suíte toda. Esperado: PASS.
- [ ] **Step 5:** commit `feat: serviço da precipitação observada com reconciliação e fallback`.

### Task 6: Merge do S1

- [ ] **Step 1:** `git merge feat/clima-regioes` (sem rebase), resolvendo conflitos a favor do S1 nos arquivos dele.
- [ ] **Step 2:** conferir os nomes reais de `ExceptionManager`, `Response`, `RegiaoRecusadaException` e da config Jackson, e ajustar a Task 7 se divergirem do plano do S1.
- [ ] **Step 3:** rodar a suíte toda. Esperado: PASS.

### Task 7: API REST, erros e OpenAPI

**Files:**
- Create: `src/main/java/br/com/neoviaengenharia/clima/ui/rest/PrecipitacaoObservadaController.java`
- Create: `src/main/java/br/com/neoviaengenharia/clima/ui/dto/PrecipitacaoObservadaResponse.java`, `HoraObservadaResponse.java`, `EstacaoResponse.java`
- Create: `src/main/java/br/com/neoviaengenharia/clima/ui/exceptions/ObservadoExceptionHandler.java`
- Test: `src/test/java/br/com/neoviaengenharia/clima/ui/rest/PrecipitacaoObservadaControllerTest.java`, `PrecipitacaoObservadaApiIT.java`

**Interfaces:**
- Consumes: `PrecipitacaoObservadaService`, `ObservadoResultado`, `JanelaObservada.de`, as exceções das Tasks 1 e 5; `Response` e `ExceptionManager` do S1.
- Produces:
  - `GET /api/clima/precipitacoes-observadas/{regionId}`, com `inicio`/`fim` como `@RequestParam(required = false) String` (a validação fica em `JanelaObservada.de`);
  - DTOs no formato exato da spec §6.1 (`inicio`/`fim` ecoam a janela pedida);
  - `ObservadoExceptionHandler` (`@RestControllerAdvice(assignableTypes = PrecipitacaoObservadaController.class)`, `@Order(HIGHEST_PRECEDENCE)`): `ParametrosInvalidosException`/`MethodArgumentTypeMismatchException` → 400, `JanelaExcedidaException` → 400 e `RegiaoNaoEncontradaException` → 404, todos com o `Response` do S1. `FonteIndisponivelException` e `Exception` caem no `ExceptionManager`.

- [ ] **Step 1: Escrever os testes que falham**
  - `PrecipitacaoObservadaControllerTest` (`@WebMvcTest`, serviço mockado):
    - `formatoDaResposta`: o JSON da spec §6.1, com `"primeiraLeitura":null`, `"precipitacaoAcumulada":0` e instantes `"...Z"`;
    - `semParametros400` ("Parâmetros inválidos: inicio é obrigatório");
    - `foraDeHoraCheia400` (S4-AC-08);
    - `janelaMaiorQue7Dias400` (S4-AC-07: `{"status":"BAD_REQUEST","message":"Janela máxima de 7 dias"}`);
    - `regionIdNaoNumerico400`;
    - `regiaoInexistente404`;
    - `fonteIndisponivel503`.
  - `PrecipitacaoObservadaApiIT` (extends `IntegracaoBase`):
    - `pontaAPonta` (valor demonstrável: 24 horas e a 2ª chamada sem Nimbus);
    - `apiDocsTemEndpoint` (S4-FR-18: `/api-docs` contém `/api/clima/precipitacoes-observadas/{regionId}`);
    - `p95ComCacheQuente` (7 dias = 168 horas gravadas, 1 chamada de aquecimento e 100 chamadas HTTP medidas; p95 < 200 ms). Se falhar, registrar como achado para o PRD, sem mudar o escopo.
- [ ] **Step 2:** rodar `-Dtest='PrecipitacaoObservadaControllerTest,PrecipitacaoObservadaApiIT'`. Esperado: FAIL.
- [ ] **Step 3:** implementar, com `@Operation` e `@ApiResponse` (200, 400, 404, 503), no estilo do `RegioesController`.
- [ ] **Step 4:** rodar a suíte toda. Esperado: PASS.
- [ ] **Step 5:** commit `feat: endpoint REST da precipitação observada`.

## Fora deste plano

- Manifests, Dockerfile e CI: o S1 cuida, e o S4 não precisa de variável nova.
- Unificar o tratamento de erros de `listarRegioes` e `buscarObservado` no `NimbusClient`: follow-up depois do merge com o S2.
