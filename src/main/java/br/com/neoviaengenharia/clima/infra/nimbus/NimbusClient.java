package br.com.neoviaengenharia.clima.infra.nimbus;

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

import java.net.SocketTimeoutException;
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
}
