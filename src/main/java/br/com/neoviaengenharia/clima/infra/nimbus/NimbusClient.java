package br.com.neoviaengenharia.clima.infra.nimbus;

import br.com.neoviaengenharia.clima.domain.previsao.PontoPrevisao;
import br.com.neoviaengenharia.clima.domain.regiao.RegiaoNimbus;
import br.com.neoviaengenharia.clima.infra.nimbus.NimbusIndisponivelException.Motivo;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Cliente da Nimbus (API Cronos). Toda falha vira {@link NimbusIndisponivelException}, sem causa nem credenciais. */
public class NimbusClient {

    private static final Logger log = LoggerFactory.getLogger(NimbusClient.class);
    private static final String CAMINHO_REGIOES = "/forecast-regions";
    private static final String CAMINHO_PREVISOES = "/retrieve-forecasts";
    private static final long FREQUENCIA_HORARIA_MS = 3_600_000L;

    private final RestClient restClient;
    private final ObjectMapper mapper;

    public NimbusClient(RestClient restClient, ObjectMapper mapper) {
        this.restClient = restClient;
        this.mapper = mapper;
    }

    public List<RegiaoNimbus> listarRegioes() {
        ResponseEntity<RegiaoDto[]> resposta = chamar("GET", CAMINHO_REGIOES, "",
                () -> restClient.get().uri(CAMINHO_REGIOES + "?integration=true").retrieve().toEntity(RegiaoDto[].class));
        return converter(resposta.getBody());
    }

    /**
     * Rodada inteira das regiões, numa única chamada. O mapa só tem as regiões pedidas que vieram na resposta:
     * a ausência de uma delas significa que a Nimbus a omitiu. {@code forecasts: []} vira lista vazia.
     */
    public Map<Long, List<PontoPrevisao>> buscarPrevisoes(List<Long> regionIds, Instant inicio, Instant fim) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("regions", regionIds.stream().map(id -> Map.of("region_id", id)).toList());
        corpo.put("datetime_start", inicio.toEpochMilli());
        corpo.put("datetime_end", fim.toEpochMilli());
        corpo.put("frequency", FREQUENCIA_HORARIA_MS);
        corpo.put("integration", true);
        ResponseEntity<JsonNode> resposta = chamar("POST", CAMINHO_PREVISOES, " regioes=" + regionIds,
                () -> restClient.post().uri(CAMINHO_PREVISOES).contentType(MediaType.APPLICATION_JSON)
                        .body(corpo).retrieve().toEntity(JsonNode.class));
        return converterPrevisoes(resposta.getBody(), new HashSet<>(regionIds));
    }

    /** Executa a chamada, registra status e duração e traduz as falhas (nunca a mensagem nem a causa). */
    private static <T> ResponseEntity<T> chamar(
            String metodo, String caminho, String detalhe, Supplier<ResponseEntity<T>> chamada) {
        long inicio = System.nanoTime();
        ResponseEntity<T> resposta;
        try {
            resposta = chamada.get();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            registrar(metodo, caminho, detalhe, status, inicio);
            if (status == 401 || status == 403) {
                log.error("Nimbus recusou as credenciais (status={})", status);
                throw new NimbusIndisponivelException(Motivo.CREDENCIAL_RECUSADA, status);
            }
            throw new NimbusIndisponivelException(Motivo.ERRO_HTTP, status);
        } catch (ResourceAccessException e) {
            throw semStatus(metodo, caminho, detalhe, temTimeout(e) ? Motivo.TIMEOUT : Motivo.REDE, inicio);
        } catch (RestClientException e) {
            // timeout de leitura do corpo chega aqui embrulhado; o resto é corpo ilegível
            throw semStatus(metodo, caminho, detalhe, temTimeout(e) ? Motivo.TIMEOUT : Motivo.CORPO_INVALIDO, inicio);
        }
        registrar(metodo, caminho, detalhe, resposta.getStatusCode().value(), inicio);
        return resposta;
    }

    /** Falha sem resposta HTTP: registra motivo e duração (nunca a mensagem nem a causa). */
    private static NimbusIndisponivelException semStatus(
            String metodo, String caminho, String detalhe, Motivo motivo, long inicio) {
        log.warn("nimbus {} {}{} status=n/a motivo={} duracaoMs={}", metodo, caminho, detalhe, motivo,
                (System.nanoTime() - inicio) / 1_000_000);
        return new NimbusIndisponivelException(motivo, null);
    }

    private static void registrar(String metodo, String caminho, String detalhe, int status, long inicio) {
        log.info("nimbus {} {}{} status={} duracaoMs={}", metodo, caminho, detalhe, status,
                (System.nanoTime() - inicio) / 1_000_000);
    }

    private static boolean temTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static List<RegiaoNimbus> converter(RegiaoDto[] corpo) {
        if (corpo == null || corpo.length == 0) {
            throw new NimbusIndisponivelException(Motivo.CORPO_INVALIDO, null);
        }
        List<RegiaoNimbus> regioes = new ArrayList<>();
        for (RegiaoDto d : corpo) {
            if (d == null || d.regionId == null || d.name == null || d.alias == null
                    || d.inactive == null || d.stateId == null) {
                throw new NimbusIndisponivelException(Motivo.CORPO_INVALIDO, null);
            }
            regioes.add(new RegiaoNimbus(d.regionId, d.name, d.alias, d.inactive, d.stateId));
        }
        return regioes;
    }

    /** Valida o corpo inteiro antes de devolver: qualquer item malformado invalida a resposta toda. */
    private Map<Long, List<PontoPrevisao>> converterPrevisoes(JsonNode corpo, Set<Long> pedidas) {
        if (corpo == null || !corpo.isArray()) {
            throw corpoInvalido();
        }
        Map<Long, List<PontoPrevisao>> porRegiao = new LinkedHashMap<>();
        for (JsonNode item : corpo) {
            JsonNode regionId = item.get("region_id");
            JsonNode forecasts = item.get("forecasts");
            if (regionId == null || !regionId.canConvertToExactIntegral() || forecasts == null || !forecasts.isArray()) {
                throw corpoInvalido();
            }
            List<PontoPrevisao> pontos = new ArrayList<>();
            for (JsonNode ponto : forecasts) {
                pontos.add(ponto(ponto));
            }
            if (pedidas.contains(regionId.asLong())) {
                porRegiao.put(regionId.asLong(), pontos);
            }
        }
        return porRegiao;
    }

    private PontoPrevisao ponto(JsonNode ponto) {
        JsonNode dataHora = ponto.get("datetime");
        JsonNode rodada = ponto.get("run_datetime");
        if (!ponto.isObject() || !epochMs(dataHora) || !epochMs(rodada)) {
            throw corpoInvalido();
        }
        Map<String, Object> variaveis = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = ponto.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> campo = it.next();
            if (!campo.getKey().equals("datetime") && !campo.getKey().equals("run_datetime")) {
                variaveis.put(campo.getKey(), campo.getValue().isNull() ? null
                        : mapper.convertValue(campo.getValue(), Object.class));
            }
        }
        return new PontoPrevisao(Instant.ofEpochMilli(dataHora.asLong()), Instant.ofEpochMilli(rodada.asLong()),
                variaveis);
    }

    private static boolean epochMs(JsonNode valor) {
        return valor != null && valor.isIntegralNumber() && valor.canConvertToLong();
    }

    private static NimbusIndisponivelException corpoInvalido() {
        log.warn("nimbus POST {} corpo inválido", CAMINHO_PREVISOES);
        return new NimbusIndisponivelException(Motivo.CORPO_INVALIDO, null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RegiaoDto {
        @JsonProperty("region_id")
        public Long regionId;
        public String name;
        public String alias;
        public Boolean inactive;
        @JsonProperty("state_id")
        public Long stateId;
    }
}
