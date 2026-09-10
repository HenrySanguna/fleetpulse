package dev.fleetpulse.api.session;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

// Task 1.3: dispatcher sessions must survive an api restart and be
// revocable server-side (design.md - "sesión de servidor... porque un
// despachador dado de baja debe perder el acceso en la petición siguiente").
// Proven against a real Postgres via Testcontainers, not an in-memory store.
@Testcontainers
@SpringBootTest
class DispatcherSessionPersistenceTest {

    private static final Path POSTGIS_PARTMAN_DOCKERFILE = Path
        .of(System.getProperty("user.dir"), "..", "..", "docker", "postgis-partman", "Dockerfile")
        .normalize();

    @Container
    static final PostgreSQLContainer postgis = new PostgreSQLContainer(
        DockerImageName
            .parse(
                new ImageFromDockerfile("fleetpulse/postgis-partman:test", false)
                    .withDockerfile(POSTGIS_PARTMAN_DOCKERFILE)
                    .get()
            )
            .asCompatibleSubstituteFor("postgres")
    );

    @DynamicPropertySource
    static void backingServices(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgis::getJdbcUrl);
        registry.add("spring.datasource.username", postgis::getUsername);
        registry.add("spring.datasource.password", postgis::getPassword);
        // No broker involved in this test; a placeholder satisfies
        // FleetpulseMqttProperties' @NotBlank binding so the full context
        // starts without needing a real Mosquitto Testcontainer here.
        registry.add("fleetpulse.mqtt.broker-url", () -> "tcp://localhost:1");
    }

    @Autowired
    private JdbcIndexedSessionRepository sessions;

    @Test
    void persistsDispatcherSessionAttributeInSpringSessionTable() throws Exception {
        // var, not the concrete JdbcSession type: JdbcIndexedSessionRepository's
        // nested JdbcSession class is package-private, so it cannot be named
        // outside org.springframework.session.jdbc. save()/findById() need the
        // var-inferred concrete type; every other call goes through the public
        // Session interface view, since javac resolves member access against a
        // var's concrete (here inaccessible) type otherwise.
        var session = sessions.createSession();
        Session sessionView = session;
        sessionView.setAttribute("dispatcherEmail", "ana@acme.test");
        sessions.save(session);

        var reloaded = sessions.findById(sessionView.getId());
        Session reloadedView = reloaded;

        assertThat(reloadedView).isNotNull();
        assertThat(reloadedView.<String>getAttribute("dispatcherEmail")).isEqualTo("ana@acme.test");
        assertThat(countSessionRows(sessionView.getId())).isEqualTo(1);
    }

    private long countSessionRows(String sessionId) throws Exception {
        try (
            Connection connection = DriverManager
                .getConnection(postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword());
            PreparedStatement statement = connection
                .prepareStatement("SELECT COUNT(*) FROM SPRING_SESSION WHERE SESSION_ID = ?")
        ) {
            statement.setString(1, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }
}
