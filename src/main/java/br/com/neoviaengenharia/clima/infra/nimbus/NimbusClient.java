package br.com.neoviaengenharia.clima.infra.nimbus;

import br.com.neoviaengenharia.clima.domain.observado.Estacao;
import br.com.neoviaengenharia.clima.domain.observado.HoraNimbus;
import br.com.neoviaengenharia.clima.domain.observado.LeituraHora;
import br.com.neoviaengenharia.clima.domain.regiao.RegiaoNimbus;
import br.com.neoviaengenharia.clima.infra.nimbus.NimbusIndisponivelException.Motivo;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Cliente da Nimbus (API Cronos). Toda falha vira {@link NimbusIndisponivelException}, sem causa nem credenciais. */
public class NimbusClient {

    private static final Logger log = LoggerFactory.getLogger(NimbusClient.class);
    private static final String CAMINHO = "/forecast-regions";

    private final RestClient restClient;

    public NimbusClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public List<RegiaoNimbus> listarRegioes() {
        long inicio = System.nanoTime();
        ResponseEntity<RegiaoDto[]> resposta;
        try {
            resposta = restClient.get().uri(CAMINHO + "?integration=true").retrieve().toEntity(RegiaoDto[].class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            registrar(status, inicio);
            if (status == 401 || status == 403) {
                log.error("Nimbus recusou as credenciais (status={})", status);
                throw new NimbusIndisponivelException(Motivo.CREDENCIAL_RECUSADA, status);
            }
            throw new NimbusIndisponivelException(Motivo.ERRO_HTTP, status);
        } catch (ResourceAccessException e) {
            throw semStatus(temTimeout(e) ? Motivo.TIMEOUT : Motivo.REDE, inicio);
        } catch (RestClientException e) {
            // timeout de leitura do corpo chega aqui embrulhado; o resto é corpo ilegível
            throw semStatus(temTimeout(e) ? Motivo.TIMEOUT : Motivo.CORPO_INVALIDO, inicio);
        }
        registrar(resposta.getStatusCode().value(), inicio);
        return converter(resposta.getBody());
    }

    /** Falha sem resposta HTTP: registra motivo e duração (nunca a mensagem nem a causa). */
    private static NimbusIndisponivelException semStatus(Motivo motivo, long inicio) {
        log.warn("nimbus GET {} status=n/a motivo={} duracaoMs={}", CAMINHO, motivo, (System.nanoTime() - inicio) / 1_000_000);
        return new NimbusIndisponivelException(motivo, null);
    }

    private static void registrar(int status, long inicio) {
        log.info("nimbus GET {} status={} duracaoMs={}", CAMINHO, status, (System.nanoTime() - inicio) / 1_000_000);
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

    // ---- Precipitação observada (S4): data-consistency ----

    private static final String CAMINHO_OBSERVADO = "/manage/representative-stations/{id}/data-consistency";

    /**
     * Série horária observada de {@code [inicio, fimExclusivo)}.
     *
     * @throws NimbusRegiaoRecusadaException se a Nimbus responder 404 (região não atribuída às credenciais)
     * @throws NimbusIndisponivelException nas demais falhas, inclusive {@code region_id} divergente (S4-FR-17)
     */
    public List<HoraNimbus> buscarObservado(long regionId, Instant inicio, Instant fimExclusivo) {
        long t0 = System.nanoTime();
        String rotulo = "regiao=" + regionId + " inicio=" + inicio.toEpochMilli() + " fim=" + fimExclusivo.toEpochMilli();
        ResponseEntity<ObservadoDto> resposta;
        try {
            resposta = restClient.get()
                    .uri(u -> u.path(CAMINHO_OBSERVADO)
                            .queryParam("start_date", inicio.toEpochMilli())
                            .queryParam("end_date", fimExclusivo.toEpochMilli())
                            .queryParam("aggregation", "hourly")
                            .queryParam("variable", "precipitation")
                            .build(regionId))
                    .retrieve()
                    .toEntity(ObservadoDto.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            registrarObservado(rotulo, String.valueOf(status), t0);
            if (status == 404) {
                throw new NimbusRegiaoRecusadaException(regionId);
            }
            if (status == 401 || status == 403) {
                log.error("Nimbus recusou as credenciais (status={})", status);
                throw new NimbusIndisponivelException(Motivo.CREDENCIAL_RECUSADA, status);
            }
            throw new NimbusIndisponivelException(Motivo.ERRO_HTTP, status);
        } catch (ResourceAccessException e) {
            throw observadoSemStatus(rotulo, temTimeout(e) ? Motivo.TIMEOUT : Motivo.REDE, t0);
        } catch (RestClientException e) {
            throw observadoSemStatus(rotulo, temTimeout(e) ? Motivo.TIMEOUT : Motivo.CORPO_INVALIDO, t0);
        }
        registrarObservado(rotulo, String.valueOf(resposta.getStatusCode().value()), t0);
        return converterObservado(regionId, resposta.getBody());
    }

    private static void registrarObservado(String rotulo, String status, long t0) {
        log.info("nimbus GET {} {} status={} duracaoMs={}", CAMINHO_OBSERVADO, rotulo, status,
                (System.nanoTime() - t0) / 1_000_000);
    }

    private static NimbusIndisponivelException observadoSemStatus(String rotulo, Motivo motivo, long t0) {
        log.warn("nimbus GET {} {} status=n/a motivo={} duracaoMs={}", CAMINHO_OBSERVADO, rotulo, motivo,
                (System.nanoTime() - t0) / 1_000_000);
        return new NimbusIndisponivelException(motivo, null);
    }

    private static List<HoraNimbus> converterObservado(long regionId, ObservadoDto corpo) {
        if (corpo == null || corpo.data == null || corpo.regionId == null || corpo.regionId != regionId) {
            throw new NimbusIndisponivelException(Motivo.CORPO_INVALIDO, null);
        }
        List<HoraNimbus> horas = new ArrayList<>();
        for (HoraObservadaDto d : corpo.data) {
            if (d == null || d.datetime == null || d.stationId == null
                    || (d.regionId != null && d.regionId != regionId)) {
                throw new NimbusIndisponivelException(Motivo.CORPO_INVALIDO, null);
            }
            horas.add(new HoraNimbus(Instant.ofEpochMilli(d.datetime), new LeituraHora(
                    new Estacao(d.stationId, d.stationName, d.stationCode),
                    d.precipitationSum, d.precipitationMax, instante(d.precipitationMaxDatetime),
                    d.precipitationFirst, instante(d.precipitationFirstDatetime), d.isInterpolated)));
        }
        return horas;
    }

    private static Instant instante(Long epochMs) {
        return epochMs == null ? null : Instant.ofEpochMilli(epochMs);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ObservadoDto {
        @JsonProperty("region_id")
        public Long regionId;
        public List<HoraObservadaDto> data;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class HoraObservadaDto {
        @JsonProperty("region_id")
        public Long regionId;
        public Long datetime;
        @JsonProperty("station_id")
        public Long stationId;
        @JsonProperty("station_name")
        public String stationName;
        @JsonProperty("station_code")
        public String stationCode;
        @JsonProperty("precipitation_sum")
        public BigDecimal precipitationSum;
        @JsonProperty("precipitation_max")
        public BigDecimal precipitationMax;
        @JsonProperty("precipitation_max_datetime")
        public Long precipitationMaxDatetime;
        @JsonProperty("precipitation_first")
        public BigDecimal precipitationFirst;
        @JsonProperty("precipitation_first_datetime")
        public Long precipitationFirstDatetime;
        @JsonProperty("is_interpolated")
        public Boolean isInterpolated;
    }
}
