package dev.minibank;

import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Postgres with the same two identities as every real environment: Flyway migrates as the
 * schema owner, and the app connects as minibank_app (member of ledger_app, see V003).
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
                .withDatabaseName("minibank")
                .withUsername("minibank")
                .withPassword("minibank")
                .withInitScript("db/local/roles.sql");
    }

    // Connection details, not DynamicPropertyRegistrar: in a web test context Tomcat's filters
    // pull in the DataSource during onRefresh, before a registrar has run, so the pool was built
    // from application.yaml's default (localhost:5433). A bean dependency has no such ordering gap.
    @Bean
    JdbcConnectionDetails appConnection(PostgreSQLContainer postgres) {
        return new JdbcConnectionDetails() {
            @Override
            public String getJdbcUrl() {
                return postgres.getJdbcUrl();
            }

            @Override
            public String getUsername() {
                return "minibank_app";
            }

            @Override
            public String getPassword() {
                return "minibank_app";
            }
        };
    }

    @Bean
    FlywayConnectionDetails migrationConnection(PostgreSQLContainer postgres) {
        return new FlywayConnectionDetails() {
            @Override
            public String getJdbcUrl() {
                return postgres.getJdbcUrl();
            }

            @Override
            public String getUsername() {
                return postgres.getUsername();
            }

            @Override
            public String getPassword() {
                return postgres.getPassword();
            }
        };
    }
}
