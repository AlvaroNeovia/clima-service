# S2 — Previsão vigente: plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** entregar `GET /api/clima/previsoes?regioes=&inicio=&fim=` com a rodada vigente de até 20 regiões. A Nimbus (`POST /retrieve-forecasts`) só é chamada quando as regras em UTC exigem. Cada rodada fica gravada como histórico.

**Architecture:** layout `domain/app/infra/ui` do S1, em `br.com.neoviaengenharia.clima`.
- Regras puras em `domain/previsao`: decisão "buscar ou usar a gravada", janela enviada, rodada da região, comparação, tradução e filtro.
- O `PrevisaoVigenteService` (app) faz o fluxo em três passos:
  1. valida cada região com `ValidacaoRegiao` (S1);
  2. tenta o `PrevisaoCache` (L1 Caffeine + L2 Redis);
  3. decide pelo SQL, ainda sem trava.
- As regiões que precisam de dado novo vão a uma transação que trava, em ordem crescente de `region_id`, as linhas de `tentativa_previsao` (lock pessimista, como em `ControleCatalogoRepository.travar()`). Dentro da trava, o serviço:
  - decide de novo;
  - registra a tentativa;
  - faz **uma** chamada à Nimbus;
  - grava as rodadas.
- Rodadas e pontos são lidos e gravados com `JdbcTemplate` (insert em lote dos pontos). `tentativa_previsao` usa JPA, por causa do lock.

**Tech Stack:** a do S1 (Java 17, Spring Boot 3.3.2, `RestClient`, JPA, `JdbcTemplate`, Flyway, H2 modo `MSSQLServer`, Caffeine, Redis/Lettuce, springdoc 2.2.0, JUnit 5, Testcontainers, `mockwebserver`).

**Spec:** `docs/superpowers/specs/2026-10-15-clima-nimbus-previsao-vigente-spec.md`. Formato da resposta da Nimbus: `Documentação.md` §3, na raiz do repositório principal, fora do git. Uso estes trechos dela:
- array com `{region_id, forecasts:[{datetime, run_datetime, <variáveis>...}]}`;
- datas em epoch ms;
- variável sem dado vem `null`.

## Decisões da fase de plano (confirmar na aprovação)

- **D1. Resultado da última tentativa.** `tentativa_previsao` ganha a coluna `ultima_falhou BIT NOT NULL DEFAULT 0`. Ela não está na tabela 6.4 da spec. O motivo: dentro da janela de 15 min (S2-FR-05), sem esse dado o serviço não sabe o que responder se a tentativa anterior falhou. Com a coluna, a resposta dentro da janela repete o resultado da tentativa:
  - última tentativa falhou: rodada gravada com `desatualizado=true`, ou `FONTE_INDISPONIVEL` sem rodada;
  - última tentativa OK, ou `forecasts: []`: rodada gravada com `desatualizado=false`, ou `rodada=null` com `pontos=[]` sem rodada (S2-AC-04/13).
- **D2.** `ultima_tentativa_em` é `NULL` até a primeira tentativa. A linha é criada antes da trava, num INSERT tolerante a chave duplicada, para que haja o que travar.
- **D3.** Rodada recebida **mais antiga** que a vigente (S2-FR-08) é descartada: não entra no histórico.
- **D4.** Na sobrescrita, o S2 apaga a chave L2 `clima:previsao:rodada:{regionId}:{runMs}` do S3. O L1 dessa chave passa a existir no S3, e o plano do S3 trata dele.
- **D5.** Se esgotar a espera pela trava (`DataAccessException | TransactionException`, como no fix d559d50 do S1), as regiões envolvidas são tratadas como falha da fonte. A tentativa não é registrada.

## Global Constraints

- Tudo do S1 (`docs/superpowers/plans/2026-10-15-clima-nimbus-regioes-plan.md`, Global Constraints) continua valendo. O resto desta lista é específico do S2.
- Migração **`V2__criar_previsao.sql`**. A V3 é do S4. SQL portável para SQL Server e H2 (`BIGINT`, `BIT`, `DATETIME2`, `NVARCHAR(MAX)`, `IDENTITY(1,1)`).
- Instantes via JDBC sempre como `LocalDateTime.ofInstant(i, ZoneOffset.UTC)`, lidos com `getObject(col, LocalDateTime.class)` e convertidos de volta com `UTC`. É o mesmo que o Hibernate faz com `jdbc.time_zone=UTC`. Nunca `java.sql.Timestamp`, que depende do fuso da JVM.
- Chaves de cache:
  - `clima:previsao:vigente:{regionId}`;
  - `clima:previsao:rodada:{regionId}:{runDatetimeEpochMs}` (S3, só invalidação aqui).
- Corpo enviado: `{"regions":[{"region_id":N}...],"datetime_start":<00:00Z de hoje em ms>,"datetime_end":<start + 15 dias>,"frequency":3600000,"integration":true}`.
- Re-consulta: 15 min (`Duration.ofMinutes(15)`). A rodada do dia vale até a próxima 00:00Z, exclusive (`agora < validoAte`).
- Tradução: `wind_direction→direcaoVento`, `temperature→temperatura`, `wind_gust→rajadaVento`, `wind_speed→velocidadeVento`, `relative_humidity→umidadeRelativa`, `precipitation→precipitacao`, `fog_fsi→nevoeiroFsi`, `fog_vis→nevoeiroVisibilidade`. Outras chaves saem com o nome original. O banco guarda os nomes originais.
- Mensagens:
  - "Região não encontrada", "Região inativa", "Fonte de dados climáticos indisponível";
  - 400 "Parâmetros inválidos: <detalhe>".
- Os testes rodam com `JAVA_HOME=/home/alvaro-zago/.jdks/temurin-17.0.20 sh ./mvnw -q test [-Dtest=...]`. O Docker precisa estar ativo.
- O `MockWebServer` estático `nimbus` é compartilhado entre as classes de teste. Os ITs contam deltas de `getRequestCount()` e limpam a fila no `@BeforeEach` (`setDispatcher(new QueueDispatcher())`).

## Review Focus

1. **Pedidos concorrentes com conjuntos sobrepostos em ordem inversa** (`1359,1706` × `1706,1359`): sem deadlock, uma chamada por região. Teste: `PrevisaoVigenteServiceIT.ordemInversaSemDeadlock` (Task 6).
2. **Virada exata do dia UTC.** Uma rodada do dia em cache às 23:59:59Z não pode ser servida às 00:00:00Z como "do dia". Testes: `RegraAtualizacaoTest.rodadaDeHojeValeAteMeiaNoiteExclusive` (Task 1) e `PrevisaoCacheIT.expiraNaViradaDoDia` (Task 4).
3. **Round-trip pelo Redis.** `35` continua inteiro, `18.14` continua decimal e `fog_fsi=null` continua presente como `null`. Teste: `PrevisaoCacheIT.preservaNulosENumerosNoL2` (Task 4).
4. **Corpo da Nimbus malformado:**
   - ponto sem `datetime` ou sem `run_datetime`, `forecasts` ausente ou corpo que não é array: `CORPO_INVALIDO` e nada gravado;
   - região que não foi pedida: ignorada.

   Testes: `NimbusClientPrevisaoTest.pontoSemRunDatetimeEhCorpoInvalido`, `.forecastsAusenteEhCorpoInvalido` e `.regiaoNaoPedidaEhIgnorada` (Task 3).
5. **Sobrescrita com menos pontos que a anterior.** Os pontos antigos que não vieram somem; nada fica misturado. Teste: `RodadaPrevisaoRepositorioIT.sobrescreveTrocaTodosOsPontos` (Task 2).

---

### Task 1: Regras de domínio da previsão

**Files:**
- Create em `src/main/java/br/com/neoviaengenharia/clima/domain/previsao/`: `PontoPrevisao.java`, `RodadaPrevisao.java`, `RegraAtualizacao.java`, `Decisao.java`, `ComparacaoRodada.java`, `TraducaoVariaveis.java`, `FiltroJanela.java`
- Test: `src/test/java/br/com/neoviaengenharia/clima/domain/previsao/RegraAtualizacaoTest.java`, `RodadaPrevisaoTest.java`, `TraducaoVariaveisTest.java`, `FiltroJanelaTest.java`

**Interfaces:**
- Produces:
  - `record PontoPrevisao(Instant dataHora, Instant rodadaPonto, Map<String, Object> variaveis)`. As chaves são as originais da Nimbus. O mapa aceita valores `null` (use `LinkedHashMap`, nunca `Map.of`).
  - `record RodadaPrevisao(long regionId, Instant rodada, Instant obtidoEm, List<PontoPrevisao> pontos)`. O construtor compacto ordena `pontos` por `dataHora` crescente e copia a lista.
  - `static Instant RodadaPrevisao.rodadaDe(List<PontoPrevisao> pontos)`: o maior `rodadaPonto`. Lista vazia → `IllegalArgumentException`.
  - `enum ComparacaoRodada { NOVA, MESMA, ANTIGA }` e `static ComparacaoRodada ComparacaoRodada.de(Optional<Instant> vigente, Instant recebida)`. Vazio → `NOVA`.
  - `record Decisao(boolean buscar, Instant validoAte, boolean desatualizado)`, com `static Decisao buscar()` e `static Decisao usarGravada(Instant validoAte, boolean desatualizado)`.
  - `RegraAtualizacao`:
    - `static final Duration INTERVALO_RECONSULTA = Duration.ofMinutes(15)`;
    - `static Decisao decidir(Optional<Instant> rodadaVigente, Optional<Instant> ultimaTentativa, boolean ultimaFalhou, Instant agora)`;
    - `static Instant inicioJanela(Instant agora)`;
    - `static Instant fimJanela(Instant agora)`.
  - `static Map<String, Object> TraducaoVariaveis.traduzir(Map<String, Object> originais)`: preserva a ordem e os `null`.
  - `static List<PontoPrevisao> FiltroJanela.aplicar(List<PontoPrevisao> pontos, Instant inicio, Instant fim)`. `inicio`/`fim` podem ser `null` (sem limite) e os limites são inclusivos.

Algoritmo de `decidir`. Os testes não determinam sozinhos a ordem:

```
hoje = data UTC de agora
se rodadaVigente presente e data UTC(rodadaVigente) >= hoje → usarGravada(próxima 00:00Z depois de agora, false)
se ultimaTentativa presente e agora < ultimaTentativa + 15min → usarGravada(ultimaTentativa + 15min, ultimaFalhou)
senão → buscar()
```

- [ ] **Step 1: Escrever os testes que falham**
  - `RegraAtualizacaoTest`:
    - `rodadaDeHojeNaoBusca` (S2-AC-01): rodada `2026-10-15T00:00Z`, agora `2026-10-15T18:00Z` → `buscar=false`, `validoAte=2026-10-16T00:00Z`, `desatualizado=false`.
    - `rodadaDeHojeValeAteMeiaNoiteExclusive`: mesma rodada; agora `2026-10-16T00:00:00Z` sem tentativa → `buscar=true`.
    - `semRodadaSemTentativaBusca`.
    - `brasilia2230UsaUtc` (S2-AC-03): rodada `2026-10-15T00:00Z`, tentativa `2026-10-16T01:00Z`, agora `2026-10-16T01:30Z` → `buscar=true`.
    - `tentativaHa5MinUsaGravada` (S2-AC-04): rodada de ontem, tentativa há 5 min, `ultimaFalhou=false` → `buscar=false`, `desatualizado=false`, `validoAte` = tentativa + 15 min.
    - `tentativaFalhaHa5MinMarcaDesatualizado` (D1): o mesmo, com `ultimaFalhou=true` → `desatualizado=true`.
    - `tentativaHaExatos15MinBusca`.
    - `janelaEnviada` (S2-AC-02): agora `2026-10-15T18:00Z` → `inicioJanela` = `2026-10-15T00:00Z` (`1792022400000` ms) e `fimJanela` = `2026-10-30T00:00Z` (`1793318400000` ms).
  - `RodadaPrevisaoTest`:
    - `rodadaEhOMaiorRunDatetime` (S2-AC-07): pontos com runs de 14 e 15/10 → `rodadaDe` = 15/10. Cada ponto mantém o seu `rodadaPonto`.
    - `pontosSaemOrdenados` (S2-AC-11): pontos fora de ordem e não contínuos (`…T03`, `…T00`, `…T06`) → `T00`, `T03`, `T06`.
    - `comparacao`: `vazio→NOVA`, `igual→MESMA`, `maior→NOVA`, `menor→ANTIGA`.
  - `TraducaoVariaveisTest.traduzNulosEDesconhecidas` (S2-AC-10): entrada `{temperature:35, fog_fsi:null, pressure:1012}` → `{temperatura:35, nevoeiroFsi:null, pressure:1012}`, com `containsKey("nevoeiroFsi")` verdadeiro e sem a chave `rajadaVento`. Mais um caso com as 8 chaves da tabela.
  - `FiltroJanelaTest`:
    - `filtraInclusivo` (S2-AC-12): pontos horários de `2026-10-15T00Z` a `2026-10-17T00Z`, janela `16T00`–`16T05` → 6 pontos.
    - `soInicio`, `soFim`, `semLimites`.
    - `janelaForaDosPontos` → lista vazia.
- [ ] **Step 2:** rodar `-Dtest='RegraAtualizacaoTest,RodadaPrevisaoTest,TraducaoVariaveisTest,FiltroJanelaTest'`. Esperado: FAIL na compilação.
- [ ] **Step 3:** implementar as classes acima, todas sem dependência de Spring.
- [ ] **Step 4:** rodar o mesmo. Esperado: PASS.
- [ ] **Step 5:** commit `feat: regras de domínio da previsão vigente`.

### Task 2: Persistência de rodadas, pontos e tentativas (V2)

**Files:**
- Create: `src/main/resources/db/migration/V2__criar_previsao.sql`
- Create em `src/main/java/br/com/neoviaengenharia/clima/infra/repo/`: `RodadaPrevisaoRepositorio.java` (`@Repository`, `JdbcTemplate` + `ObjectMapper`), `TentativaPrevisao.java` (entidade), `TentativaPrevisaoRepository.java`
- Test: `src/test/java/br/com/neoviaengenharia/clima/infra/repo/RodadaPrevisaoRepositorioIT.java`, `TentativaPrevisaoRepositoryIT.java`

**Interfaces:**
- Consumes: `PontoPrevisao`, `RodadaPrevisao` (Task 1).
- Produces:
  - Tabelas da spec §6.1, §6.2 e §6.4, com as FKs, os nomes `uq_rodada_previsao_regiao_run` / `uq_ponto_previsao_rodada_data` e mais `tentativa_previsao.ultima_falhou BIT NOT NULL DEFAULT 0` (D1), com `ultima_tentativa_em DATETIME2 NULL` (D2).
  - `RodadaPrevisaoRepositorio`:
    - `Optional<RodadaPrevisao> vigente(long regionId)`: a de maior `run_datetime`, com pontos.
    - `Optional<RodadaPrevisao> buscar(long regionId, Instant rodada)` (o S3 usa).
    - `List<RodadaResumo> listar(long regionId, Instant de, Instant ate)` fica para o S3. Não criar aqui.
    - `void gravar(RodadaPrevisao rodada)`: insere a rodada nova ou, se (`region_id`, `run_datetime`) já existe, atualiza `obtido_em`, apaga os pontos e insere os novos. Pontos com `batchUpdate`; `variaveis` serializadas com `ObjectMapper` (mapa original, `null` incluído).
  - `TentativaPrevisao`: `regionId` (`@Id`), `Instant ultimaTentativaEm`, `boolean ultimaFalhou`, `void registrar(Instant quando, boolean falhou)`.
  - `TentativaPrevisaoRepository extends JpaRepository<TentativaPrevisao, Long>`:
    - `@Lock(PESSIMISTIC_WRITE)` + hint `jakarta.persistence.lock.timeout=15000`: `List<TentativaPrevisao> travar(Collection<Long> ids)`, com `order by t.regionId`;
    - `default void garantir(long regionId)`: cria a linha se não existir, engolindo `DataIntegrityViolationException`. Use `@Modifying` nativo `INSERT ... SELECT ?, NULL, 0 WHERE NOT EXISTS (...)` com `@Transactional(propagation = REQUIRES_NEW)` num método do próprio repositório ou de um componente auxiliar.

- [ ] **Step 1: Escrever os testes que falham** (extends `IntegracaoBase`; limpar `ponto_previsao`, `rodada_previsao` e `tentativa_previsao` no `@BeforeEach`; semear as regiões 1359 e 1706 em `regiao`, por causa da FK)
  - `RodadaPrevisaoRepositorioIT`:
    - `gravaELeVigente`: grava a rodada 15/10 com 3 pontos, incluindo um com `fog_fsi=null`. `vigente(1359)` devolve os pontos em ordem, `obtidoEm` igual e o mapa com a chave `fog_fsi` → `null`.
    - `vigenteEhAMaisRecente` (S2-AC-05): grava 14/10 e 15/10. `vigente` é 15/10 e há 2 linhas em `rodada_previsao`.
    - `sobrescreveMesmaRodada` (S2-AC-06): grava 15/10 com `temperature=35` às 15:00Z e depois a mesma rodada com `36`. Fica uma linha, com `temperature=36` e `obtidoEm` atualizado.
    - `sobrescreveTrocaTodosOsPontos`: a rodada tinha 5 pontos e a nova versão tem 2. Restam exatamente os 2 novos.
    - `instantesEmUtc`: com `TimeZone.setDefault(America/Sao_Paulo)` restaurado no `finally`, gravar e ler devolve os mesmos `Instant`. Além disso, `SELECT COUNT(*) FROM ponto_previsao p JOIN rodada_previsao r ON p.rodada_id = r.id WHERE p.run_datetime = r.run_datetime` conta todos os pontos de uma rodada de run único.
    - `buscarRodadaExata`: `buscar(1359, 14/10)` presente; `buscar(1359, 14/10T01:00Z)` vazio.
  - `TentativaPrevisaoRepositoryIT`:
    - `garantirEhIdempotente`: duas chamadas a `garantir(1359)` resultam numa linha com `ultimaTentativaEm=null`.
    - `travarDevolveEmOrdem`: dentro de `TransactionTemplate`, `travar([1706, 1359])` devolve [1359, 1706]; `registrar(agora, true)` persiste.
- [ ] **Step 2:** rodar `-Dtest='RodadaPrevisaoRepositorioIT,TentativaPrevisaoRepositoryIT'`. Esperado: FAIL.
- [ ] **Step 3:** escrever a V2 e implementar. Ler os pontos com um único `SELECT ... ORDER BY data_hora` por rodada.
- [ ] **Step 4:** rodar o mesmo e depois a suíte toda (o `ddl-auto=validate` e o Flyway rodam em todo IT). Esperado: PASS.
- [ ] **Step 5:** commit `feat: persistência de rodadas e tentativas de previsão`.

### Task 3: Cliente da Nimbus para `retrieve-forecasts`

**Files:**
- Modify: `src/main/java/br/com/neoviaengenharia/clima/infra/nimbus/NimbusClient.java`
- Test: `src/test/java/br/com/neoviaengenharia/clima/infra/nimbus/NimbusClientPrevisaoTest.java` (mesmo arranjo de `NimbusClientTest`)

**Interfaces:**
- Consumes: `PontoPrevisao` (Task 1).
- Produces: `Map<Long, List<PontoPrevisao>> NimbusClient.buscarPrevisoes(List<Long> regionIds, Instant inicio, Instant fim)`.
  - O mapa contém só as regiões pedidas que vieram na resposta. Uma região ausente do mapa foi omitida pela Nimbus, e o serviço trata isso como falha da região.
  - `forecasts: []` vira lista vazia.
  - Falhas como em `listarRegioes`, com o mesmo `NimbusIndisponivelException`.

- [ ] **Step 1: Escrever os testes que falham**
  - `enviaCorpoEHeaders` (S2-AC-02): `buscarPrevisoes([1706, 1710], 2026-10-15T00Z, 2026-10-30T00Z)`. O pedido é `POST /retrieve-forecasts`, com os headers `api_key`/`authorization` exatos e o corpo JSON igual a `{"regions":[{"region_id":1706},{"region_id":1710}],"datetime_start":1792022400000,"datetime_end":1793318400000,"frequency":3600000,"integration":true}`. Comparar com `JsonNode`, não como string.
  - `mapeiaPontosDaDoc`: o exemplo da `Documentação.md` §3 vira 2 pontos da 1359, com `dataHora`/`rodadaPonto` convertidos de ms e `variaveis` contendo as 8 chaves originais, sem `datetime` nem `run_datetime`.
  - `variavelNulaPreservada`: `"fog_fsi": null` vira `containsEntry("fog_fsi", null)`.
  - `variasRegioesEForecastsVazio`: a resposta traz 1359 com pontos e 1706 com `[]`. O mapa tem as 2 chaves e a 1706 tem lista vazia.
  - `regiaoOmitida`: pedido [1359, 1706], resposta só com a 1359 → o mapa não contém a 1706.
  - `regiaoNaoPedidaEhIgnorada`: a resposta traz a 9999 → ausente do mapa.
  - `pontoSemRunDatetimeEhCorpoInvalido` e `forecastsAusenteEhCorpoInvalido`: `CORPO_INVALIDO`.
  - `corpoNaoArrayEhCorpoInvalido`: `{}` → `CORPO_INVALIDO`.
  - `status401`/`403` → `CREDENCIAL_RECUSADA`; `503` → `ERRO_HTTP`; timeout → `TIMEOUT`.
  - `logRegioesSemCredencial` (`OutputCaptureExtension`): depois de um 200, a saída contém `retrieve-forecasts`, `regioes=[1359]` e o status, e não contém as chaves.
- [ ] **Step 2:** rodar `-Dtest=NimbusClientPrevisaoTest`. Esperado: FAIL.
- [ ] **Step 3: Implementar**
  - Extrair de `listarRegioes` o tratamento de erro e de log para um método privado comum, parametrizado por método HTTP, caminho e regiões. O log fica `nimbus {metodo} {caminho} regioes={ids} status={} duracaoMs={}`.
  - Ler a resposta como `JsonNode`. Cada campo de ponto, exceto `datetime`/`run_datetime`, vai ao mapa via `mapper.convertValue(valor, Object.class)`, ou `null` para `NullNode`. O `ObjectMapper` é injetado: `NimbusConfig` passa o bean do Spring e `NimbusClientTest` passa `new ObjectMapper()`.
- [ ] **Step 4:** rodar `-Dtest='NimbusClientPrevisaoTest,NimbusClientTest'`. Esperado: PASS.
- [ ] **Step 5:** commit `feat: cliente da Nimbus para previsões (retrieve-forecasts)`.

### Task 4: Cache multinível da rodada vigente

**Files:**
- Create: `src/main/java/br/com/neoviaengenharia/clima/infra/cache/PrevisaoCache.java`, `src/main/java/br/com/neoviaengenharia/clima/domain/previsao/PrevisaoVigente.java`
- Modify: `src/main/java/br/com/neoviaengenharia/clima/infra/cache/CacheConfig.java` (bean `previsaoVigenteL1`)
- Test: `src/test/java/br/com/neoviaengenharia/clima/infra/cache/PrevisaoCacheIT.java`, `PrevisaoCacheRedisForaIT.java`

**Interfaces:**
- Consumes: `RodadaPrevisao`.
- Produces:
  - `record PrevisaoVigente(RodadaPrevisao rodada, Instant validoAte, boolean desatualizado)` com `boolean valida(Instant agora)` (`agora < validoAte`). A `rodada` nunca é `null`: sem rodada não há cache.
  - `PrevisaoCache.obter(long regionId): Optional<PrevisaoVigente>`: L1, depois L2 com promoção. Só devolve entradas válidas.
  - `PrevisaoCache.guardar(PrevisaoVigente)`: L1 + L2, com TTL `validoAte - agora`; não grava se o TTL for ≤ 0.
  - `PrevisaoCache.invalidarRodada(long regionId, Instant rodada)`: `DEL clima:previsao:rodada:{regionId}:{epochMs}` (D4).
  - Falhas do Redis: WARN `Falha no cache L2 (Redis): {}`, como no `CatalogoCache`.
  - `void limparL1()` package-private, para os testes.
- L1: `Cache<Long, PrevisaoVigente>` com `maximumSize(5000)` e `Expiry` até `validoAte`, no mesmo molde do `catalogoL1`.

- [ ] **Step 1: Escrever os testes que falham**
  - `guardaEDevolveDoL1`.
  - `promoveDoL2`.
  - `preservaNulosENumerosNoL2`: guarda um ponto com `{temperature:35, wind_gust:18.14, fog_fsi:null}`, limpa o L1 e lê do L2. O valor de `temperature` é `Integer` 35, o de `wind_gust` é `Double` 18.14 e `fog_fsi` está presente como `null`.
  - `expiraNaViradaDoDia`: relógio `2026-10-15T23:59:59Z`, entrada válida até `2026-10-16T00:00Z`; avança 1 s → `obter` vazio.
  - `chaveRedis`: depois de `guardar`, `redis.hasKey("clima:previsao:vigente:1359")` é verdadeiro e o TTL é > 0 e ≤ o restante até `validoAte`.
  - `invalidaChaveDaRodadaEspecifica`: grava `clima:previsao:rodada:1359:1792022400000` direto no Redis; `invalidarRodada(1359, 2026-10-15T00Z)` apaga a chave.
  - `PrevisaoCacheRedisForaIT.redisForaNaoLanca` (extends `IntegracaoRedisForaBase`): `guardar`, `obter` e `invalidarRodada` não lançam, e a saída tem "Falha no cache L2".
- [ ] **Step 2:** rodar `-Dtest='PrevisaoCacheIT,PrevisaoCacheRedisForaIT'`. Esperado: FAIL.
- [ ] **Step 3:** implementar.
- [ ] **Step 4:** rodar o mesmo. Esperado: PASS.
- [ ] **Step 5:** commit `feat: cache Caffeine → Redis da previsão vigente`.

### Task 5: Serviço da previsão vigente (fluxo com a Nimbus respondendo)

**Files:**
- Create em `src/main/java/br/com/neoviaengenharia/clima/app/previsao/`: `PrevisaoVigenteService.java`, `ItemPrevisao.java`, `ConsultaPrevisao.java`, `CodigoErroRegiao.java`
- Test: `src/test/java/br/com/neoviaengenharia/clima/app/previsao/PrevisaoVigenteServiceIT.java`, `PrevisaoFixture.java`

**Interfaces:**
- Consumes:
  - `ValidacaoRegiao.verificar(long)` (S1), `MotivoRecusa`;
  - Tasks 1–4.
- Produces:
  - `enum CodigoErroRegiao { NAO_ENCONTRADA, INATIVA, FONTE_INDISPONIVEL }`, com `String mensagem()` ("Região não encontrada", "Região inativa", "Fonte de dados climáticos indisponível").
  - `record ItemPrevisao(long regionId, RodadaPrevisao rodada, boolean desatualizado, CodigoErroRegiao erro)`. `rodada` é `null` quando há erro ou sem dado. Os pontos já vêm filtrados pela janela.
  - `record ConsultaPrevisao(List<ItemPrevisao> itens)` com `boolean todasIndisponiveis()`: não vazia e todas com `FONTE_INDISPONIVEL`.
  - `ConsultaPrevisao PrevisaoVigenteService.consultar(List<Long> regionIds, Instant inicio, Instant fim)`. Os ids chegam distintos, entre 1 e 20; a validação é do controller. `inicio`/`fim` podem ser `null`. A `FonteIndisponivelException` do catálogo (S1) propaga.

Algoritmo:

```
para cada id, na ordem pedida:
  verificar(id) presente → item de erro (NAO_ENCONTRADA/INATIVA); fim
  cache.obter(id) presente → item(rodada, desatualizado); fim
  ler do SQL a vigente + tentativa; d = decidir(...)
  !d.buscar → resolve com (gravada, d) e guarda no cache; fim
  senão → pendentes
pendentes não vazio → tx.execute(buscarComTrava(pendentes))   // D5: lock esgotado → falha sem tentativa
  garantir(id) para cada pendente (fora da tx principal, antes)
  travar(pendentes) em ordem; para cada: reler a vigente + tentativa, re-decidir; !buscar → resolve
  ainda pendentes: registrar(agora, false) provisório; inicio/fimJanela(agora); UMA chamada buscarPrevisoes
    NimbusIndisponivelException → todos registrar(agora, true)
    por região: ausente do mapa → registrar(agora, true)
                lista vazia → registrar(agora, false)
                senão r = rodadaDe(pontos); c = de(vigente, r)
                  ANTIGA → registrar(agora, false), mantém a vigente
                  NOVA/MESMA → gravar(RodadaPrevisao(id, r, agora, pontos)); se MESMA, cache.invalidarRodada(id, r)
    log INFO: "previsão: rodadas recebidas {id=run|vazio|omitida}"
  depois de cada registro: re-decidir com o estado novo → resolve
depois do commit: cache.guardar das entradas resolvidas com rodada
resolve(gravada, d): se gravada vazia e d.desatualizado → FONTE_INDISPONIVEL
                     se gravada vazia → item(null, false, null)       // sem dado (S2-FR-09)
                     senão → item(gravada, d.desatualizado)
no fim, aplicar FiltroJanela em cada item com rodada
```

Para que a re-decisão depois de uma busca feita "agora" não peça outra busca, use `decidir` com o novo `ultimaTentativa = agora`. Ele devolve `usarGravada(agora + 15min, falhou)`, ou `usarGravada(próxima meia-noite, false)` se a rodada nova for de hoje.

- [ ] **Step 1: Escrever os testes que falham.** `PrevisaoFixture` limpa as tabelas, o L1/L2 da previsão e a fila da Nimbus. Também tem:
  - `semearCatalogo(ativa(1359), ativa(1706), ativa(1710), inativa(1720))`, reusando o `CatalogoFixture.gravar` com `obtidoEm` = agora;
  - `gravarRodada(id, run, obtidoEm, pontos...)`, `registrarTentativa(id, quando, falhou)`;
  - `respostaNimbus(Map<Long, List<ponto>>)`.

  Testes, com o relógio em `2026-10-15T18:00Z` salvo indicação contrária e conferindo em cada um o delta de requisições:
  - `rodadaDoDiaNaoChama` (S2-AC-01): 0 requisições, `desatualizado=false`.
  - `primeiraConsultaBuscaEGrava` (S2-AC-02): 1 requisição, cujo corpo tem `datetime_start=1792022400000` e `datetime_end=1793318400000`; a rodada fica gravada e o item traz os pontos. Uma segunda consulta → 0 requisições novas, servida pelo cache.
  - `utcNaoLocal` (S2-AC-03): rodada 15/10, tentativa `2026-10-16T01:00Z`, relógio `2026-10-16T01:30Z` → 1 requisição, com `datetime_start` = `2026-10-16T00:00Z`.
  - `reconsultaRespeita15Min` (S2-AC-04): rodada de ontem, tentativa há 5 min → 0 requisições, `desatualizado=false`, rodada de ontem.
  - `rodadaNovaPreservaHistorico` (S2-AC-05).
  - `mesmaRodadaSobrescreveEInvalidaCacheDoS3` (S2-AC-06 + D4): a rodada 15/10 já gravada, ontem. Tentativa antiga. Relógio `2026-10-16T01:30Z`, a Nimbus devolve 15/10 com `temperature=36` → 1 linha em `rodada_previsao`, item com `temperature=36` e a chave `clima:previsao:rodada:1359:1792022400000` (semeada antes) apagada.
  - `rodadaMaisAntigaMantemVigente` (S2-FR-08): vigente 15/10, relógio `2026-10-16T01:30Z`, a Nimbus devolve 14/10 → a vigente segue 15/10 e a 14/10 não é gravada (D3).
  - `variasRegioesNumaChamada` (S2-AC-08): 1359 com a rodada do dia; 1706 e 1710 sem rodada → 1 requisição com `regions` = [1706, 1710].
  - `erroPorRegiaoNaOrdem` (S2-AC-09): `[1359, 1720, 99999]` → 1359 com pontos, 1720 `INATIVA`, 99999 `NAO_ENCONTRADA`, nessa ordem; o corpo enviado só tem a 1359.
  - `regiaoSemDado` (S2-AC-13): `forecasts: []` → `rodada=null`, pontos vazios, `erro=null`; a tentativa fica registrada com `ultima_falhou=false`. Uma nova consulta 5 min depois → 0 requisições, mesmo resultado.
  - `filtroDeJanelaSemChamada` (S2-AC-12): rodada do dia com pontos de hora em hora, de 15/10T00Z a 17/10T00Z. Janela `16T00`–`16T05` → 6 pontos, 0 requisições.
- [ ] **Step 2:** rodar `-Dtest=PrevisaoVigenteServiceIT`. Esperado: FAIL.
- [ ] **Step 3:** implementar seguindo o algoritmo. Os casos de falha do Task 6 podem ficar com o caminho mínimo; os testes deles vêm a seguir.
- [ ] **Step 4:** rodar o mesmo. Esperado: PASS.
- [ ] **Step 5:** commit `feat: serviço da previsão vigente`.

### Task 6: Falhas da Nimbus, concorrência e Redis fora

**Files:**
- Modify: `src/main/java/br/com/neoviaengenharia/clima/app/previsao/PrevisaoVigenteService.java`
- Test: `PrevisaoVigenteServiceIT.java` (novos casos) e `src/test/java/br/com/neoviaengenharia/clima/app/previsao/PrevisaoVigenteServiceRedisForaIT.java`

**Interfaces:** sem mudanças, só o comportamento.

- [ ] **Step 1: Escrever os testes que falham**
  - `nimbusForaComRodada` (S2-AC-14): rodada de ontem, tentativa há 20 min, 503 → `desatualizado=true`, `obtidoEm` da gravada; tentativa registrada com `ultima_falhou=true`. 5 min depois → 0 requisições e ainda `desatualizado=true` (D1).
  - `nimbusForaUmaDeDuas` (S2-AC-15): 1359 com rodada, 1706 sem; timeout (`SocketPolicy.NO_RESPONSE`, ou um atraso maior que o `read-timeout` do perfil de teste; se preciso, reduzir `nimbus.read-timeout` no `application-test.properties` para 2 s) → 1359 `desatualizado=true`, 1706 `FONTE_INDISPONIVEL`; `todasIndisponiveis()` falso.
  - `nimbusForaTodas` (S2-AC-16): nenhuma com rodada, 503 → `todasIndisponiveis()` verdadeiro.
  - `regiaoOmitidaEhFalhaSoDela`: pede 1359 e 1706; a resposta só traz a 1359 → 1359 OK, 1706 `FONTE_INDISPONIVEL`.
  - `credencialRecusadaLogaErro` (S2 §8): 401 → item desatualizado ou `FONTE_INDISPONIVEL`, log ERROR "recusou as credenciais", sem as chaves.
  - `concorrenciaUmaChamada` (S2-AC-18): 8 threads pedem a 1359 sem rodada; a primeira resposta tem 300 ms de atraso e há mais 7 respostas enfileiradas → exatamente 1 requisição; todas as threads recebem os pontos.
  - `ordemInversaSemDeadlock`: 4 threads com `[1359, 1706]` e 4 com `[1706, 1359]`, ambas sem rodada → termina em menos de 30 s, com 1 requisição, cujo corpo tem as duas regiões.
  - `PrevisaoVigenteServiceRedisForaIT.redisForaServeDoSql` (S2-AC-19): rodada do dia só no SQL → item normal, 0 requisições, log "Falha no cache L2".
- [ ] **Step 2:** rodar `-Dtest='PrevisaoVigenteServiceIT,PrevisaoVigenteServiceRedisForaIT'`. Esperado: FAIL nos novos.
- [ ] **Step 3:** completar o serviço: os ramos de falha do algoritmo do Task 5 e o `try/catch (DataAccessException | TransactionException)` em volta do `tx.execute` (D5), que resolve os pendentes com `decidir(..., ultimaFalhou=true)` sem gravar.
- [ ] **Step 4:** rodar o mesmo. Esperado: PASS.
- [ ] **Step 5:** commit `feat: previsão vigente com fallback, concorrência e Redis fora`.

### Task 7: Endpoint REST e OpenAPI

**Pré-condição:** o S1 terminou os Tasks 6–7 em `feat/clima-regioes`. Rodar `git merge feat/clima-regioes` (sem rebase) e a suíte toda antes de começar. Se o S1 ainda não terminou, parar e avisar.

**Files:**
- Create: `src/main/java/br/com/neoviaengenharia/clima/ui/rest/PrevisoesController.java`
- Create em `src/main/java/br/com/neoviaengenharia/clima/ui/dto/`: `PrevisoesResponse.java`, `PrevisaoRegiaoResponse.java`, `PontoPrevisaoResponse.java`, `ErroRegiaoResponse.java`
- Create: `src/main/java/br/com/neoviaengenharia/clima/ui/exceptions/ParametrosInvalidosException.java`, salvo se o S1 já tiver um equivalente para 400 (nesse caso, reusar)
- Modify: `src/main/java/br/com/neoviaengenharia/clima/ui/exceptions/ExceptionManager.java` (do S1), só para acrescentar handlers de 400 se faltarem. Antes, conferir nas notas compartilhadas se o S4 também mexe nele.
- Test: `src/test/java/br/com/neoviaengenharia/clima/ui/rest/PrevisoesControllerTest.java` (`@WebMvcTest`, serviço mockado) e `PrevisoesApiIT.java`

**Interfaces:**
- Consumes: `PrevisaoVigenteService.consultar`, `ConsultaPrevisao`, `ItemPrevisao`, `TraducaoVariaveis`; do S1: `Response`, `ExceptionManager` e o mapeamento `FonteIndisponivelException` → 503.
- Produces:
  - `record PrevisoesResponse(List<PrevisaoRegiaoResponse> regioes)`;
  - `record PrevisaoRegiaoResponse(long regionId, Instant rodada, Instant obtidoEm, boolean desatualizado, ErroRegiaoResponse erro, List<PontoPrevisaoResponse> pontos)`, com `static PrevisaoRegiaoResponse de(ItemPrevisao)`, que traduz as variáveis. O S3 reusa;
  - `record PontoPrevisaoResponse(Instant dataHora, Instant rodadaPonto, Map<String, Object> variaveis)`;
  - `record ErroRegiaoResponse(String codigo, String mensagem)`;
  - `ParametrosInvalidosException(String detalhe)` → 400 `{"status":"BAD_REQUEST","message":"Parâmetros inválidos: <detalhe>"}`. O S3 reusa;
  - `static Instant PrevisoesController.instante(String valor, String nome)` (ou um utilitário em `ui/rest`) para ISO-8601 **com fuso**, via `OffsetDateTime.parse`. Sem fuso → `ParametrosInvalidosException`. O S3 reusa.

- [ ] **Step 1: Escrever os testes que falham**
  - `PrevisoesControllerTest`:
    - `formatoDaSpec`: com o serviço devolvendo um item OK e um `INATIVA`, o JSON é igual ao da spec §5.1, com `"rodada":"2026-10-15T00:00:00Z"`, `"erro":null`, `"pontos":[]` no item com erro e `"nevoeiroFsi":null` presente.
    - `regioesInvalidas400` (S2-AC-17), parametrizado: ausente, `""`, `"1,1"`, 21 ids, `"1,abc"`, `"1,,2"` → 400 com mensagem começando por "Parâmetros inválidos: ".
    - `datasInvalidas400`: `inicio=2026-10-15T00:00:00` (sem fuso), `fim < inicio`, `inicio=lixo` → 400.
    - `datasComOffsetAceitas`: `inicio=2026-10-15T00:00:00-03:00` → o serviço recebe `2026-10-15T03:00:00Z`.
    - `todasIndisponiveis503` (S2-FR-15): `{"status":"SERVICE_UNAVAILABLE","message":"Fonte de dados climáticos indisponível"}`.
    - `todasRecusadas200`: todas `INATIVA`/`NAO_ENCONTRADA` → 200.
    - `ordemPreservada`: `regioes=1706,1359` → o serviço recebe `[1706, 1359]`.
  - `PrevisoesApiIT`:
    - `GET /api/clima/previsoes?regioes=1359` ponta a ponta, com o catálogo semeado e o MockWebServer → 200 com pontos traduzidos;
    - `/api-docs` contém `/api/clima/previsoes` (S2-FR-18).
- [ ] **Step 2:** rodar `-Dtest='PrevisoesControllerTest,PrevisoesApiIT'`. Esperado: FAIL.
- [ ] **Step 3:** implementar o controller com `@Operation`/`@ApiResponse` (200, 400, 503) no estilo do `RegioesController` do S1. `regioes` chega como `String` e é dividido e validado à mão, com até 20 itens, distintos e numéricos. Os handlers de `MissingServletRequestParameterException` → 400 entram no `ExceptionManager` se o S1 não os tiver.
- [ ] **Step 4:** rodar `./mvnw -q test` (suíte toda). Esperado: PASS.
- [ ] **Step 5:** commit `feat: endpoint REST da previsão vigente`.
