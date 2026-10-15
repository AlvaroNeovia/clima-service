# PRD: clima-service — Integração Neovia x Nimbus (API Cronos)

- **Data:** 2026-10-15
- **Autor:** alvaro.zago@neoviaengenharia.com.br
- **Status:** Aprovado em 2026-10-15
- **Fonte:** `Documentação.md` (Integração Neovia x Nimbus | API Cronos) + entrevista com o solicitante

## 1. Visão geral e valor

O `clima-service` é um novo microsserviço backend da Neovia. Ele concentra o acesso à API Cronos da Nimbus Meteorologia e oferece aos sistemas internos três tipos de dado: o catálogo de regiões, a previsão do tempo (até 15 dias) e a precipitação observada por hora.

Valor:

- **Um único ponto de integração.** As credenciais da Nimbus, as regras de cache e o formato da API ficam só no `clima-service`. O `pgo-api-service` e o `neoviapainel-backend` consomem um contrato próprio da Neovia e não dependem da Nimbus.
- **Respeito às regras de uso da Nimbus.** A previsão muda uma vez por dia e o observado uma vez por hora. O cache multinível garante que a Nimbus só seja chamada quando há dado novo.
- **Histórico próprio.** Cada rodada de previsão e cada hora observada ficam gravadas no SQL Server. Rodadas passadas podem ser consultadas mesmo que a Nimbus não guarde esse histórico.
- **Resiliência.** Se a Nimbus estiver fora do ar, os consumidores recebem o último dado guardado, com aviso de que pode estar desatualizado.

### Consumidores

| Consumidor | Uso |
| --- | --- |
| `pgo-api-service` | Chamada serviço a serviço pela rede interna |
| `neoviapainel-backend` | Chamada serviço a serviço pela rede interna; o frontend (`portal-web`) chega aos dados por ele |

### Classificação do material de origem

- **Intenção de negócio:** não constava da documentação; foi obtida na entrevista (seção 9).
- **Contratos (fatos a codificar):** os três endpoints da Nimbus, os headers `api_key`/`authorization` (sem `Bearer`), datas em epoch ms, o formato `integration=true`, a rodada às 00:00 UTC, a resolução horária até 120 h e de 3 em 3 h depois, `null` para variável configurada sem dado, e `forecasts: []` para região sem dado.
- **Ruído (decisões já tomadas pela fonte, não são requisitos):** os exemplos em Python/`requests` e cURL/`jq`, e `timeout=30` nos exemplos.

## 2. Contrato da Nimbus (referência)

| Operação Nimbus | Uso no clima-service | Frequência máxima de chamada |
| --- | --- | --- |
| `GET /forecast-regions?integration=true` | Catálogo de regiões | 1x/dia (ou sob alteração conhecida) |
| `POST /retrieve-forecasts` com `integration: true` | Previsão da rodada inteira (15 dias) por região | Rodada do dia já obtida: nenhuma até o próximo dia UTC. Rodada do dia ausente: 1x a cada 15 min por região |
| `GET /manage/representative-stations/{region_id}/data-consistency?aggregation=hourly&variable=precipitation` | Precipitação observada horária | 1x/hora por região e janela |

Headers em toda chamada: `api_key` e `authorization` (valor direto, sem `Bearer`). Base URL: `https://cronos-api.nimbusmeteorologia.com.br`.

## 3. Requisitos funcionais (EARS)

### Regiões

**FR-001: Listar regiões.** Quando um consumidor pedir a lista de regiões, o clima-service deve retornar todas as regiões disponíveis para as credenciais Neovia, com identificador, nome, código curto (alias), indicador de inativa e identificador do estado.

**FR-002: Cache do catálogo de regiões.** O clima-service deve chamar `GET /forecast-regions` da Nimbus no máximo uma vez por dia.

**FR-003: Atualização forçada do catálogo.** Quando um consumidor pedir explicitamente a atualização do catálogo, o clima-service deve consultar a Nimbus de novo, respeitando o limite de FR-002. O endpoint é interno.

**FR-004: Identificador de região.** O clima-service deve aceitar apenas o `region_id` vindo do catálogo da Nimbus como identificador de região. Ele nunca deve deduzir o identificador a partir do nome ou da posição na lista.

**FR-005: Região inativa.** Se a região pedida estiver com `inactive=true` no catálogo, o clima-service deve recusar consultas de previsão e de precipitação observada para ela. A região continua aparecendo na listagem com o indicador de inativa.

**FR-006: Região desconhecida.** Se o `region_id` pedido não existir no catálogo, o clima-service deve recusar a consulta sem chamar a Nimbus.

**FR-007: Região removida pela Nimbus.** Quando uma região gravada deixar de vir no catálogo da Nimbus, o clima-service deve mantê-la no banco, marcada como removida, preservando o histórico. Ela sai da listagem, e consultas a ela passam a ser tratadas como região inexistente.

### Previsão

**FR-010: Previsão vigente por região.** Quando um consumidor pedir a previsão de uma ou mais regiões, o clima-service deve retornar, para cada região, a previsão da rodada mais recente disponível. Cada ponto deve trazer o horário previsto, a rodada (`run_datetime`) e as variáveis configuradas pela Nimbus para aquela região.

**FR-011: Rodada inteira.** Quando o clima-service buscar previsão na Nimbus, ele deve pedir a rodada inteira (15 dias a partir do início do dia UTC, com `frequency=3600000`) e guardar todos os pontos efetivamente retornados.

**FR-012: Filtro de janela.** Quando o consumidor informar início e fim opcionais, o clima-service deve devolver só os pontos dentro dessa janela, tirados da rodada guardada, sem nova chamada à Nimbus.

**FR-013: Pontos não contínuos.** O clima-service deve devolver só os horários efetivamente retornados pela Nimbus (horários até 120 h da rodada e de 3 em 3 h depois). Ele não deve inventar, interpolar nem preencher pontos.

**FR-014: Variáveis dinâmicas e nulos.** O clima-service deve repassar só as variáveis que a Nimbus enviou para cada ponto e manter como `null` as que vierem `null`. Os valores são repassados sem unidade declarada.

**FR-015: Região sem dado.** Se a Nimbus devolver `forecasts: []` para uma região, o clima-service deve incluir essa região na resposta com a lista de pontos vazia, nunca omiti-la.

**FR-016: Rodada do dia já obtida.** Se a rodada guardada para a região tiver a data de `run_datetime`, em UTC, igual à data atual em UTC, o clima-service deve responder a partir do cache, sem chamar a Nimbus, até a virada do dia UTC.

**FR-017: Rodada do dia ainda ausente.** Se a rodada guardada para a região for de um dia UTC anterior, o clima-service deve consultar a Nimbus de novo no máximo uma vez a cada 15 minutos por região, até a rodada do dia UTC atual chegar. Entre essas tentativas, ele responde com a rodada guardada.

**FR-018: Comparação em UTC.** O clima-service deve comparar datas de rodada exclusivamente em UTC, nunca no horário local.

**FR-019: Previsão por rodada específica.** Quando um consumidor pedir a previsão de uma região para uma rodada específica (`run_datetime`), o clima-service deve devolver essa rodada a partir do histórico persistido, sem chamar a Nimbus. Essa consulta e a listagem das rodadas gravadas valem também para regiões inativas ou removidas, desde que tenham histórico (exceção a FR-005 e FR-007, decisão do S3).

**FR-020: Histórico de rodadas.** O clima-service deve gravar no SQL Server cada rodada de previsão obtida da Nimbus, por região, sem sobrescrever as anteriores, e mantê-las por tempo indeterminado.

**FR-021: Várias regiões numa chamada.** Quando o consumidor pedir várias regiões e algumas precisarem de dado novo, o clima-service deve buscar na Nimbus só essas regiões, juntas numa única chamada `retrieve-forecasts` com vários itens em `regions`.

### Precipitação observada

**FR-030: Série horária observada.** Quando um consumidor pedir a precipitação observada de uma região numa janela de início e fim, o clima-service deve devolver a série horária. Cada hora traz: horário, estação de origem (id, nome, código), acumulado da hora (`precipitation_sum`), máximo da hora (`precipitation_max`) com o seu horário, primeira leitura com o seu horário, e o indicador de interpolação.

**FR-038: Observado de região inativa ou removida.** Se a região estiver inativa ou removida, o clima-service deve devolver só a precipitação observada já gravada, sem chamar a Nimbus (exceção a FR-005 e FR-007, decisão do S4).

**FR-031: Janela máxima.** Se a janela pedida for maior que 7 dias, o clima-service deve recusar a requisição.

**FR-032: Janela obrigatória.** O clima-service deve exigir início e fim explícitos na consulta de observado.

**FR-033: Horas definitivas.** Se uma hora observada já estiver gravada e tiver fechado há mais de 6 horas, o clima-service deve tratá-la como definitiva e nunca mais buscá-la na Nimbus.

**FR-034: Reconciliação recente.** Se a janela pedida incluir horas fechadas nas últimas 6 horas, ou a hora corrente, o clima-service deve buscá-las de novo na Nimbus no máximo uma vez por hora por região e substituir os valores gravados pelos recebidos.

**FR-035: Horas faltantes.** Se a janela pedida tiver horas ausentes no banco, o clima-service deve buscá-las na Nimbus, respeitando o limite de uma vez por hora por região e janela, e gravá-las.

**FR-036: Estação variável.** O clima-service deve guardar e devolver a estação de origem por ponto, já que a estação representativa pode mudar dentro do período.

**FR-037: Histórico observado.** O clima-service deve gravar no SQL Server cada hora observada recebida e mantê-la por tempo indeterminado.

### Cache multinível e resiliência

**FR-040: Ordem de leitura.** O clima-service deve resolver toda leitura nesta ordem: Caffeine (L1, local por instância), Redis (L2, compartilhado), SQL Server (L3) e, por último, a Nimbus. Só chega à Nimbus quando as regras de FR-016/017/033/034/035 exigirem dado novo.

**FR-041: Preenchimento.** Quando um dado for obtido de uma camada inferior (SQL ou Nimbus), o clima-service deve preenchê-lo nas camadas superiores.

**FR-042: Dado desatualizado em falha.** Se a Nimbus falhar (timeout, erro 5xx ou resposta inválida) e houver dado guardado, o clima-service deve devolver o último dado guardado, indicando no corpo da resposta, com os campos `desatualizado=true` e `obtidoEm`, que ele pode estar desatualizado e quando foi obtido.

**FR-043: Falha sem dado.** Se a Nimbus falhar e não houver nenhum dado guardado para a região e período pedidos, o clima-service deve responder com erro de indisponibilidade da fonte.

**FR-044: Redis indisponível.** Se o Redis estiver indisponível, o clima-service deve continuar respondendo via Caffeine e SQL Server, sem falhar a requisição.

### Contrato próprio

**FR-050: Contrato Neovia.** O clima-service deve expor contrato e DTOs próprios da Neovia, documentados em OpenAPI, sem repassar diretamente a resposta da Nimbus nem campos que dependam do modelo de previsão.

## 4. Requisitos não funcionais

### Performance
- Leitura com cache quente (L1 ou L2): **p95 < 200 ms**.
- Leitura que precisa chamar a Nimbus: limitada pelo tempo de resposta da Nimbus. O timeout da chamada externa deve ser configurável.

### Segurança
- **Sem autenticação** no clima-service. O serviço só é acessível pela rede interna do cluster e não deve ser exposto no `gateway-service` nem por ingress público. *(Decisão do solicitante.)*
- `api_key` e `authorization` da Nimbus vêm de **Secret do Kubernetes**, injetadas como variáveis de ambiente. Nunca ficam em código-fonte, arquivos versionados, URLs ou logs.
- Os logs de chamadas à Nimbus não podem registrar os headers de credencial.
- O arquivo `Credenciais-Neovia.txt` na pasta do projeto deve sair do repositório antes do primeiro commit (ver Q-01).

### Escalabilidade e disponibilidade
- Várias instâncias (pods) compartilham o L2 (Redis) e o L3 (SQL Server). O L1 (Caffeine) é por instância.
- Chamadas concorrentes que precisam do mesmo dado novo (mesma região e tipo) não devem gerar chamadas duplicadas à Nimbus além dos limites de FR-002, FR-017 e FR-034.
- Retenção: indefinida para rodadas de previsão e horas observadas.

### Observabilidade
- Registrar, por chamada à Nimbus: endpoint, regiões, duração, status HTTP e se a resposta foi servida desatualizada (sem credenciais).

### Plataforma (padrão do ecossistema Neovia)
- Microsserviço Spring Boot no mesmo padrão dos serviços irmãos (`pgo-api-service`: Spring Boot 3.3.2, Java 17, springdoc-openapi, Flyway com SQL Server, Caffeine, cliente HTTP externo via `RestClient`), com SQL Server, Redis e Caffeine.

## 5. Critérios de aceite (Given/When/Then)

**AC-001: Listagem de regiões em cache**
Given o catálogo foi obtido da Nimbus há menos de 24 h
When um consumidor lista as regiões
Then a resposta traz todas as regiões, inclusive as inativas com o indicador, e nenhuma chamada é feita à Nimbus.

**AC-002: Região inativa recusada**
Given a região 1359 está com `inactive=true` no catálogo
When um consumidor pede previsão ou observado da região 1359
Then a requisição é recusada e nenhuma chamada é feita à Nimbus.

**AC-003: Região desconhecida recusada**
Given o `region_id` 99999 não existe no catálogo
When um consumidor pede previsão da região 99999
Then a requisição é recusada como região inexistente e nenhuma chamada é feita à Nimbus.

**AC-004: Rodada do dia em cache**
Given a rodada guardada da região 1359 tem `run_datetime` = 2026-10-15T00:00:00Z e agora é 2026-10-15T18:00Z
When um consumidor pede a previsão da região 1359
Then a resposta vem do cache, sem chamada à Nimbus, com os pontos da rodada de 2026-10-15.

**AC-005: Comparação em UTC, não no horário local**
Given a rodada guardada tem `run_datetime` = 2026-10-15T00:00:00Z e agora é 2026-10-15T22:30 em Brasília (2026-10-16T01:30Z)
When um consumidor pede a previsão
Then o serviço considera a rodada como de um dia anterior e tenta buscar a rodada de 2026-10-16 na Nimbus.

**AC-006: Re-consulta limitada a 15 min**
Given a rodada guardada é de ontem (UTC) e a última tentativa na Nimbus foi há 5 minutos sem rodada nova
When um consumidor pede a previsão
Then o serviço devolve a rodada de ontem, sem chamar a Nimbus.

**AC-007: Rodada nova gravada sem apagar a anterior**
Given a rodada de ontem está gravada e a Nimbus passa a devolver a rodada de hoje
When o serviço obtém a rodada de hoje
Then as duas rodadas ficam no histórico e a consulta vigente passa a devolver a de hoje.

**AC-008: Previsão por rodada específica**
Given as rodadas de 2026-10-14 e 2026-10-15 estão gravadas para a região 1359
When um consumidor pede a previsão da região 1359 para a rodada 2026-10-14T00:00:00Z
Then o serviço devolve os pontos dessa rodada a partir do banco, sem chamar a Nimbus.

**AC-009: Variáveis nulas e pontos não contínuos**
Given a Nimbus devolveu um ponto com `fog_fsi: null` e pontos de 3 em 3 h depois de 120 h
When o consumidor consulta a previsão
Then `fog_fsi` vem `null` naquele ponto e só os horários retornados aparecem, sem preenchimento.

**AC-010: Região sem dado de previsão**
Given a Nimbus devolveu `forecasts: []` para a região 1706
When o consumidor pede as regiões 1359 e 1706
Then a resposta inclui a região 1706 com a lista vazia.

**AC-011: Observado — janela acima de 7 dias**
Given qualquer região ativa
When o consumidor pede observado de 2026-10-01 a 2026-10-10
Then a requisição é recusada como janela inválida.

**AC-012: Observado — horas definitivas não são rebuscadas**
Given as horas de 2026-10-14 estão gravadas para a região 1674 e agora é 2026-10-15T18:00Z
When o consumidor pede o observado de 2026-10-14
Then a resposta vem do cache ou do banco, sem chamada à Nimbus.

**AC-013: Observado — reconciliação das últimas 6 h**
Given as horas até 17:00Z estão gravadas, agora é 18:20Z e a última busca da região foi às 17:10Z
When o consumidor pede o observado das últimas 12 h
Then o serviço rebusca na Nimbus as horas fechadas nas últimas 6 h e a hora corrente, substitui os valores gravados e devolve a série completa.

**AC-014: Nimbus indisponível com dado guardado**
Given a rodada de ontem está gravada e a Nimbus responde 503
When o consumidor pede a previsão
Then o serviço devolve a rodada de ontem com o indicador de desatualizado e o horário em que foi obtida.

**AC-015: Nimbus indisponível sem dado guardado**
Given não há nenhum dado gravado para a região e a Nimbus está fora do ar
When o consumidor pede a previsão
Then o serviço responde com erro de fonte indisponível.

**AC-016: Redis fora do ar**
Given o Redis está indisponível
When o consumidor pede a previsão de uma região com rodada gravada no banco
Then a resposta é servida normalmente a partir do Caffeine ou do SQL Server.

**AC-017: Credenciais fora de logs**
Given qualquer chamada à Nimbus, com sucesso ou com falha
When os logs do serviço são inspecionados
Then nenhum valor de `api_key` ou `authorization` aparece.

## 6. Tratamento de erros

Códigos confirmados na aprovação. Nas consultas de previsão com várias regiões, os erros de região (inexistente, inativa, sem dado) vão por região no corpo 200, e o 503 só ocorre se todas as regiões pedidas ficarem sem dado (decisão do S2).

| Condição | HTTP | Mensagem ao consumidor |
| --- | --- | --- |
| Parâmetro ausente ou inválido (datas, ids) | 400 | "Parâmetros inválidos: <detalhe>" |
| Janela de observado > 7 dias | 400 | "Janela máxima de 7 dias" |
| `region_id` inexistente no catálogo | 404 | "Região não encontrada" |
| Região inativa | 422 | "Região inativa" |
| Rodada específica inexistente no histórico | 404 | "Rodada não encontrada para a região" |
| Nimbus falhou e há dado guardado | 200 com `desatualizado=true` e `obtidoEm` no corpo | — |
| Nimbus falhou e não há dado guardado | 503 | "Fonte de dados climáticos indisponível" |
| Nimbus recusou as credenciais (401/403) | 503, com log de alerta | "Fonte de dados climáticos indisponível" |
| Nimbus recusou a região (não atribuída) | 404, com log de alerta | "Região não encontrada" |

## 7. Fora de escopo (não objetivos)

- Telas no `portal-web` ou em qualquer frontend.
- Autenticação e autorização no clima-service, e exposição pelo `gateway-service`.
- Alertas ou notificações automáticas por condição climática.
- Jobs agendados de coleta: a obtenção é sempre sob demanda.
- Variáveis observadas além de `precipitation`.
- Conversão ou declaração de unidades das variáveis.
- Vínculo das regiões Nimbus com entidades Neovia (praças, contratos, obras).
- Escolha de modelo meteorológico (a Nimbus decide).
- Interpolação ou preenchimento de pontos de previsão.
- Migração do `pgo-api-service` (hoje integrado ao Open-Meteo em `infra/clima/OpenMeteoClient.java`) para o clima-service: fica para outro PRD.

## 8. Questões abertas

- [ ] **Q-01:** Retirar `Credenciais-Neovia.txt` da pasta do projeto (e incluí-lo no `.gitignore`) antes de qualquer commit, e cadastrar o conteúdo no Secret do Kubernetes.
- [x] **Q-02:** Códigos HTTP da seção 6 confirmados.
- [x] **Q-03:** FR-003 mantido como endpoint interno, limitado a 1x/dia.
- [x] **Q-04:** O indicador vai no corpo (`desatualizado`, `obtidoEm`).

## 9. Decisões da entrevista

| Tema | Decisão |
| --- | --- |
| Consumidores | `pgo-api-service` e `neoviapainel-backend` (o frontend chega aos dados pelo `neoviapainel-backend`) |
| Escopo | Regiões, previsões e precipitação observada |
| Modo de obtenção | Proxy sob demanda + cache |
| Cache | Multinível: Caffeine → Redis → SQL Server → Nimbus |
| Banco | SQL Server |
| Histórico | Persistir tudo, indefinidamente |
| Auth | Nenhuma; só rede interna |
| Falha da Nimbus | Último dado + aviso |
| Contrato | Próprio da Neovia |
| Re-consulta da rodada ausente | 15 min por região |
| API de histórico | Rodada vigente + rodada específica |
| Regiões inativas | Listar com flag; recusar consultas |
| Segredos | Secret do Kubernetes |
| Horas observadas fechadas | Reconciliar as últimas 6 h |
| Janela máxima do observado | 7 dias |
| Unidades | Repassar sem unidade |
| Performance | p95 < 200 ms com cache quente |
| Busca de previsão | Sempre a rodada inteira (15 dias); o consumidor filtra a janela |
| Rotas | Prefixo `/api/clima/` |
| Redis | O mesmo do `neoviapainel-backend` (`REDIS_HOST`/`REDIS_PORT`), com prefixo de chave `clima:` |
| Banco | Schema `clima` no SQL Server compartilhado (Secret `secret`), com `flyway_schema_history_clima` |
| Refresh forçado dentro de 24 h | 200 com o catálogo guardado, sem chamar a Nimbus |
| Região que some do catálogo | Mantida e marcada como removida (sai da listagem; consultas dão 404) |
| Catálogo vazio vindo da Nimbus | Tratado como falha (não grava; fallback com `desatualizado=true`) |
| Testes de integração | Testcontainers (SQL Server e Redis) |
| Rodada de uma região | Maior `run_datetime` entre os pontos retornados |
| Janela enviada à Nimbus | Hoje 00:00 UTC até +15 dias |
| Endpoint de previsão | `GET /api/clima/previsoes?regioes=...&inicio=&fim=`, no máximo 20 regiões |
| Nomes das variáveis | Traduzidos para PT (mapeamento na spec S2); variável não mapeada sai com o nome da Nimbus |
| Mesma rodada devolvida de novo | Sobrescreve |
| Histórico por rodada | Listagem de rodadas (`?de=&ate=` obrigatórios, máx. 90 dias) + consulta de uma região por chamada; `{rodada}` em instante ISO; permitido para regiões inativas ou removidas |
| Observado: endpoint | `GET /api/clima/precipitacoes-observadas/{regionId}?inicio=&fim=`, hora cheia, `fim` exclusivo, uma região por chamada |
| Observado: hora ausente | Tentativas 1x/hora enquanto a hora estiver dentro da janela de 6 h; depois disso fica "sem dado" definitivo. Hora antiga nunca tentada recebe uma tentativa |
| Observado: horas sem dado | Omitidas da série |
| Observado: metadata da Nimbus | Não exposta |
| Observado: região inativa ou removida | Serve só o que está gravado, sem chamar a Nimbus |
| Consulta com várias regiões | Erro por região no corpo 200 (`NAO_ENCONTRADA`, `INATIVA`, `FONTE_INDISPONIVEL`); 503 só se todas as regiões pedidas ficarem `FONTE_INDISPONIVEL` |
