package br.com.neoviaengenharia.clima.infra.repo;

import br.com.neoviaengenharia.clima.domain.previsao.PontoPrevisao;
import br.com.neoviaengenharia.clima.domain.previsao.RodadaPrevisao;
import br.com.neoviaengenharia.clima.domain.previsao.RodadaResumo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rodadas e pontos de previsão via JDBC (insert em lote dos pontos). Instantes vão e voltam como
 * {@link LocalDateTime} em UTC, igual ao Hibernate com {@code jdbc.time_zone=UTC}.
 */
@Repository
public class RodadaPrevisaoRepositorio {

    private static final TypeReference<LinkedHashMap<String, Object>> MAPA = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public RodadaPrevisaoRepositorio(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** A rodada de maior {@code run_datetime} da região, com os pontos. */
    @Transactional(readOnly = true)
    public Optional<RodadaPrevisao> vigente(long regionId) {
        return cabecalho("SELECT TOP 1 id, run_datetime, obtido_em FROM clima.rodada_previsao "
                + "WHERE region_id = ? ORDER BY run_datetime DESC", regionId)
                .map(c -> c.comPontos(regionId, pontos(c.id())));
    }

    /** A rodada com {@code run_datetime} exatamente igual a {@code rodada}. */
    @Transactional(readOnly = true)
    public Optional<RodadaPrevisao> buscar(long regionId, Instant rodada) {
        return cabecalho("SELECT id, run_datetime, obtido_em FROM clima.rodada_previsao "
                + "WHERE region_id = ? AND run_datetime = ?", regionId, utc(rodada))
                .map(c -> c.comPontos(regionId, pontos(c.id())));
    }

    /** Rodadas da região no período, da mais recente para a mais antiga, sem ler os pontos. */
    @Transactional(readOnly = true)
    public List<RodadaResumo> listar(long regionId, Instant de, Instant ate) {
        return jdbc.query("SELECT run_datetime, obtido_em FROM clima.rodada_previsao "
                        + "WHERE region_id = ? AND run_datetime >= ? AND run_datetime <= ? ORDER BY run_datetime DESC",
                (rs, n) -> new RodadaResumo(instante(rs, "run_datetime"), instante(rs, "obtido_em")),
                regionId, utc(de), utc(ate));
    }

    /** Grava a rodada como nova ou, se já existir, sobrescreve {@code obtido_em} e todos os pontos. */
    @Transactional
    public void gravar(RodadaPrevisao rodada) {
        Optional<Long> existente = idDe(rodada.regionId(), rodada.rodada());
        long id;
        if (existente.isPresent()) {
            id = existente.get();
            jdbc.update("UPDATE clima.rodada_previsao SET obtido_em = ? WHERE id = ?", utc(rodada.obtidoEm()), id);
            jdbc.update("DELETE FROM clima.ponto_previsao WHERE rodada_id = ?", id);
        } else {
            jdbc.update("INSERT INTO clima.rodada_previsao (region_id, run_datetime, obtido_em) VALUES (?, ?, ?)",
                    rodada.regionId(), utc(rodada.rodada()), utc(rodada.obtidoEm()));
            id = idDe(rodada.regionId(), rodada.rodada()).orElseThrow();
        }
        long rodadaId = id;
        jdbc.batchUpdate(
                "INSERT INTO clima.ponto_previsao (rodada_id, data_hora, run_datetime, variaveis) VALUES (?, ?, ?, ?)",
                rodada.pontos(), 500, (ps, p) -> {
                    ps.setLong(1, rodadaId);
                    ps.setObject(2, utc(p.dataHora()));
                    ps.setObject(3, utc(p.rodadaPonto()));
                    ps.setString(4, json(p.variaveis()));
                });
    }

    private Optional<Long> idDe(long regionId, Instant rodada) {
        return jdbc.queryForList("SELECT id FROM clima.rodada_previsao WHERE region_id = ? AND run_datetime = ?",
                Long.class, regionId, utc(rodada)).stream().findFirst();
    }

    private Optional<Cabecalho> cabecalho(String sql, Object... args) {
        return jdbc.query(sql, (rs, n) -> new Cabecalho(rs.getLong("id"), instante(rs, "run_datetime"),
                instante(rs, "obtido_em")), args).stream().findFirst();
    }

    private List<PontoPrevisao> pontos(long rodadaId) {
        return jdbc.query("SELECT data_hora, run_datetime, variaveis FROM clima.ponto_previsao "
                        + "WHERE rodada_id = ? ORDER BY data_hora",
                (rs, n) -> new PontoPrevisao(instante(rs, "data_hora"), instante(rs, "run_datetime"),
                        mapa(rs.getString("variaveis"))),
                rodadaId);
    }

    private record Cabecalho(long id, Instant rodada, Instant obtidoEm) {
        RodadaPrevisao comPontos(long regionId, List<PontoPrevisao> pontos) {
            return new RodadaPrevisao(regionId, rodada, obtidoEm, pontos);
        }
    }

    private static LocalDateTime utc(Instant instante) {
        return LocalDateTime.ofInstant(instante, ZoneOffset.UTC);
    }

    private static Instant instante(ResultSet rs, String coluna) throws SQLException {
        return rs.getObject(coluna, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    private String json(Map<String, Object> variaveis) {
        try {
            return mapper.writeValueAsString(variaveis);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Variáveis de previsão não serializáveis", e);
        }
    }

    private Map<String, Object> mapa(String json) {
        try {
            return mapper.readValue(json, MAPA);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Variáveis de previsão gravadas ilegíveis", e);
        }
    }
}
