# Integração Neovia x Nimbus | API Cronos

## Sumário

- [1. Acesso](#1-acesso)
- [2. Descobrir regiões](#2-descobrir-regiões)
- [3. Consultar previsões](#3-consultar-previsões)
- [4. Consultar precipitação observada](#4-consultar-precipitação-observada)

## 1. Acesso

A Nimbus fornece o endereço da API e duas credenciais por canal seguro. Envie-as nos cabeçalhos `api_key` e `authorization` em todas as chamadas. O valor de `authorization` é enviado diretamente, sem `Bearer`. Os valores nos exemplos são marcadores: em uma aplicação, obtenha as credenciais de um cofre de segredos, sem gravá-las em código-fonte, URLs ou logs.

Nos exemplos, substitua os trechos entre `<` e `>` pelos valores recebidos. As datas são Unix epoch em **milissegundos**. As respostas de previsão são uma fotografia do momento do teste: para consultar a rodada atual, use uma janela futura atualizada. O exemplo de precipitação observada consulta uma janela histórica fixa.

Os exemplos Python usam a biblioteca `requests` (`python3 -m pip install requests`). Os exemplos cURL usam `jq` para formatar a resposta (`sudo apt install jq`); sem ele, remova o `| jq`.

## 2. Descobrir regiões

`GET /forecast-regions?integration=true` lista as regiões disponíveis para as credenciais utilizadas, com apenas os campos necessários à integração. Use o campo `region_id` nas consultas das seções 3 e 4. Consulte novamente este endpoint para descobrir regiões adicionadas depois.

| Campo | Descrição |
| --- | --- |
| `region_id` | Identificador da região, usado nas seções 3 e 4. |
| `name` | Nome da região. |
| `alias` | Código curto da região. |
| `inactive` | `true` se a região está desativada; `false` se está ativa. |
| `state_id` | Identificador do estado da região. |

**cURL**

```bash
curl -sS 'https://cronos-api.nimbusmeteorologia.com.br/forecast-regions?integration=true' \
  -H 'api_key: <chave-publica-do-servico>' \
  -H 'authorization: <chave-privada-do-servico>' | jq
```

**Python 3 (`requests`)**

```python
import requests

response = requests.get(
    "https://cronos-api.nimbusmeteorologia.com.br/forecast-regions",
    params={"integration": "true"},
    headers={"api_key": "<chave-publica-do-servico>", "authorization": "<chave-privada-do-servico>"},
    timeout=30,
)
response.raise_for_status()
regions = response.json()
print(regions)
```

**Exemplo de resposta**

```json
[
  {
    "region_id": 1359,
    "name": "01 SP-334 - Franca",
    "alias": "f_neo_fra",
    "inactive": false,
    "state_id": 25
  },
  {
    "region_id": 1706,
    "name": "01 Votorantim (Sorocaba)",
    "alias": "f_neo_vot",
    "inactive": false,
    "state_id": 25
  }
]
```

Guarde o `region_id` e o nome da região. Não deduza o identificador a partir do nome ou da posição na lista.

**Use cache.** A lista de regiões raramente muda. Guarde a resposta (em memória, arquivo ou banco) e consulte o endpoint de novo só quando souber de uma alteração, como uma região nova combinada com a Nimbus, ou no máximo uma vez ao dia. Não chame este endpoint a cada consulta de previsão ou de dado observado.

## 3. Consultar previsões

`POST /retrieve-forecasts` é uma operação **somente de leitura**. O método HTTP `POST` serve para enviar os filtros no corpo JSON; a chamada não cria, altera ou exclui previsões. Envie uma ou mais regiões, o intervalo, a frequência e `integration: true`.

| Campo no corpo | Obrigatório | Descrição |
| --- | --- | --- |
| `regions` | Sim | Array de objetos com `region_id` obtido em `GET /forecast-regions?integration=true`. |
| `datetime_start` e `datetime_end` | Sim | Início e fim da janela em epoch ms. |
| `frequency` | Sim | Intervalo em milissegundos; `3600000` corresponde a uma hora. |
| `integration` | Sim, para esta integração | Envie `true` no nível raiz do corpo para ativar o formato descrito abaixo. Sem o campo (ou com `false`), a resposta segue o formato anterior, que não é o documentado aqui. |

Não é necessário informar modelos nem qualquer outro campo sobre modelo: a Nimbus escolhe a origem de cada variável conforme a configuração do cliente e da região. Para várias regiões numa única chamada, liste mais itens em `regions`.

**cURL**

```bash
curl -sS -X POST 'https://cronos-api.nimbusmeteorologia.com.br/retrieve-forecasts' \
  -H 'api_key: <chave-publica-do-servico>' \
  -H 'authorization: <chave-privada-do-servico>' \
  -H 'Content-Type: application/json' \
  --data '{"regions":[{"region_id":1359}],"datetime_start":1790780400000,"datetime_end":1790784000000,"frequency":3600000,"integration":true}' | jq
```

**Python 3 (`requests`)**

```python
import requests

payload = {
    "regions": [{"region_id": 1359}],
    "datetime_start": 1790780400000,
    "datetime_end": 1790784000000,
    "frequency": 3600000,
    "integration": True,
}
response = requests.post(
    "https://cronos-api.nimbusmeteorologia.com.br/retrieve-forecasts",
    headers={"api_key": "<chave-publica-do-servico>", "authorization": "<chave-privada-do-servico>"},
    json=payload,
    timeout=30,
)
response.raise_for_status()
forecasts = response.json()
print(forecasts)
```

**Exemplo de resposta, HTTP 200**

```json
[
  {
    "region_id": 1359,
    "forecasts": [
      {
        "datetime": 1790780400000,
        "run_datetime": 1790726400000,
        "wind_direction": 297,
        "temperature": 35,
        "wind_gust": 18.14,
        "wind_speed": 10.8,
        "relative_humidity": 24.19,
        "precipitation": 0.2,
        "fog_fsi": 68.63,
        "fog_vis": 5
      },
      {
        "datetime": 1790784000000,
        "run_datetime": 1790726400000,
        "wind_direction": 297,
        "temperature": 34.66,
        "wind_gust": 19.4,
        "wind_speed": 15.48,
        "relative_humidity": 23.53,
        "precipitation": 0.19,
        "fog_fsi": 68.62,
        "fog_vis": 5
      }
    ]
  }
]
```

Regras do formato:

- A resposta traz um objeto por região pedida, sempre. Região sem dado disponível vem com `forecasts: []`, nunca é omitida.
- Cada ponto (`datetime`) traz só as variáveis configuradas para o cliente/região, uma única vez cada.
- Não há `model_id` nem `models` na resposta.
- `datetime` indica o horário previsto e `run_datetime` a rodada mais recente entre as fontes que contribuíram com dado naquele ponto.
- Variável configurada sem dado naquele ponto vem como `null`, não é omitida (`fog_fsi` e `fog_vis` no segundo ponto do exemplo).

**Atualização e cache.** A previsão é atualizada uma vez ao dia. Depois que a rodada do dia entra, qualquer requisição feita no mesmo dia retorna a mesma previsão, então não há ganho em consultar repetidamente. A rodada é sempre identificada às **00:00 UTC** (`run_datetime` termina em `T00:00:00Z`). Para saber se a rodada do dia já chegou, compare a data de `run_datetime` **em UTC** com a data atual **em UTC**. Não converta para o horário local: em Brasília (UTC-3), `00:00 UTC` corresponde a `21:00` do dia anterior, e a comparação local nunca indicaria o dia de hoje.

- Se a data de `run_datetime` (em UTC) é a do dia atual (em UTC), a previsão já foi atualizada. Guarde a resposta em cache e reutilize-a até o dia seguinte (em UTC).
- Se é de um dia anterior, a rodada de hoje ainda não chegou. Consulte de novo mais tarde, com intervalo razoável, até `run_datetime` passar a ser do dia atual.

Exemplo em Python:

```python
from datetime import datetime, timezone

run_date = datetime.fromtimestamp(run_datetime / 1000, tz=timezone.utc).date()
atualizada = run_date == datetime.now(timezone.utc).date()
```

Para consultar até 15 dias, amplie `datetime_end`. A resolução disponível é **horária até 120 horas da rodada e depois de 3 em 3 horas**. Mesmo com `frequency=3600000`, não haverá 360 pontos horários contínuos; processe os timestamps efetivamente retornados.

## 4. Consultar precipitação observada

`GET /manage/representative-stations/{region_id}/data-consistency` retorna o histórico de **precipitação observada** da estação representativa da região. Informe o mesmo `region_id` obtido na seção 2. Só são aceitas regiões atribuídas às credenciais utilizadas; um `region_id` de região não atribuída é recusado. Não é necessário descobrir ou selecionar uma estação. Se a representativa mudar no período, a estação de origem pode variar entre os pontos.

| Parâmetro | Local | Descrição |
| --- | --- | --- |
| `region_id` | Caminho | ID da região, obtido em `GET /forecast-regions?integration=true`. |
| `start_date` e `end_date` | Query | Início e fim em epoch ms. Envie ambos explicitamente. |
| `aggregation` | Query | Use `hourly` para a série horária. |
| `variable` | Query | Use `precipitation`; outras variáveis observadas não fazem parte desta integração neste momento. |

**cURL**

```bash
curl -sS 'https://cronos-api.nimbusmeteorologia.com.br/manage/representative-stations/1674/data-consistency?start_date=1790121600000&end_date=1790208000000&aggregation=hourly&variable=precipitation' \
  -H 'api_key: <chave-publica-do-servico>' \
  -H 'authorization: <chave-privada-do-servico>' | jq
```

**Python 3 (`requests`)**

```python
import requests

params = {
    "start_date": 1790121600000,
    "end_date": 1790208000000,
    "aggregation": "hourly",
    "variable": "precipitation",
}
response = requests.get(
    "https://cronos-api.nimbusmeteorologia.com.br/manage/representative-stations/1674/data-consistency",
    headers={"api_key": "<chave-publica-do-servico>", "authorization": "<chave-privada-do-servico>"},
    params=params,
    timeout=30,
)
response.raise_for_status()
observed = response.json()
print(observed)
```

**Resposta real**

```text
{
  "region_id": 1674,
  "variable": "precipitation",
  "period": {
    "start": 1790121600000,
    "end": 1790208000000
  },
  "data": [
    {
      "datetime": 1790121600000,
      "period": "Wed, 23 Sep 2026 00:00:00 GMT",
      "station_id": 3078,
      "station_name": "Prudentópolis",
      "station_code": "mg_h-823",
      "precipitation_sum": 0,
      "precipitation_max": 0,
      "precipitation_max_datetime": 1790121600000,
      "precipitation_first": null,
      "precipitation_first_datetime": null,
      "is_interpolated": false,
      "aggregation": "hourly"
    },
    ...
    {
      "datetime": 1790204400000,
      "period": "Wed, 23 Sep 2026 23:00:00 GMT",
      "station_id": 3078,
      "station_name": "Prudentópolis",
      "station_code": "mg_h-823",
      "precipitation_sum": 0,
      "precipitation_max": 0,
      "precipitation_max_datetime": 1790204400000,
      "precipitation_first": null,
      "precipitation_first_datetime": null,
      "is_interpolated": false,
      "aggregation": "hourly"
    }
  ],
  "metadata": {
    "total_points": 24,
    "stations_used": [
      3078
    ],
    "manual_overrides": 0,
    "configurations_count": 1
  }
}
```

Em cada item de `data`, `precipitation_sum` é o acumulado e `precipitation_max` é o maior valor **daquela hora** (`datetime`), não do período inteiro consultado.

**Atualização e cache.** O dado observado é atualizado de hora em hora. Consultar mais de uma vez por hora não traz dado novo. Faça a consulta no máximo uma vez por hora e guarde o resultado em cache; horas já fechadas não tendem a mudar.
