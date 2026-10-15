# Spec S4: Precipitação observada

- **PRD:** `docs/prd/2026-10-15-clima-nimbus-prd.md` (FR-030 a FR-038, FR-040 a FR-044, FR-050)
- **Data:** 2026-10-15
- **Status:** Aprovado para escrita
- **Depende de:** S1 (`docs/superpowers/specs/2026-10-15-clima-nimbus-regioes-spec.md`): catálogo e validação de região, cliente da Nimbus, cache multinível, tratamento de erros e configuração. Não depende de S2 nem de S3.

## 1. Visão geral

Entrega ponta a ponta a série horária de precipitação observada de uma região, para janelas de até 7 dias.
- Horas já gravadas e definitivas são servidas do cache ou do SQL.
- A Nimbus só é chamada para a hora corrente, para as horas fechadas nas últimas 6 h (reconciliação) e para as horas nunca tentadas, sempre no máximo uma vez por hora.
- Tudo o que vem da Nimbus é gravado como histórico permanente.

**Valor demonstrável:** um consumidor chama `GET /api/clima/precipitacoes-observadas/1674?inicio=2026-10-14T00:00:00Z&fim=2026-10-15T00:00:00Z` e recebe as 24 horas observadas. Uma nova chamada não vai à Nimbus para horas definitivas.

## 2. Escopo

### Dentro
- `GET /api/clima/precipitacoes-observadas/{regionId}`.
- Extensão do cliente da Nimbus (S1) para `GET /manage/representative-stations/{region_id}/data-consistency` com `aggregation=hourly` e `variable=precipitation`.
- Regras por hora: corrente, reconciliação de 6 h, definitiva, nunca tentada, sem dado definitivo.
- Tabelas novas no schema `clima`.
- Cache multinível, falha da Nimbus com dado desatualizado.
- Histórico gravado para regiões inativas ou removidas.

### Fora
- Outras variáveis observadas.
- Agregações diferentes de `hourly`.
- Seleção de estação.
- O bloco `metadata` da Nimbus.
- Várias regiões numa chamada.
- Unidades.

## 3. Código existente de referência

**Trabalho novo** no `clima-service`, sobre os componentes do S1. Referências externas:

| Padrão | Referência |
| --- | --- |
| Validade de cache até um instante (fim da hora) | `pgo-api-service/src/main/java/br/com/neoviaengenharia/pgo/infra/cache/CacheLeitura.java:62`, `:168` |
| Leitura L1 → L2 e L2 tolerante a falha | `neoviapainel-backend/src/main/java/br/com/neoviaengenharia/userapi/config/feign/cache/CacheService.java:50`, `:110` |
| Cliente externo com `RestClient` e falha → indisponível | `pgo-api-service/src/main/java/br/com/neoviaengenharia/pgo/infra/clima/OpenMeteoClient.java:22`, `:106` |

## 4. Definições

- **Hora H:** o intervalo `[H, H+1h)`, com H em hora cheia UTC. Corresponde ao `datetime` de um item de `data` da Nimbus.
- **Hora corrente:** a hora H que contém o instante atual.
- **Fechada:** uma hora H fecha em `H+1h`.
- **Recente:** fechada há 6 h ou menos (`H+1h >= agora - 6h`) e não corrente.
- **Antiga:** fechada há mais de 6 h.
- **Definitiva:** hora antiga já gravada com dado, ou já marcada como `sem_dado`.

## 5. Requisitos funcionais (EARS)

**S4-FR-01 (FR-030).** Quando um consumidor chamar `GET /api/clima/precipitacoes-observadas/{regionId}?inicio=&fim=`, o clima-service deve devolver, em ordem crescente, as horas da janela `[inicio, fim)` que tenham dado. Cada hora traz:
- `dataHora`;
- `estacao` (`id`, `nome`, `codigo`);
- `precipitacaoAcumulada`, `precipitacaoMaxima` e `dataHoraMaxima`;
- `primeiraLeitura` e `dataHoraPrimeiraLeitura`;
- `interpolado` e `obtidoEm`.

**S4-FR-02 (FR-031, FR-032).** O clima-service deve exigir `inicio` e `fim` em ISO-8601 com fuso, em hora cheia, com `fim > inicio` e `fim - inicio <= 7 dias`.

**S4-FR-03.** O clima-service deve considerar só as horas até a hora corrente, inclusive. Horas futuras da janela não são buscadas nem devolvidas.

**S4-FR-04 (acesso por região).**
- Região inexistente em `regiao` → 404.
- Região inativa ou removida → devolve só o que estiver gravado, sem chamar a Nimbus (FR-038).
- Região ativa → aplica S4-FR-05 a S4-FR-09.

**S4-FR-05 (FR-033, definitivas).** O clima-service nunca deve buscar na Nimbus uma hora definitiva.

**S4-FR-06 (FR-034, corrente e recentes).** Se a janela incluir a hora corrente ou horas recentes, o clima-service deve buscá-las na Nimbus, desde que a última tentativa para aquela hora e região tenha sido há mais de 1 hora (ou que nunca tenha havido tentativa). Os valores recebidos substituem os gravados.

**S4-FR-07 (FR-035, antigas nunca tentadas).** Se a janela incluir horas antigas sem registro e sem tentativa anterior, o clima-service deve buscá-las na Nimbus uma vez.

**S4-FR-08 (sem dado definitivo).** Quando uma hora antiga for tentada e a Nimbus não a devolver, o clima-service deve marcá-la como `sem_dado` definitiva. Quando uma hora recente ou corrente não vier da Nimbus, ela fica sem registro e volta a ser tentada pelas regras de S4-FR-06 até ficar antiga. A primeira tentativa depois disso aplica a marcação `sem_dado`.

**S4-FR-09 (uma chamada por requisição).** Quando houver horas a buscar, o clima-service deve fazer uma única chamada à Nimbus:
- `start_date` = a menor hora a buscar;
- `end_date` = a maior hora a buscar + 1 h (exclusivo, como no exemplo da Nimbus: 00:00 → 00:00 do dia seguinte = 24 pontos);
- `aggregation=hourly` e `variable=precipitation`.

Horas definitivas devolvidas dentro desse intervalo não são regravadas. Registra a tentativa para todas as horas buscadas.

**S4-FR-10 (FR-036).** O clima-service deve gravar e devolver a estação de origem por hora.

**S4-FR-11 (FR-037).** O clima-service deve manter todas as horas gravadas indefinidamente.

**S4-FR-12 (FR-042).** Se a chamada à Nimbus falhar (timeout, rede, 5xx, 401/403, corpo inválido) e houver ao menos uma hora gravada na janela, o clima-service deve devolver as horas gravadas com `desatualizado=true` no nível da resposta. Nesse caso, as tentativas não contam como feitas para S4-FR-06 a S4-FR-08: uma hora antiga nunca obtida não vira `sem_dado` por causa de falha.

**S4-FR-13 (FR-043).** Se a chamada à Nimbus falhar e não houver nenhuma hora gravada na janela, o clima-service deve responder 503.

**S4-FR-14 (FR-040, FR-041).** O clima-service deve resolver as horas na ordem Caffeine → Redis → SQL e preencher as camadas superiores. Toda gravação de hora vinda da Nimbus deve atualizar ou invalidar as entradas afetadas em L1 e L2.

**S4-FR-15 (FR-044).** Se o Redis estiver indisponível, o clima-service deve seguir com Caffeine e SQL.

**S4-FR-16.** Requisições concorrentes, em uma ou várias instâncias, não devem buscar a mesma hora e região mais de uma vez por hora.

**S4-FR-17.** Quando a Nimbus devolver para a mesma hora um item com `region_id` diferente do pedido, o clima-service deve tratar a resposta como inválida (S4-FR-12/13).

**S4-FR-18 (FR-050).** O clima-service deve documentar o endpoint em OpenAPI.

## 6. Interfaces

### 6.1 `GET /api/clima/precipitacoes-observadas/{regionId}?inicio=&fim=`

Resposta 200:

```json
{
  "regionId": 1674,
  "inicio": "2026-10-14T00:00:00Z",
  "fim": "2026-10-15T00:00:00Z",
  "desatualizado": false,
  "horas": [
    {
      "dataHora": "2026-10-14T00:00:00Z",
      "estacao": { "id": 3078, "nome": "Prudentópolis", "codigo": "mg_h-823" },
      "precipitacaoAcumulada": 0,
      "precipitacaoMaxima": 0,
      "dataHoraMaxima": "2026-10-14T00:00:00Z",
      "primeiraLeitura": null,
      "dataHoraPrimeiraLeitura": null,
      "interpolado": false,
      "obtidoEm": "2026-10-14T07:05:12Z"
    }
  ]
}
```

- `precipitacaoAcumulada`/`precipitacaoMaxima` valem para **aquela hora**, não para a janela.
- `obtidoEm` (por hora): última vez que a hora foi gravada a partir da Nimbus.
- `null` vindo da Nimbus é preservado.
- Horas sem dado são omitidas.

### 6.2 Saída para a Nimbus

`GET {nimbus.base-url}/manage/representative-stations/{region_id}/data-consistency?start_date=<epoch ms>&end_date=<epoch ms>&aggregation=hourly&variable=precipitation`, com headers `api_key`/`authorization` (S1).

### 6.3 Tradução de campos

| Nimbus | Contrato Neovia |
| --- | --- |
| `datetime` | `dataHora` |
| `station_id`, `station_name`, `station_code` | `estacao.id`, `estacao.nome`, `estacao.codigo` |
| `precipitation_sum` | `precipitacaoAcumulada` |
| `precipitation_max` | `precipitacaoMaxima` |
| `precipitation_max_datetime` | `dataHoraMaxima` |
| `precipitation_first` | `primeiraLeitura` |
| `precipitation_first_datetime` | `dataHoraPrimeiraLeitura` |
| `is_interpolated` | `interpolado` |

Ignorados: `period` (texto redundante com `datetime`), `aggregation`, o `period` de nível raiz, `variable` e `metadata`.

## 7. Modelo de dados (schema `clima`)

### 7.1 `hora_observada`

| Coluna | Tipo | Regra |
| --- | --- | --- |
| `id` | BIGINT IDENTITY | PK |
| `region_id` | BIGINT | FK → `regiao.region_id` |
| `data_hora` | DATETIME2 (UTC) | Hora H |
| `sem_dado` | BIT | 1 = hora antiga tentada e não devolvida (S4-FR-08); os demais campos de dado ficam nulos |
| `station_id` | BIGINT | Nulo se `sem_dado` |
| `station_name` | NVARCHAR | Nulo se `sem_dado` |
| `station_code` | NVARCHAR | Nulo se `sem_dado` |
| `precipitation_sum` | DECIMAL | Nulo permitido |
| `precipitation_max` | DECIMAL | Nulo permitido |
| `precipitation_max_datetime` | DATETIME2 (UTC) | Nulo permitido |
| `precipitation_first` | DECIMAL | Nulo permitido |
| `precipitation_first_datetime` | DATETIME2 (UTC) | Nulo permitido |
| `is_interpolated` | BIT | Nulo se `sem_dado` |
| `obtido_em` | DATETIME2 (UTC) | Última gravação |
| — | — | Único (`region_id`, `data_hora`) |

### 7.2 `tentativa_observado`

| Coluna | Tipo | Regra |
| --- | --- | --- |
| `region_id` | BIGINT | PK composta, FK → `regiao` |
| `data_hora` | DATETIME2 (UTC) | PK composta |
| `ultima_tentativa_em` | DATETIME2 (UTC) | Última chamada bem-sucedida à Nimbus que cobriu essa hora |

### 7.3 Chaves de cache
- L1/L2 por hora: `clima:observado:{regionId}:{dataHoraEpochMs}`.
- Horas definitivas: sem validade por tempo.
- Hora corrente ou recente: validade até `ultima_tentativa_em + 1 h`.
- Ao fim da leitura, o serviço monta a série a partir das horas, e a resposta inteira não é cacheada. O p95 < 200 ms para 7 dias (168 horas) com cache quente deve ser verificado; se não for atingido, isso é um achado a levar de volta ao PRD, não uma mudança silenciosa de escopo.

## 8. Casos de borda

| Caso | Comportamento |
| --- | --- |
| Janela inteira antiga e já gravada | Zero chamadas à Nimbus |
| Janela que termina no futuro | Considera só até a hora corrente (S4-FR-03) |
| Janela antiga pedida pela primeira vez | Uma chamada; horas não devolvidas viram `sem_dado` (S4-FR-07/08) |
| Hora recente ausente na Nimbus | Sem registro; nova tentativa após 1 h, até ficar antiga e virar `sem_dado` |
| Hora recente com valor corrigido pela Nimbus | Sobrescreve (S4-FR-06) |
| Estação representativa muda dentro da janela | Cada hora traz a sua estação (S4-FR-10) |
| `inicio`/`fim` fora de hora cheia, sem fuso, `fim <= inicio` ou mais de 7 dias | 400 |
| Região inativa ou removida, sem nada gravado | 200 com `horas: []` |
| Região inexistente | 404 |
| Nimbus fora e algumas horas gravadas | 200 com o gravado e `desatualizado=true` |
| Nimbus fora e nada gravado | 503 |
| Falha da Nimbus em hora antiga nunca obtida | Não vira `sem_dado` (S4-FR-12) |
| Nimbus devolve horas fora do intervalo pedido | Ignoradas |

## 9. Tratamento de erros

| Condição | HTTP | Mensagem |
| --- | --- | --- |
| Parâmetros inválidos (formato, fuso, hora cheia, ordem) | 400 | "Parâmetros inválidos: <detalhe>" |
| Janela acima de 7 dias | 400 | "Janela máxima de 7 dias" |
| Região inexistente em `regiao` | 404 | "Região não encontrada" |
| Nimbus falhou, com horas gravadas | 200 com `desatualizado=true` | — |
| Nimbus falhou, sem horas gravadas | 503 | "Fonte de dados climáticos indisponível" |
| Nimbus recusou a região (não atribuída) | 404, com log de alerta | "Região não encontrada" |
| Nimbus 401/403 | Como as falhas acima, + log ERROR de credencial (S1-FR-11) | — |

## 10. Requisitos não funcionais
- Janela de até 7 dias com cache quente: **p95 < 200 ms** (ver a nota em 7.3).
- No máximo uma chamada à Nimbus por hora e região (S4-FR-06/16).
- Credenciais nunca aparecem em logs. Por chamada, logar região, intervalo, duração e status.

## 11. Critérios de aceite

Todos os horários estão em UTC.

**S4-AC-01: Janela definitiva**
Given as 24 horas de 2026-10-14 da 1674 estão gravadas e agora é 2026-10-15T18:00Z
When `inicio=2026-10-14T00:00:00Z&fim=2026-10-15T00:00:00Z` é pedido
Then as 24 horas são devolvidas, sem chamada à Nimbus.

**S4-AC-02: Primeira consulta de janela antiga**
Given nada está gravado para 2026-10-10 da 1674, agora é 2026-10-15T18:00Z, e a Nimbus devolve 23 das 24 horas
When essa janela é pedida
Then uma chamada é feita com `start_date`=2026-10-10T00:00Z e `end_date`=2026-10-11T00:00Z; as 23 horas são gravadas; a hora ausente fica `sem_dado`; a resposta traz 23 horas.

**S4-AC-03: `sem_dado` não volta à Nimbus**
Given a hora 2026-10-10T05:00Z da 1674 está `sem_dado`
When a janela de 2026-10-10 é pedida de novo
Then nenhuma chamada é feita à Nimbus e a hora 05:00 é omitida.

**S4-AC-04: Reconciliação das últimas 6 h**
Given agora é 18:20Z, as horas até 17:00Z estão gravadas e a última tentativa para 12:00–17:00Z foi às 17:10Z
When a janela 06:00Z–19:00Z é pedida
Then a Nimbus é chamada para 12:00Z–19:00Z (horas recentes e corrente, com mais de 1 h desde a última tentativa); os valores recebidos substituem os gravados; 06:00–11:00 vêm do cache ou do banco.

**S4-AC-05: Limite de 1x/hora**
Given a última tentativa para as horas recentes da 1674 foi há 20 min
When a janela que as inclui é pedida
Then nenhuma chamada é feita à Nimbus.

**S4-AC-06: Hora recente ausente**
Given agora é 18:20Z e a Nimbus não devolveu a hora 16:00Z na última busca, às 17:10Z
When a janela é pedida às 18:20Z
Then a hora 16:00Z é tentada de novo, e não fica `sem_dado` enquanto for recente.

**S4-AC-07: Janela acima de 7 dias**
Given qualquer região ativa
When `inicio=2026-10-01T00:00:00Z&fim=2026-10-09T00:00:00Z` é pedido
Then a resposta é 400 "Janela máxima de 7 dias".

**S4-AC-08: Fora de hora cheia**
Given qualquer região
When `inicio=2026-10-14T00:30:00Z` é pedido
Then a resposta é 400.

**S4-AC-09: Janela no futuro**
Given agora é 2026-10-15T18:20Z
When `inicio=2026-10-15T12:00:00Z&fim=2026-10-16T00:00:00Z` é pedido
Then só as horas até 18:00Z são consideradas e devolvidas.

**S4-AC-10: Estação variável**
Given a Nimbus devolve as horas 00–11 com a estação 3078 e 12–23 com a 3090
When a janela é devolvida
Then cada hora traz a sua estação.

**S4-AC-11: Região inativa com histórico**
Given a 1706 está inativa e tem horas gravadas em 2026-10-10
When a janela de 2026-10-10 é pedida
Then as horas gravadas são devolvidas, sem chamada à Nimbus.

**S4-AC-12: Região inexistente**
Given o 99999 não está em `regiao`
When o observado da 99999 é pedido
Then a resposta é 404.

**S4-AC-13: Nimbus fora, com horas gravadas**
Given as horas 06:00–17:00Z estão gravadas, agora é 18:20Z e a Nimbus responde 503
When a janela 06:00–19:00Z é pedida
Then a resposta é 200 com as horas gravadas e `desatualizado=true`, e nenhuma hora é marcada `sem_dado`.

**S4-AC-14: Nimbus fora, nada gravado**
Given nada está gravado na janela e a Nimbus dá timeout
When a janela é pedida
Then a resposta é 503.

**S4-AC-15: Tradução e nulos**
Given a Nimbus devolve uma hora com `precipitation_first=null` e `is_interpolated=false`
When a série é devolvida
Then a hora traz `primeiraLeitura=null` e `interpolado=false`.

**S4-AC-16: Concorrência**
Given duas instâncias recebem ao mesmo tempo a mesma janela com horas a buscar
When ambas processam
Then só uma chamada é feita à Nimbus para essas horas.

**S4-AC-17: Redis fora**
Given o Redis está inacessível e a janela está gravada no SQL
When a janela é pedida
Then a resposta é 200 normal, com log WARN do L2.

## 12. Estratégia de testes
- **Unidade (com relógio injetável):**
  - classificação de cada hora: corrente, recente, antiga, definitiva, nunca tentada;
  - cálculo do intervalo a buscar (S4-FR-09);
  - regra de `sem_dado` e o efeito de falha (S4-FR-12);
  - validação de parâmetros;
  - tradução.
- **Cliente da Nimbus:** com servidor HTTP simulado:
  - query enviada e headers;
  - 200 com horas faltando ou fora do intervalo, `region_id` divergente;
  - 5xx, 401/403, timeout e corpo inválido.
- **Integração (Testcontainers, SQL Server + Redis):** S4-AC-01, 02, 03, 04, 05, 13, 14, 16 e 17.
- **Desempenho:** medir o p95 da janela de 7 dias com cache quente (nota em 7.3).
- **API:** formato da seção 6.1 e tabela de erros.
