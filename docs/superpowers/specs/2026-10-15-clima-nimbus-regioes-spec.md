# Spec S1: Catálogo de regiões

- **PRD:** `docs/prd/2026-10-15-clima-nimbus-prd.md` (FR-001 a FR-007, FR-040 a FR-044, FR-050)
- **Data:** 2026-10-15
- **Status:** Aprovado para escrita
- **Depende de:** nada. Este slice cria o serviço.
- **Habilita:** S2 (previsão vigente) e S4 (precipitação observada), pela validação de região.

## 1. Visão geral

Primeiro slice do `clima-service`. Ele entrega, ponta a ponta, a listagem das regiões Nimbus disponíveis para as credenciais Neovia: contrato REST próprio, cliente da Nimbus, cache Caffeine → Redis → SQL Server, histórico do catálogo e tolerância a falha da Nimbus e do Redis. Também entrega o componente de validação de região que S2 e S4 vão usar.

**Valor demonstrável:** um consumidor interno (`pgo-api-service` ou `neoviapainel-backend`) chama `GET /api/clima/regioes` e recebe o catálogo. A Nimbus é chamada no máximo uma vez a cada 24 h.

## 2. Escopo

### Dentro
- O serviço `clima-service` (novo; a pasta não tem código hoje).
- Cliente HTTP da Nimbus só para `GET /forecast-regions?integration=true`.
- Persistência do catálogo no schema `clima` (SQL Server compartilhado).
- Cache multinível do catálogo.
- `GET /api/clima/regioes` e `POST /api/clima/regioes/atualizar`.
- Validação de região, reutilizável: inexistente/removida → 404; inativa → 422.
- Configuração de deploy: credenciais da Nimbus no Secret do Kubernetes.

### Fora
- Previsão (S2, S3) e precipitação observada (S4).
- Autenticação, exposição no `gateway-service` e ingress público.
- Migração do `pgo-api-service` (Open-Meteo).

## 3. Código existente de referência

O `clima-service` não tem código: **tudo aqui é trabalho novo**. Os arquivos abaixo, de serviços irmãos, são o padrão a seguir. Nenhum deles é alterado.

| Padrão | Referência |
| --- | --- |
| Layout `domain/app/infra/ui` | `pgo-api-service/src/main/java/br/com/neoviaengenharia/pgo/` |
| Cliente externo com `RestClient` e timeouts | `pgo-api-service/src/main/java/br/com/neoviaengenharia/pgo/infra/config/ClimaConfig.java:16` |
| Falha externa vira exceção de indisponibilidade | `pgo-api-service/src/main/java/br/com/neoviaengenharia/pgo/infra/clima/OpenMeteoClient.java:22`, `:106` |
| Tratamento centralizado de erros | `pgo-api-service/src/main/java/br/com/neoviaengenharia/pgo/ui/exceptions/ExceptionManager.java:24` |
| Caffeine com single-flight e validade | `pgo-api-service/src/main/java/br/com/neoviaengenharia/pgo/infra/cache/CacheLeitura.java:40`, `:168` |
| Leitura L1 → L2 com promoção; L2 tolerante a falha | `neoviapainel-backend/src/main/java/br/com/neoviaengenharia/userapi/config/feign/cache/CacheService.java:23`, `:50`, `:110` |
| Redis via `REDIS_HOST`/`REDIS_PORT` | `neoviapainel-backend/src/main/resources/application.properties` (chaves `spring.data.redis.*`) |
| Datasource por ambiente, Flyway com schema próprio, `ddl-auto=validate` | `pgo-api-service/src/main/resources/application.properties:4-19` |
| Variáveis de ambiente vindas do Secret `secret` | `pgo-api-service/k8s/04-deployment.yaml:60-78` |

## 4. Requisitos funcionais (EARS)

**S1-FR-01 (FR-001).** Quando um consumidor chamar `GET /api/clima/regioes`, o clima-service deve devolver todas as regiões do catálogo não marcadas como removidas, inclusive as inativas, com `regionId`, `nome`, `alias`, `inativa` e `estadoId`.

**S1-FR-02 (FR-002).** O clima-service deve chamar `GET /forecast-regions?integration=true` da Nimbus no máximo uma vez a cada 24 h (contadas a partir da última busca bem-sucedida), considerando todas as instâncias.

**S1-FR-03 (FR-002, FR-040).** Quando o catálogo for pedido, o clima-service deve resolvê-lo na ordem Caffeine → Redis → SQL Server. Só vai à Nimbus se a última busca bem-sucedida tiver mais de 24 h ou se não houver catálogo gravado.

**S1-FR-04 (FR-041).** Quando o catálogo vier de uma camada inferior, o clima-service deve preenchê-lo nas camadas superiores.

**S1-FR-05 (FR-003).** Quando um consumidor chamar `POST /api/clima/regioes/atualizar`:
- se a última busca bem-sucedida tiver mais de 24 h, o clima-service deve buscar o catálogo na Nimbus;
- se tiver 24 h ou menos, deve devolver 200 com o catálogo guardado e o `obtidoEm` vigente, sem chamar a Nimbus.

**S1-FR-06.** Quando o catálogo for obtido da Nimbus, o clima-service deve, para cada região recebida, inserir ou atualizar `nome`, `alias`, `inativa` e `estadoId`, identificando a região só pelo `region_id` (FR-004). Também deve registrar o instante da busca e invalidar o L1 e o L2 do catálogo.

**S1-FR-07 (FR-007).** Quando uma região gravada não vier no catálogo obtido da Nimbus, o clima-service deve marcá-la como removida, sem apagá-la. Se ela voltar num catálogo posterior, a marca é retirada.

**S1-FR-08 (FR-004, FR-005, FR-006).** O clima-service deve oferecer uma validação de região para uso interno (S2, S4):
- `regionId` inexistente ou removido → falha "região não encontrada";
- região inativa → falha "região inativa";
- região ativa → sucesso.

A validação usa o catálogo pelas regras S1-FR-03 e nunca chama a Nimbus só para validar, a não ser que o próprio catálogo tenha vencido.

**S1-FR-09 (FR-042).** Se a busca na Nimbus falhar (timeout, erro de rede, 5xx, 401/403 ou corpo inválido) e houver catálogo gravado, o clima-service deve devolver o catálogo gravado com `desatualizado=true` e o `obtidoEm` da última busca bem-sucedida.

**S1-FR-10 (FR-043).** Se a busca na Nimbus falhar e não houver catálogo gravado, o clima-service deve responder 503.

**S1-FR-11.** Se a Nimbus responder 401 ou 403, o clima-service deve registrar log de nível ERROR indicando falha de credencial, sem os valores das credenciais.

**S1-FR-12 (FR-044).** Se o Redis estiver indisponível, o clima-service deve seguir com Caffeine e SQL Server, registrar log WARN e não falhar a requisição.

**S1-FR-14.** Se a Nimbus devolver o catálogo vazio (`[]`), o clima-service deve tratá-lo como resposta inválida: não grava nada, não marca regiões como removidas, aplica S1-FR-09/S1-FR-10 e registra log WARN.

**S1-FR-13 (FR-050).** O clima-service deve documentar os dois endpoints em OpenAPI (springdoc).

## 5. Interfaces

### 5.1 `GET /api/clima/regioes`

Resposta 200:

```json
{
  "regioes": [
    { "regionId": 1359, "nome": "01 SP-334 - Franca", "alias": "f_neo_fra", "inativa": false, "estadoId": 25 },
    { "regionId": 1706, "nome": "01 Votorantim (Sorocaba)", "alias": "f_neo_vot", "inativa": false, "estadoId": 25 }
  ],
  "obtidoEm": "2026-10-15T12:00:00Z",
  "desatualizado": false
}
```

- `obtidoEm`: instante (ISO-8601, UTC) da última busca bem-sucedida na Nimbus.
- `desatualizado`: `true` só quando a busca necessária falhou e o dado veio do que estava guardado (S1-FR-09).

### 5.2 `POST /api/clima/regioes/atualizar`

Sem corpo. A resposta 200 tem o mesmo formato de 5.1. Endpoint interno (rede do cluster).

### 5.3 Validação de região (interface interna)

É uma operação de aplicação, não um endpoint REST. Recebe um `regionId` e devolve sucesso, ou falha com um de dois motivos (`NAO_ENCONTRADA`, `INATIVA`). O tratamento de erros mapeia esses motivos para 404 e 422.

### 5.4 Saída para a Nimbus

- `GET {nimbus.base-url}/forecast-regions?integration=true`
- Headers `api_key` e `authorization` (valor direto, sem `Bearer`), lidos das variáveis de ambiente `NIMBUS_API_KEY` e `NIMBUS_AUTHORIZATION`.
- `nimbus.base-url` é configurável, com padrão `https://cronos-api.nimbusmeteorologia.com.br`. O timeout também é configurável.

### 5.5 Configuração e deploy
- `NIMBUS_API_KEY` e `NIMBUS_AUTHORIZATION` vêm de chaves no Secret do Kubernetes (`secretKeyRef`), como em `pgo-api-service/k8s/04-deployment.yaml:60-78`.
- O datasource segue `SPRING_DATASOURCE_*` do Secret `secret`. Flyway com schema `clima`, tabela `flyway_schema_history_clima`, e `ddl-auto=validate`.
- Redis: `REDIS_HOST`/`REDIS_PORT` (mesma instância do `neoviapainel-backend`), com todas as chaves prefixadas `clima:`.
- Service do Kubernetes só interno (ClusterIP), sem ingress e sem rota no gateway.

## 6. Modelo de dados (schema `clima`)

### Tabela `regiao`

| Coluna | Tipo | Regra |
| --- | --- | --- |
| `region_id` | BIGINT | PK. Identificador vindo da Nimbus, nunca gerado localmente |
| `nome` | NVARCHAR | Obrigatório |
| `alias` | NVARCHAR | Obrigatório |
| `inativa` | BIT | Obrigatório |
| `estado_id` | BIGINT | Obrigatório |
| `removida` | BIT | Obrigatório, padrão 0 (S1-FR-07) |
| `atualizado_em` | DATETIME2 (UTC) | Última vez que a linha mudou |

### Tabela `catalogo_regioes_busca`

Uma linha por busca bem-sucedida na Nimbus. Serve de histórico do catálogo e de fonte do `obtidoEm`.

| Coluna | Tipo | Regra |
| --- | --- | --- |
| `id` | BIGINT IDENTITY | PK |
| `obtido_em` | DATETIME2 (UTC) | Instante da busca |
| `total_regioes` | INT | Quantidade recebida |

### Chaves de cache
- L1 (Caffeine, por instância) e L2 (Redis): chave `clima:regioes:catalogo`, com o catálogo e o `obtidoEm`.
- A validade lógica de L1 e L2 termina em `obtidoEm + 24 h`. Depois disso a leitura desce até o SQL e decide se vai à Nimbus (S1-FR-03).

## 7. Casos de borda

| Caso | Comportamento |
| --- | --- |
| Primeira subida, banco vazio, Nimbus ok | Busca na Nimbus, grava, responde |
| Primeira subida, banco vazio, Nimbus fora | 503 (S1-FR-10) |
| Várias instâncias com o catálogo vencido ao mesmo tempo | No máximo uma busca na Nimbus por janela de 24 h, considerando todas as instâncias (S1-FR-02) |
| Nimbus devolve lista vazia `[]` | Tratada como falha (S1-FR-14): nada é gravado, aplica S1-FR-09/S1-FR-10 e loga WARN |
| Nimbus devolve item sem `region_id` ou com tipo inválido | O corpo é considerado inválido; aplica S1-FR-09/S1-FR-10, sem gravar nada parcialmente |
| Região muda de nome ou alias | Atualiza pelo `region_id` (S1-FR-06) |
| Região inativa vira ativa ou o contrário | Atualiza `inativa`; a validação reflete após a próxima busca |
| Região removida que reaparece | Volta a ser listada (S1-FR-07) |
| Redis fora do ar | Caffeine → SQL (S1-FR-12) |
| `POST /atualizar` com a Nimbus fora e o catálogo vencido | 200 com `desatualizado=true` (S1-FR-09) |

## 8. Tratamento de erros

| Condição | HTTP | Mensagem |
| --- | --- | --- |
| Nimbus falhou, sem catálogo gravado | 503 | "Fonte de dados climáticos indisponível" |
| Nimbus 401/403, sem catálogo gravado | 503 (+ log ERROR de credencial) | "Fonte de dados climáticos indisponível" |
| Nimbus falhou, com catálogo gravado | 200 com `desatualizado=true` | — |
| Validação: região inexistente ou removida | 404 | "Região não encontrada" |
| Validação: região inativa | 422 | "Região inativa" |
| Erro inesperado | 500 | Mensagem genérica, sem detalhes internos |

## 9. Requisitos não funcionais
- `GET /api/clima/regioes` com L1 ou L2 quente: **p95 < 200 ms**.
- Nenhum log, mensagem de erro ou resposta contém `api_key` ou `authorization`.
- Por chamada à Nimbus, logar endpoint, duração, status HTTP e se a resposta saiu desatualizada.
- O arquivo `Credenciais-Neovia.txt` sai da pasta do projeto antes do primeiro commit e entra no `.gitignore`.

## 10. Critérios de aceite

**S1-AC-01: Catálogo em cache**
Given o catálogo foi obtido da Nimbus há 2 h
When `GET /api/clima/regioes` é chamado
Then a resposta 200 traz as regiões com `desatualizado=false`, e nenhuma chamada é feita à Nimbus.

**S1-AC-02: Primeira busca**
Given o banco está vazio e a Nimbus devolve as regiões 1359 e 1706
When `GET /api/clima/regioes` é chamado
Then as duas regiões são gravadas, uma linha é inserida em `catalogo_regioes_busca`, e a resposta traz as duas.

**S1-AC-03: Catálogo vencido**
Given o catálogo foi obtido há 25 h
When `GET /api/clima/regioes` é chamado
Then a Nimbus é consultada uma vez e `obtidoEm` passa a ser o instante dessa busca.

**S1-AC-04: Refresh forçado dentro de 24 h**
Given o catálogo foi obtido há 3 h
When `POST /api/clima/regioes/atualizar` é chamado
Then a resposta 200 traz o catálogo guardado com o `obtidoEm` de 3 h atrás, e nenhuma chamada é feita à Nimbus.

**S1-AC-05: Inativa listada**
Given a Nimbus devolveu a região 1706 com `inactive=true`
When `GET /api/clima/regioes` é chamado
Then a região 1706 aparece com `inativa=true`.

**S1-AC-06: Região removida**
Given as regiões 1359 e 1706 estão gravadas e a nova busca da Nimbus devolve só a 1359
When o catálogo é atualizado
Then a 1706 fica no banco com `removida=1` e não aparece em `GET /api/clima/regioes`.

**S1-AC-07: Validação de região**
Given o catálogo tem a 1359 ativa, a 1706 inativa e a 1800 removida
When a validação é chamada com 1359, 1706, 1800 e 99999
Then os resultados são: sucesso, `INATIVA`, `NAO_ENCONTRADA` e `NAO_ENCONTRADA`.

**S1-AC-08: Nimbus fora, com catálogo**
Given o catálogo foi obtido há 30 h e a Nimbus responde 503
When `GET /api/clima/regioes` é chamado
Then a resposta 200 traz o catálogo guardado com `desatualizado=true` e o `obtidoEm` de 30 h atrás.

**S1-AC-09: Nimbus fora, sem catálogo**
Given o banco está vazio e a Nimbus dá timeout
When `GET /api/clima/regioes` é chamado
Then a resposta é 503 "Fonte de dados climáticos indisponível".

**S1-AC-10: Credencial recusada**
Given a Nimbus responde 401
When o catálogo precisa ser buscado
Then há um log ERROR de falha de credencial sem os valores de `api_key` e `authorization`, e a resposta segue S1-AC-08 ou S1-AC-09.

**S1-AC-11: Redis fora**
Given o Redis está inacessível e o catálogo está gravado no SQL há 2 h
When `GET /api/clima/regioes` é chamado
Then a resposta 200 vem normalmente e há um log WARN de falha no L2.

**S1-AC-12: Headers da Nimbus**
Given `NIMBUS_API_KEY` e `NIMBUS_AUTHORIZATION` configurados
When o clima-service chama a Nimbus
Then a requisição leva os headers `api_key` e `authorization` com os valores exatos, sem prefixo `Bearer`, e com `integration=true` na query.

**S1-AC-14: Catálogo vazio**
Given o catálogo gravado tem 2 regiões e a Nimbus devolve `[]`
When o catálogo precisa ser buscado
Then nada é gravado, nenhuma região é marcada como removida, a resposta 200 traz as 2 regiões com `desatualizado=true`, e há um log WARN.

**S1-AC-13: Concorrência entre instâncias**
Given duas instâncias com o catálogo vencido
When ambas recebem `GET /api/clima/regioes` ao mesmo tempo
Then só uma chamada é feita à Nimbus.

## 11. Estratégia de testes
- **Unidade:**
  - regra de vencimento (24 h);
  - reconciliação do catálogo: inserir, atualizar, marcar removida, reativar;
  - validação de região;
  - mapeamento de erros.
- **Cliente da Nimbus:** com servidor HTTP simulado (mesmo padrão de `OpenMeteoClient` + `MockRestServiceServer`, ver `pgo-api-service/src/main/java/br/com/neoviaengenharia/pgo/infra/clima/OpenMeteoClient.java:19-20`):
  - headers e query;
  - 200, 401, 403, 5xx, timeout e corpo inválido.
- **Integração:** SQL Server e Redis reais via Testcontainers (como no `hotel-service/pom.xml`); cobrir S1-AC-02, 03, 06, 08, 09, 11 e 13.
- **API:** testes de controller para os formatos das seções 5.1 e 5.2 e a tabela de erros.
- **Logs:** um teste confirma que os valores de credencial não aparecem nos logs (S1-AC-10).
