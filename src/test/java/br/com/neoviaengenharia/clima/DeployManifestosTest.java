package br.com.neoviaengenharia.clima;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class DeployManifestosTest {

    private static final Path K8S = Path.of("k8s");

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> documentos(Path arquivo) throws IOException {
        List<Map<String, Object>> docs = new ArrayList<>();
        try (InputStream in = Files.newInputStream(arquivo)) {
            for (Object o : new Yaml().loadAll(in)) {
                if (o != null) {
                    docs.add((Map<String, Object>) o);
                }
            }
        }
        return docs;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> env() throws IOException {
        Map<String, Object> deployment = documentos(K8S.resolve("04-deployment.yaml")).get(0);
        Map<String, Object> spec = (Map<String, Object>) deployment.get("spec");
        Map<String, Object> template = (Map<String, Object>) spec.get("template");
        Map<String, Object> podSpec = (Map<String, Object>) template.get("spec");
        List<Map<String, Object>> containers = (List<Map<String, Object>>) podSpec.get("containers");
        return (List<Map<String, Object>>) containers.get(0).get("env");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> variavel(String nome) throws IOException {
        return env().stream().filter(e -> nome.equals(e.get("name"))).findFirst()
                .orElseThrow(() -> new AssertionError("env ausente: " + nome));
    }

    @SuppressWarnings("unchecked")
    private static void assertSecretKeyRef(String nome, String secret) throws IOException {
        Map<String, Object> ref = (Map<String, Object>) ((Map<String, Object>) variavel(nome)
                .get("valueFrom")).get("secretKeyRef");
        assertThat(ref).as(nome).isNotNull();
        assertThat(ref.get("name")).as(nome).isEqualTo(secret);
        assertThat(ref.get("key")).as(nome).isEqualTo(nome);
    }

    @Test
    void deploymentLeNimbusDoSecret() throws IOException {
        assertSecretKeyRef("NIMBUS_API_KEY", "clima-service-nimbus");
        assertSecretKeyRef("NIMBUS_AUTHORIZATION", "clima-service-nimbus");
        for (String nome : List.of("SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME",
                "SPRING_DATASOURCE_PASSWORD", "SPRING_DATA_REDIS_HOST", "SPRING_DATA_REDIS_PORT",
                "SPRING_DATA_REDIS_DATABASE")) {
            assertSecretKeyRef(nome, "secret");
        }
    }

    @Test
    void nenhumValorLiteralDeCredencial() throws IOException {
        for (Map<String, Object> e : env()) {
            String nome = (String) e.get("name");
            if (nome.startsWith("NIMBUS_") || nome.equals("SPRING_DATASOURCE_PASSWORD")) {
                assertThat(e).as(nome).doesNotContainKey("value");
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void serviceEhClusterIP() throws IOException {
        Map<String, Object> service = documentos(K8S.resolve("05-service.yaml")).get(0);
        assertThat(service.get("kind")).isEqualTo("Service");
        assertThat(((Map<String, Object>) service.get("spec")).get("type")).isEqualTo("ClusterIP");
    }

    @Test
    void semIngress() throws IOException {
        try (Stream<Path> arquivos = Files.list(K8S)) {
            for (Path p : arquivos.filter(f -> f.toString().endsWith(".yaml")).toList()) {
                for (Map<String, Object> doc : documentos(p)) {
                    assertThat(doc.get("kind")).as(p.toString()).isNotEqualTo("Ingress");
                }
            }
        }
    }

    @Test
    void workflowsPublicamClimaService() throws IOException {
        for (String nome : List.of("dev.yml", "qa.yml", "main.yml")) {
            String conteudo = Files.readString(Path.of(".github/workflows", nome));
            assertThat(conteudo).as(nome).contains("/clima-service:").doesNotContain("service-api");
        }
    }
}
