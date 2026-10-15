package br.com.neoviaengenharia.clima.app.observado;

import br.com.neoviaengenharia.clima.domain.observado.EstadoHora;
import br.com.neoviaengenharia.clima.domain.observado.Estacao;
import br.com.neoviaengenharia.clima.domain.observado.HoraNimbus;
import br.com.neoviaengenharia.clima.domain.observado.LeituraHora;
import br.com.neoviaengenharia.clima.domain.regiao.CatalogoSnapshot;
import br.com.neoviaengenharia.clima.domain.regiao.Regiao;
import br.com.neoviaengenharia.clima.domain.regiao.RegiaoNimbus;
import br.com.neoviaengenharia.clima.infra.repo.BuscaCatalogo;
import br.com.neoviaengenharia.clima.infra.repo.BuscaCatalogoRepository;
import br.com.neoviaengenharia.clima.infra.repo.HoraObservada;
import br.com.neoviaengenharia.clima.infra.repo.HoraObservadaRepository;
import br.com.neoviaengenharia.clima.infra.repo.RegiaoRepository;
import br.com.neoviaengenharia.clima.infra.repo.TentativaObservado;
import br.com.neoviaengenharia.clima.infra.repo.TentativaObservadoRepository;
import com.github.benmanes.caffeine.cache.Cache;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Estado (SQL, cache e Nimbus simulado) para os testes do observado. Limpe antes e depois de cada teste. */
public class ObservadoFixture {

    public static final long ATIVA = 1674;
    public static final long INATIVA = 1706;
    public static final long REMOVIDA = 1800;

    public final HoraObservadaRepository horas;
    public final TentativaObservadoRepository tentativas;
    private final RegiaoRepository regioes;
    private final BuscaCatalogoRepository buscas;
    private final TransactionTemplate tx;
    private final Cache<String, EstadoHora> observadoL1;
    private final Cache<String, CatalogoSnapshot> catalogoL1;
    private final StringRedisTemplate redis;

    @SuppressWarnings("unchecked")
    public ObservadoFixture(ApplicationContext ctx) {
        this.horas = ctx.getBean(HoraObservadaRepository.class);
        this.tentativas = ctx.getBean(TentativaObservadoRepository.class);
        this.regioes = ctx.getBean(RegiaoRepository.class);
        this.buscas = ctx.getBean(BuscaCatalogoRepository.class);
        this.tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
        this.observadoL1 = ctx.getBean("observadoL1", Cache.class);
        this.catalogoL1 = ctx.getBean("catalogoL1", Cache.class);
        this.redis = ctx.getBean(StringRedisTemplate.class);
    }

    /** Esvazia as tabelas do observado e do catálogo, os caches e a fila do Nimbus simulado. */
    public void limpar(MockWebServer nimbus) {
        nimbus.setDispatcher(new QueueDispatcher());
        tx.executeWithoutResult(s -> {
            horas.deleteAll();
            tentativas.deleteAll();
            regioes.deleteAll();
            buscas.deleteAll();
        });
        limparCaches();
    }

    public void limparCaches() {
        observadoL1.invalidateAll();
        catalogoL1.invalidateAll();
        try {
            Set<String> chaves = redis.keys("clima:*");
            if (chaves != null && !chaves.isEmpty()) {
                redis.delete(chaves);
            }
        } catch (Exception e) {
            // cenário "Redis fora": não há o que limpar
        }
    }

    /** Catálogo vigente em {@code agora}: 1674 ativa, 1706 inativa e 1800 removida. */
    public void catalogo(Instant agora) {
        tx.executeWithoutResult(s -> {
            regioes.save(Regiao.nova(new RegiaoNimbus(ATIVA, "Região 1674", "r1674", false, 25), agora));
            regioes.save(Regiao.nova(new RegiaoNimbus(INATIVA, "Região 1706", "r1706", true, 25), agora));
            Regiao removida = Regiao.nova(new RegiaoNimbus(REMOVIDA, "Região 1800", "r1800", false, 25), agora);
            removida.marcarRemovida(agora);
            regioes.save(removida);
            buscas.save(new BuscaCatalogo(agora, 2));
        });
    }

    /** Grava horas com dado (estação 3078, acumulado {@code soma}) e a tentativa em {@code tentativa}. */
    public void gravar(long regionId, List<Instant> dataHoras, String soma, Instant tentativa) {
        tx.executeWithoutResult(s -> {
            for (Instant h : dataHoras) {
                horas.save(HoraObservada.de(regionId, new EstadoHora(h, leitura(3078, soma), tentativa, false, tentativa)));
                tentativas.save(new TentativaObservado(regionId, h, tentativa));
            }
        });
    }

    public void gravarSemDado(long regionId, Instant h, Instant tentativa) {
        tx.executeWithoutResult(s -> {
            horas.save(HoraObservada.de(regionId, new EstadoHora(h, null, null, true, tentativa)));
            tentativas.save(new TentativaObservado(regionId, h, tentativa));
        });
    }

    public void gravarTentativa(long regionId, Instant h, Instant tentativa) {
        tx.executeWithoutResult(s -> tentativas.save(new TentativaObservado(regionId, h, tentativa)));
    }

    public void apagarHoras() {
        tx.executeWithoutResult(s -> {
            horas.deleteAll();
            tentativas.deleteAll();
        });
    }

    public List<HoraObservada> horasGravadas(long regionId) {
        return horas.findAll().stream().filter(h -> h.getRegionId() == regionId).toList();
    }

    public Instant tentativa(long regionId, Instant h) {
        return tentativas.findAll().stream()
                .filter(t -> t.getRegionId() == regionId && t.getDataHora().equals(h))
                .map(TentativaObservado::getUltimaTentativaEm).findFirst().orElse(null);
    }

    public static List<Instant> horas(Instant de, int quantidade) {
        List<Instant> l = new ArrayList<>();
        for (int i = 0; i < quantidade; i++) {
            l.add(de.plus(Duration.ofHours(i)));
        }
        return l;
    }

    public static LeituraHora leitura(long estacao, String soma) {
        return new LeituraHora(new Estacao(estacao, "Estação " + estacao, "c-" + estacao), new BigDecimal(soma),
                new BigDecimal(soma), null, null, null, false);
    }

    /** Resposta 200 do data-consistency com as horas dadas, cada uma com a estação de {@code estacao}. */
    public static MockResponse observado(long regionId, List<Instant> dataHoras, Function<Instant, Long> estacao,
            String soma) {
        String data = dataHoras.stream().map(h -> {
            long id = estacao.apply(h);
            return ("{\"datetime\":%d,\"period\":\"x\",\"station_id\":%d,\"station_name\":\"Estação %d\","
                    + "\"station_code\":\"c-%d\",\"precipitation_sum\":%s,\"precipitation_max\":%s,"
                    + "\"precipitation_max_datetime\":null,\"precipitation_first\":null,"
                    + "\"precipitation_first_datetime\":null,\"is_interpolated\":false,\"aggregation\":\"hourly\"}")
                    .formatted(h.toEpochMilli(), id, id, id, soma, soma);
        }).collect(Collectors.joining(",", "[", "]"));
        return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("{\"region_id\":%d,\"variable\":\"precipitation\",\"data\":%s,\"metadata\":{}}"
                        .formatted(regionId, data));
    }

    public static MockResponse observado(long regionId, List<Instant> dataHoras, String soma) {
        return observado(regionId, dataHoras, h -> 3078L, soma);
    }

    public static HoraNimbus hora(Instant h, String soma) {
        return new HoraNimbus(h, leitura(3078, soma));
    }
}
