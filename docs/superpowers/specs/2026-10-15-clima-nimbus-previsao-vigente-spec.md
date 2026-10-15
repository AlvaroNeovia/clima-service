# Spec S2: Previsão vigente

- **PRD:** `docs/prd/2026-10-15-clima-nimbus-prd.md` (FR-010 a FR-018, FR-020, FR-021, FR-040 a FR-044, FR-050)
- **Data:** 2026-10-15
- **Status:** Aprovado para escrita
- **Depende de:** S1 (`docs/superpowers/specs/2026-10-15-clima-nimbus-regioes-spec.md`): validação de região, cliente da Nimbus, cache multinível, tratamento de erros e configuração.
- **Habilita:** S3 (previsão por rodada), que lê as rodadas que este slice grava.

## 1. Visão geral

Entrega ponta a ponta a consulta da previsão mais recente para até 20 regiões. A rodada inteira (15 dias) é buscada na Nimbus só quando as regras de atualização em UTC exigem. Cada rodada é gravada como histórico, e a resposta segue o contrato Neovia, com variáveis em português e erro por região.

**Valor demonstrável:** um consumidor chama `GET /api/clima/previsoes?regioes=1359,1706` e recebe a previsão vigente das duas regiões. Durante o resto do dia UTC, novas consultas não chamam a Nimbus.

## 2. Escopo

### Dentro
- `GET /api/clima/previsoes`.
- Extensão do cliente da Nimbus (S1) para `POST /retrieve-forecasts`.
- Regras de atualização em UTC e a re-consulta a cada 15 min.
- Gravação das rodadas e dos pontos (tabelas novas no schema `clima`).
- Cache multinível da rodada vigente por região.
- Tradução das variáveis para português.
- Erro por região.

### Fora
- Consulta por rodada específica (S3).
- Precipitação observada (S4).
- Interpolação ou preenchimento de pontos.
- Declaração de unidades.

## 3. Código existente de referência

Tudo deste slice é **trabalho novo** no `clima-service`, sobre os componentes criados em S1 (cliente da Nimbus, cache, validação de região, tratamento de erros). Referências externas:

| Padrão | Referência |
| --- | --- |
| Validade de entrada de cache até um instante (virada do dia) | `pgo-api-service/src/main/java/br/com/neoviaengenharia/pgo/infra/cache/CacheLeitura.java:62`, `:168` |
| Leitura L1 → L2 com promoção e escrita assíncrona no L2 | `neoviapainel-backend/src/main/java/br/com/neoviaengenharia/userapi/config/feign/cache/CacheService.java:50`, `:85` |
| Cliente externo com `RestClient` e timeouts | `pgo-api-service/src/main/java/br/com/neoviaengenharia/pgo/infra/config/ClimaConfig.java:16` |

## 4. Requisitos funcionais (EARS)

**S2-FR-01 (FR-010).** Quando um consumidor chamar `GET /api/clima/previsoes` com `regioes` contendo de 1 a 20 `regionId` distintos, o clima-service deve devolver um item por região pedida, na ordem pedida, com a previsão da rodada vigente ou com um erro da região.

**S2-FR-02 (FR-004 a FR-006).** O clima-service deve validar cada região com a validação de S1. Região inexistente ou removida sai com erro `NAO_ENCONTRADA`; região inativa, com erro `INATIVA`. Nenhuma das duas vai à Nimbus.

**S2-FR-03 (Rodada da região).** O clima-service deve identificar a rodada de uma região pelo **maior** `run_datetime` entre os pontos recebidos da Nimbus para ela.

**S2-FR-04 (FR-016, FR-018).** Se a rodada vigente gravada para a região tiver a data UTC do seu `run_datetime` igual à data UTC atual, o clima-service deve responder com ela, a partir de Caffeine → Redis → SQL, sem chamar a Nimbus.

**S2-FR-05 (FR-017).** Se não houver rodada gravada para a região, ou se a rodada gravada for de uma data UTC anterior à atual:
- se a última tentativa na Nimbus para a região tiver ocorrido há menos de 15 min, o clima-service deve responder com a rodada gravada (se houver) sem chamar a Nimbus;
- caso contrário, deve buscar a região na Nimbus e registrar o instante da tentativa.

**S2-FR-06 (FR-011, FR-021).** Quando precisar buscar previsão, o clima-service deve fazer **uma única** chamada `POST /retrieve-forecasts`, com:
- `regions` contendo só as regiões da requisição que precisam de dado novo;
- `datetime_start` = início do dia UTC atual (00:00:00Z), em epoch ms;
- `datetime_end` = `datetime_start` + 15 dias, em epoch ms;
- `frequency` = `3600000`;
- `integration` = `true`.

**S2-FR-07 (FR-020).** Quando a Nimbus devolver pontos para uma região:
- se a rodada recebida (S2-FR-03) ainda não estiver gravada, o clima-service deve gravá-la como nova, sem alterar as anteriores;
- se já estiver gravada, deve **sobrescrever** os pontos dessa rodada pelos recebidos.

Em ambos os casos, a rodada mais recente da região passa a ser a vigente, e L1 e L2 da região são atualizados. Na sobrescrita, a entrada dessa rodada no cache de rodada específica do S3 (`clima:previsao:rodada:{regionId}:{runDatetimeEpochMs}`) também deve ser invalidada.

**S2-FR-08.** Se a Nimbus devolver para a região uma rodada mais antiga que a vigente gravada, o clima-service deve manter a vigente, registrar a tentativa e não gravar a rodada recebida como vigente.

**S2-FR-09 (FR-015).** Se a Nimbus devolver `forecasts: []` para uma região, o clima-service deve registrar a tentativa e responder com a rodada gravada, se houver, com `desatualizado=false` (não é falha da fonte). Se não houver, deve responder a região com `pontos: []` e `rodada: null`, sem erro.

**S2-FR-10 (FR-013).** O clima-service deve devolver só os horários efetivamente recebidos, ordenados de forma crescente, sem preencher nem interpolar.

**S2-FR-11 (FR-012).** Quando `inicio` e/ou `fim` forem informados, o clima-service deve devolver só os pontos da rodada vigente com `dataHora >= inicio` e `dataHora <= fim`. O filtro nunca dispara chamada à Nimbus por si só.

**S2-FR-12 (FR-014, tradução).** O clima-service deve expor as variáveis de cada ponto com os nomes da tabela da seção 6.3. Variável recebida fora da tabela deve sair com o nome original da Nimbus. Variável recebida como `null` deve sair `null`. Variável não recebida não aparece.

**S2-FR-13 (FR-042).** Se a busca na Nimbus falhar (timeout, rede, 5xx, 401/403, corpo inválido) e houver rodada gravada para a região, o clima-service deve responder a região com essa rodada, com `desatualizado=true` e o `obtidoEm` dessa rodada. A tentativa fica registrada para S2-FR-05.

**S2-FR-14 (FR-043).** Se a busca na Nimbus falhar e não houver rodada gravada para a região, o clima-service deve responder a região com erro `FONTE_INDISPONIVEL`.

**S2-FR-15.** Se **todas** as regiões pedidas resultarem em `FONTE_INDISPONIVEL`, o clima-service deve responder 503. Nos demais casos, com parâmetros válidos, deve responder 200.

**S2-FR-16 (FR-044).** Se o Redis estiver indisponível, o clima-service deve seguir com Caffeine e SQL, como em S1-FR-12.

**S2-FR-17.** Chamadas concorrentes, em uma ou várias instâncias, que precisem buscar a mesma região não devem gerar mais de uma chamada à Nimbus por região dentro da janela de 15 min de S2-FR-05.

**S2-FR-18 (FR-050).** O clima-service deve documentar o endpoint em OpenAPI.

## 5. Interfaces

### 5.1 `GET /api/clima/previsoes`

| Parâmetro | Obrigatório | Regra |
| --- | --- | --- |
| `regioes` | Sim | Lista separada por vírgula, de 1 a 20 inteiros distintos |
| `inicio` | Não | ISO-8601 com fuso (ex.: `2026-10-15T00:00:00Z`) |
| `fim` | Não | ISO-8601 com fuso; se `inicio` também vier, deve ser `>= inicio` |

Resposta 200:

```json
{
  "regioes": [
    {
      "regionId": 1359,
      "rodada": "2026-10-15T00:00:00Z",
      "obtidoEm": "2026-10-15T03:12:44Z",
      "desatualizado": false,
      "erro": null,
      "pontos": [
        {
          "dataHora": "2026-10-15T15:00:00Z",
          "rodadaPonto": "2026-10-15T00:00:00Z",
          "variaveis": {
            "direcaoVento": 297,
            "temperatura": 35,
            "rajadaVento": 18.14,
            "velocidadeVento": 10.8,
            "umidadeRelativa": 24.19,
            "precipitacao": 0.2,
            "nevoeiroFsi": 68.63,
            "nevoeiroVisibilidade": 5
          }
        }
      ]
    },
    {
      "regionId": 1706,
      "rodada": null,
      "obtidoEm": null,
      "desatualizado": false,
      "erro": { "codigo": "INATIVA", "mensagem": "Região inativa" },
      "pontos": []
    }
  ]
}
```

- `rodada`: identificação da rodada vigente da região (S2-FR-03), em ISO-8601 UTC.
- `rodadaPonto`: o `run_datetime` próprio do ponto, que pode ser anterior a `rodada`.
- `obtidoEm`: quando essa rodada foi obtida (ou sobrescrita) a partir da Nimbus.
- `erro.codigo`: `NAO_ENCONTRADA`, `INATIVA` ou `FONTE_INDISPONIVEL`.

### 5.2 Saída para a Nimbus

`POST {nimbus.base-url}/retrieve-forecasts` com headers `api_key`/`authorization` (como em S1) e o corpo:

```json
{ "regions": [{"region_id": 1359}], "datetime_start": 1792022400000, "datetime_end": 1793318400000, "frequency": 3600000, "integration": true }
```

Os valores do exemplo são para 2026-10-15T00:00Z até 2026-10-30T00:00Z.

## 6. Modelo de dados (schema `clima`)

### 6.1 `rodada_previsao`

| Coluna | Tipo | Regra |
| --- | --- | --- |
| `id` | BIGINT IDENTITY | PK |
| `region_id` | BIGINT | FK → `regiao.region_id` |
| `run_datetime` | DATETIME2 (UTC) | Rodada (S2-FR-03) |
| `obtido_em` | DATETIME2 (UTC) | Última gravação ou sobrescrita |
| — | — | Único (`region_id`, `run_datetime`) |

### 6.2 `ponto_previsao`

| Coluna | Tipo | Regra |
| --- | --- | --- |
| `id` | BIGINT IDENTITY | PK |
| `rodada_id` | BIGINT | FK → `rodada_previsao.id` |
| `data_hora` | DATETIME2 (UTC) | `datetime` da Nimbus |
| `run_datetime` | DATETIME2 (UTC) | `run_datetime` do ponto |
| `variaveis` | NVARCHAR(MAX), JSON | Objeto com as chaves **originais** da Nimbus e seus valores, inclusive `null` |
| — | — | Único (`rodada_id`, `data_hora`) |

### 6.3 Tradução de variáveis

| Nimbus | Contrato Neovia |
| --- | --- |
| `wind_direction` | `direcaoVento` |
| `temperature` | `temperatura` |
| `wind_gust` | `rajadaVento` |
| `wind_speed` | `velocidadeVento` |
| `relative_humidity` | `umidadeRelativa` |
| `precipitation` | `precipitacao` |
| `fog_fsi` | `nevoeiroFsi` |
| `fog_vis` | `nevoeiroVisibilidade` |

A tradução acontece na saída. O banco guarda os nomes originais (6.2).

### 6.4 `tentativa_previsao`

| Coluna | Tipo | Regra |
| --- | --- | --- |
| `region_id` | BIGINT | PK, FK → `regiao.region_id` |
| `ultima_tentativa_em` | DATETIME2 (UTC) | Instante da última chamada à Nimbus para a região, com ou sem sucesso |

### 6.5 Chaves de cache
- L1/L2: `clima:previsao:vigente:{regionId}`, com a rodada vigente completa (pontos, `obtidoEm`).
- A validade lógica termina na próxima virada do dia UTC, quando a rodada é do dia atual. Quando a rodada é de um dia anterior, termina em `ultima_tentativa_em + 15 min`.
- O filtro `inicio`/`fim` é aplicado depois da leitura do cache e não faz parte da chave.

## 7. Casos de borda

| Caso | Comportamento |
| --- | --- |
| 22:30 em Brasília (01:30Z do dia seguinte), rodada de "hoje" local | A rodada é do dia UTC anterior; aplica S2-FR-05 |
| Pontos com `run_datetime` diferentes na mesma região | A rodada é o maior; cada ponto mantém o seu `rodadaPonto` |
| Resolução horária até 120 h e de 3 em 3 h depois | Devolve os pontos como vieram (S2-FR-10) |
| Variável `null` num ponto | Sai `null` |
| Variável nova da Nimbus (ex.: `pressure`) | Sai com o nome original (S2-FR-12) |
| Mesma rodada devolvida com valores diferentes | Sobrescreve (S2-FR-07) |
| Nimbus devolve rodada mais antiga que a gravada | Mantém a vigente (S2-FR-08) |
| Nimbus omite uma região pedida na resposta | Tratado como falha só dessa região (S2-FR-13/14) |
| `regioes` com duplicatas, vazio, mais de 20 ou não numérico | 400 |
| `fim < inicio` ou data sem fuso | 400 |
| Janela de filtro fora dos pontos da rodada | A região sai com `pontos: []` |
| Mistura de regiões válidas, inativas e inexistentes | 200 com erro por região (S2-FR-01/02) |
| Todas as regiões sem dado e a Nimbus fora | 503 (S2-FR-15) |
| Todas as regiões inexistentes ou inativas | 200 com erro por região |

## 8. Tratamento de erros

| Condição | HTTP | Mensagem |
| --- | --- | --- |
| `regioes` ausente, vazio, duplicado, com mais de 20 ou não numérico | 400 | "Parâmetros inválidos: <detalhe>" |
| `inicio`/`fim` inválidos, sem fuso ou `fim < inicio` | 400 | "Parâmetros inválidos: <detalhe>" |
| Região inexistente ou removida | 200, item com `erro.codigo=NAO_ENCONTRADA` | "Região não encontrada" |
| Região inativa | 200, item com `erro.codigo=INATIVA` | "Região inativa" |
| Nimbus falhou e há rodada gravada | 200, item com `desatualizado=true` | — |
| Nimbus falhou e não há rodada gravada | 200, item com `erro.codigo=FONTE_INDISPONIVEL` | "Fonte de dados climáticos indisponível" |
| Todas as regiões em `FONTE_INDISPONIVEL` | 503 | "Fonte de dados climáticos indisponível" |
| Nimbus 401/403 | Como as falhas acima, + log ERROR de credencial (S1-FR-11) | — |

## 9. Requisitos não funcionais
- Até 20 regiões com cache quente (L1/L2): **p95 < 200 ms**.
- No máximo uma chamada à Nimbus por região a cada 15 min enquanto a rodada do dia não chega, e nenhuma depois que ela chega, até a virada do dia UTC (S2-FR-04/05/17).
- As credenciais nunca aparecem em logs (S1).
- Por chamada à Nimbus, logar as regiões, a duração, o status e as rodadas recebidas.

## 10. Critérios de aceite

Todos os horários estão em UTC, salvo quando indicado.

**S2-AC-01: Rodada do dia em cache**
Given a rodada vigente gravada da 1359 é 2026-10-15T00:00Z e agora é 2026-10-15T18:00Z
When `GET /api/clima/previsoes?regioes=1359` é chamado
Then a resposta traz a rodada 2026-10-15T00:00Z com `desatualizado=false`, sem chamada à Nimbus.

**S2-AC-02: Primeira consulta**
Given não há rodada gravada para a 1359 e a Nimbus devolve pontos com `run_datetime` 2026-10-15T00:00Z
When a previsão da 1359 é pedida
Then uma chamada `retrieve-forecasts` é feita com `datetime_start`=2026-10-15T00:00Z, `datetime_end`=2026-10-30T00:00Z, `frequency=3600000` e `integration=true`; a rodada é gravada; a resposta traz os pontos.

**S2-AC-03: UTC, não horário local**
Given a rodada gravada é 2026-10-15T00:00Z e agora é 2026-10-15T22:30 em Brasília (2026-10-16T01:30Z), com a última tentativa às 2026-10-16T01:00Z
When a previsão é pedida
Then o serviço chama a Nimbus (já se passaram mais de 15 min) em busca da rodada de 2026-10-16.

**S2-AC-04: Re-consulta a cada 15 min**
Given a rodada gravada é de ontem e a última tentativa foi há 5 min
When a previsão é pedida
Then a resposta traz a rodada de ontem com `desatualizado=false` e nenhuma chamada é feita à Nimbus.

**S2-AC-05: Rodada nova preserva o histórico**
Given a rodada 2026-10-14T00:00Z está gravada e a Nimbus devolve 2026-10-15T00:00Z
When a busca é feita
Then as duas rodadas existem em `rodada_previsao` e a resposta traz 2026-10-15T00:00Z.

**S2-AC-06: Mesma rodada sobrescreve**
Given a rodada 2026-10-15T00:00Z está gravada com `temperature=35` às 15:00Z e a Nimbus devolve a mesma rodada com `temperature=36`
When a busca é feita
Then continua existindo uma única rodada 2026-10-15T00:00Z, com `temperatura=36` às 15:00Z.

**S2-AC-07: Maior `run_datetime` define a rodada**
Given a Nimbus devolve para a 1359 pontos com `run_datetime` 2026-10-14T00:00Z e 2026-10-15T00:00Z
When a resposta é processada
Then a rodada da região é 2026-10-15T00:00Z e cada ponto mantém o seu `rodadaPonto`.

**S2-AC-08: Várias regiões numa chamada**
Given a 1359 tem a rodada do dia em cache e a 1706 e a 1710 precisam de dado novo
When `regioes=1359,1706,1710` é pedido
Then uma única chamada à Nimbus é feita, com `regions` = [1706, 1710].

**S2-AC-09: Erro por região**
Given a 1359 está ativa, a 1706 inativa e a 99999 não existe
When `regioes=1359,1706,99999` é pedido
Then a resposta 200 traz a 1359 com pontos, a 1706 com `erro.codigo=INATIVA` e a 99999 com `erro.codigo=NAO_ENCONTRADA`, nessa ordem; só a 1359 vai à Nimbus, se precisar.

**S2-AC-10: Tradução e nulos**
Given um ponto da Nimbus com `temperature=35`, `fog_fsi=null`, `pressure=1012`, sem `wind_gust`
When a previsão é devolvida
Then o ponto traz `temperatura=35`, `nevoeiroFsi=null` e `pressure=1012`, e não traz `rajadaVento`.

**S2-AC-11: Pontos não contínuos**
Given a Nimbus devolveu pontos horários até 120 h e de 3 em 3 h depois
When a previsão é devolvida
Then só esses horários aparecem, em ordem crescente.

**S2-AC-12: Filtro de janela**
Given a rodada vigente da 1359 tem pontos de 2026-10-15T00:00Z a 2026-10-30T00:00Z
When `inicio=2026-10-16T00:00:00Z&fim=2026-10-16T05:00:00Z` é pedido
Then só os pontos dessa janela são devolvidos, sem chamada à Nimbus.

**S2-AC-13: Região sem dado na Nimbus**
Given não há rodada gravada para a 1706 e a Nimbus devolve `forecasts: []`
When a previsão da 1706 é pedida
Then a região sai com `rodada=null`, `pontos=[]` e `erro=null`.

**S2-AC-14: Nimbus fora, com rodada gravada**
Given a rodada de ontem está gravada, a última tentativa foi há 20 min e a Nimbus responde 503
When a previsão é pedida
Then a região sai com a rodada de ontem, `desatualizado=true` e o `obtidoEm` dessa rodada.

**S2-AC-15: Nimbus fora, sem dado, uma de duas regiões**
Given a 1359 tem rodada gravada, a 1706 não tem e a Nimbus dá timeout
When `regioes=1359,1706` é pedido
Then a resposta é 200, a 1359 sai com `desatualizado=true` e a 1706 com `erro.codigo=FONTE_INDISPONIVEL`.

**S2-AC-16: Nimbus fora, sem dado, todas as regiões**
Given nenhuma das regiões pedidas tem rodada gravada e a Nimbus está fora
When a previsão é pedida
Then a resposta é 503.

**S2-AC-17: Parâmetros inválidos**
Given qualquer estado
When `regioes` vem com 21 ids, ou vazio, ou `fim < inicio`
Then a resposta é 400.

**S2-AC-18: Concorrência**
Given duas instâncias recebem ao mesmo tempo um pedido da 1359, que precisa de dado novo
When ambas processam
Then só uma chamada à Nimbus é feita para a 1359.

**S2-AC-19: Redis fora**
Given o Redis está inacessível e a rodada do dia da 1359 está no SQL
When a previsão é pedida
Then a resposta é 200 normal, sem chamada à Nimbus, com log WARN do L2.

## 11. Estratégia de testes
- **Unidade:**
  - decisão "precisa buscar?": data UTC, janela de 15 min, ausência de rodada;
  - cálculo da janela enviada (00:00Z, +15 dias);
  - identificação da rodada (maior `run_datetime`);
  - comparação de rodadas: nova, igual (sobrescreve), mais antiga (ignora);
  - tradução de variáveis;
  - filtro de janela;
  - montagem do erro por região e a regra do 503.
- **Relógio:** as regras de data dependem de um relógio injetável, para testar a virada do dia UTC e o caso de Brasília (S2-AC-03).
- **Cliente da Nimbus:** com servidor HTTP simulado:
  - corpo enviado;
  - 200 com várias regiões, `forecasts: []`, região omitida;
  - 5xx, 401/403, timeout e corpo inválido.
- **Integração (Testcontainers, SQL Server + Redis):** S2-AC-02, 05, 06, 08, 14, 15, 16, 18 e 19.
- **API:** formato da seção 5.1, validação de parâmetros e ordem dos itens.
