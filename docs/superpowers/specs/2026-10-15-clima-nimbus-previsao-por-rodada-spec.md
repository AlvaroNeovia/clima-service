# Spec S3: Previsão por rodada específica

- **PRD:** `docs/prd/2026-10-15-clima-nimbus-prd.md` (FR-019, FR-020, FR-040, FR-041, FR-044, FR-050)
- **Data:** 2026-10-15
- **Status:** Aprovado para escrita
- **Depende de:** S1 (catálogo, cache, erros) e S2 (`docs/superpowers/specs/2026-10-15-clima-nimbus-previsao-vigente-spec.md`), que fornece as tabelas `rodada_previsao`/`ponto_previsao`, a tradução de variáveis e o filtro de janela.

## 1. Visão geral

Entrega a consulta ao histórico de previsões gravado pelo S2. O consumidor lista as rodadas gravadas de uma região num período e busca os pontos de uma rodada específica. Tudo é servido do cache ou do SQL Server, **sem chamar a Nimbus**.

**Valor demonstrável:** com rodadas gravadas pelo S2, um consumidor lista as rodadas da região 1359 em outubro e abre a de 2026-10-14T00:00:00Z.

## 2. Escopo

### Dentro
- `GET /api/clima/previsoes/{regionId}/rodadas?de=&ate=`.
- `GET /api/clima/previsoes/{regionId}/rodadas/{rodada}?inicio=&fim=`.
- Cache multinível das rodadas consultadas.
- Acesso ao histórico de regiões inativas ou removidas.

### Fora
- Buscar rodadas passadas na Nimbus.
- Comparar rodadas entre si.
- Consultar várias regiões numa chamada.
- Gravar dados: este slice só lê.

## 3. Código existente de referência

**Trabalho novo** no `clima-service`, sobre as tabelas e a tradução do S2 e o cache e os erros do S1. Nenhuma tabela nova. A referência externa é o cache de leitura com dado imutável: `pgo-api-service/src/main/java/br/com/neoviaengenharia/pgo/infra/cache/CacheLeitura.java:57` (entrada sem validade, que só os dados mudam).

## 4. Requisitos funcionais (EARS)

**S3-FR-01 (listagem).** Quando um consumidor chamar `GET /api/clima/previsoes/{regionId}/rodadas` com `de` e `ate`, o clima-service deve devolver as rodadas gravadas da região com `run_datetime` entre `de` e `ate` (inclusive), ordenadas da mais recente para a mais antiga, cada uma com `rodada` e `obtidoEm`.

**S3-FR-02.** O clima-service deve exigir `de` e `ate` em ISO-8601 com fuso, com `ate >= de` e intervalo de no máximo 90 dias.

**S3-FR-03 (rodada específica).** Quando um consumidor chamar `GET /api/clima/previsoes/{regionId}/rodadas/{rodada}`, o clima-service deve devolver os pontos dessa rodada no mesmo formato de item de região do S2 (seção 5.1 da spec S2). Valem as regras:
- `desatualizado=false` e `erro=null`;
- variáveis traduzidas pela tabela 6.3 do S2;
- `null` preservado;
- pontos em ordem crescente.

**S3-FR-04.** Quando `inicio` e/ou `fim` forem informados, o clima-service deve devolver só os pontos dessa rodada dentro da janela (mesma regra de S2-FR-11).

**S3-FR-05.** O clima-service deve casar `{rodada}` com o `run_datetime` gravado (rodada da região, S2-FR-03) por igualdade exata do instante em UTC.

**S3-FR-06 (sem Nimbus).** O clima-service nunca deve chamar a Nimbus para atender a este slice, nem para validar a região.

**S3-FR-07 (regiões inativas e removidas).** O clima-service deve atender às duas operações para qualquer `regionId` presente na tabela `regiao`, inclusive inativo ou removido.

**S3-FR-08.** Se o `regionId` não existir na tabela `regiao`, o clima-service deve responder 404 "Região não encontrada".

**S3-FR-09.** Se a região existir e não houver rodada gravada igual a `{rodada}`, o clima-service deve responder 404 "Rodada não encontrada para a região".

**S3-FR-10.** Se a região existir e não houver rodadas no período, a listagem deve responder 200 com a lista vazia.

**S3-FR-11 (cache, FR-040/041).** O clima-service deve resolver a rodada específica na ordem Caffeine → Redis → SQL e preencher as camadas superiores. Como uma rodada só muda quando a mesma rodada é sobrescrita (S2-FR-07), toda sobrescrita feita pelo S2 deve invalidar a entrada dessa rodada em L1 e L2.

**S3-FR-12 (FR-044).** Se o Redis estiver indisponível, o clima-service deve seguir com Caffeine e SQL.

**S3-FR-13 (FR-050).** O clima-service deve documentar os dois endpoints em OpenAPI.

## 5. Interfaces

### 5.1 `GET /api/clima/previsoes/{regionId}/rodadas?de=&ate=`

Resposta 200:

```json
{
  "regionId": 1359,
  "rodadas": [
    { "rodada": "2026-10-15T00:00:00Z", "obtidoEm": "2026-10-15T03:12:44Z" },
    { "rodada": "2026-10-14T00:00:00Z", "obtidoEm": "2026-10-14T02:58:10Z" }
  ]
}
```

### 5.2 `GET /api/clima/previsoes/{regionId}/rodadas/{rodada}?inicio=&fim=`

- `{rodada}`: instante ISO-8601 com fuso, codificado na URL (ex.: `2026-10-14T00:00:00Z`).
- Resposta 200: um item de região no formato do S2, por exemplo:

```json
{
  "regionId": 1359,
  "rodada": "2026-10-14T00:00:00Z",
  "obtidoEm": "2026-10-14T02:58:10Z",
  "desatualizado": false,
  "erro": null,
  "pontos": [
    { "dataHora": "2026-10-14T00:00:00Z", "rodadaPonto": "2026-10-14T00:00:00Z", "variaveis": { "temperatura": 22.1, "precipitacao": 0 } }
  ]
}
```

## 6. Modelo de dados

Sem tabelas novas. Lê `regiao` (S1), `rodada_previsao` e `ponto_previsao` (S2). A listagem precisa de um índice em `rodada_previsao (region_id, run_datetime)`, que já é coberto pela restrição única do S2.

### Chaves de cache
- L1/L2: `clima:previsao:rodada:{regionId}:{runDatetimeEpochMs}`, com a rodada completa.
- Não há validade por tempo. A entrada é invalidada na sobrescrita (S3-FR-11).
- A listagem não usa cache: consulta direta ao SQL, já que o período varia.

## 7. Casos de borda

| Caso | Comportamento |
| --- | --- |
| Região inativa ou removida com histórico | Atende normalmente (S3-FR-07) |
| `regionId` que nunca existiu | 404 região |
| `{rodada}` válido mas inexistente | 404 rodada |
| `{rodada}` com horário diferente de 00:00Z | Busca exata; normalmente 404 |
| `{rodada}` sem fuso ou mal formado | 400 |
| `de`/`ate` ausentes, sem fuso, `ate < de` ou mais de 90 dias | 400 |
| Rodada vigente do dia pedida por aqui | Atende do histórico, igual a qualquer outra rodada |
| Rodada sobrescrita pelo S2 depois de ter entrado no cache | A próxima leitura traz os novos valores (S3-FR-11) |
| Filtro `inicio`/`fim` sem pontos | 200 com `pontos: []` |

## 8. Tratamento de erros

| Condição | HTTP | Mensagem |
| --- | --- | --- |
| Parâmetros de data inválidos ou intervalo acima de 90 dias | 400 | "Parâmetros inválidos: <detalhe>" |
| `regionId` inexistente em `regiao` | 404 | "Região não encontrada" |
| Rodada inexistente para a região | 404 | "Rodada não encontrada para a região" |
| Erro inesperado | 500 | Mensagem genérica |

## 9. Requisitos não funcionais
- Rodada específica com cache quente: **p95 < 200 ms**.
- Listagem (consulta indexada no SQL): **p95 < 200 ms** para até 90 rodadas. *(Aplicação da meta geral do PRD.)*
- Zero chamadas à Nimbus (S3-FR-06).

## 10. Critérios de aceite

**S3-AC-01: Listagem no período**
Given a 1359 tem rodadas gravadas de 2026-10-10 a 2026-10-15
When `GET /api/clima/previsoes/1359/rodadas?de=2026-10-12T00:00:00Z&ate=2026-10-14T00:00:00Z` é chamado
Then a resposta traz as rodadas 14, 13 e 12, nessa ordem, sem chamada à Nimbus.

**S3-AC-02: Período acima de 90 dias**
Given qualquer região
When a listagem é pedida de 2026-01-01T00:00:00Z a 2026-10-15T00:00:00Z
Then a resposta é 400.

**S3-AC-03: Rodada específica**
Given a 1359 tem a rodada 2026-10-14T00:00:00Z gravada
When `GET /api/clima/previsoes/1359/rodadas/2026-10-14T00:00:00Z` é chamado
Then a resposta traz os pontos dessa rodada com as variáveis traduzidas, `desatualizado=false`, sem chamada à Nimbus.

**S3-AC-04: Filtro na rodada específica**
Given a rodada 2026-10-14T00:00:00Z da 1359 tem 240 pontos
When `inicio=2026-10-14T06:00:00Z&fim=2026-10-14T08:00:00Z` é pedido
Then só os pontos das 06, 07 e 08 h são devolvidos.

**S3-AC-05: Rodada inexistente**
Given a 1359 não tem a rodada 2026-09-01T00:00:00Z
When ela é pedida
Then a resposta é 404 "Rodada não encontrada para a região".

**S3-AC-06: Região inexistente**
Given o `regionId` 99999 não está em `regiao`
When a listagem ou uma rodada da 99999 é pedida
Then a resposta é 404 "Região não encontrada".

**S3-AC-07: Região inativa ou removida com histórico**
Given a 1706 está inativa, a 1800 está removida e ambas têm rodadas gravadas
When a listagem e uma rodada de cada uma são pedidas
Then as respostas são 200 com os dados gravados.

**S3-AC-08: Sobrescrita invalida o cache**
Given a rodada 2026-10-15T00:00:00Z da 1359 está no L1/L2 com `temperatura=35` às 15:00Z, e o S2 sobrescreve essa rodada com 36
When a rodada é pedida de novo
Then a resposta traz `temperatura=36`.

**S3-AC-09: Período sem rodadas**
Given a 1359 não tem rodadas em setembro
When a listagem de setembro é pedida
Then a resposta é 200 com `rodadas: []`.

**S3-AC-10: Redis fora**
Given o Redis está inacessível
When uma rodada gravada é pedida
Then a resposta é 200, vinda do Caffeine ou do SQL.

## 11. Estratégia de testes
- **Unidade:**
  - validação de `de`/`ate` (fuso, ordem, 90 dias) e de `{rodada}`;
  - casamento exato da rodada;
  - regra de acesso para regiões inativas ou removidas.
- **Integração (Testcontainers, SQL Server + Redis):** S3-AC-01, 03, 05, 06, 07, 08 e 10. Os dados de rodada são semeados diretamente nas tabelas do S2.
- **Ausência de chamadas externas:** o cliente da Nimbus é simulado e o teste confirma zero interações em todos os ACs.
- **API:** formatos das seções 5.1 e 5.2 e a tabela de erros.
