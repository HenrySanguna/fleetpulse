package dev.fleetpulse.processor.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class FleetpulseDataSourcePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfig.class);

    @EnableConfigurationProperties(FleetpulseDataSourceProperties.class)
    static class TestConfig {
    }

    @Test
    void failsFastWhenDatasourceUrlIsMissing() {
        contextRunner
            .withPropertyValues(
                "spring.datasource.username=fleetpulse",
                "spring.datasource.password=fleetpulse"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(BindValidationException.class)
                    .rootCause()
                    .hasMessageContaining("spring.datasource")
                    .hasMessageContaining("url")
                    .hasMessageContaining("must not be blank");
            });
    }

    @Test
    void startsSuccessfullyWhenAllRequiredPropertiesArePresent() {
        contextRunner
            .withPropertyValues(
                "spring.datasource.url=jdbc:postgresql://localhost:5432/fleetpulse",
                "spring.datasource.username=fleetpulse",
                "spring.datasource.password=fleetpulse"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(FleetpulseDataSourceProperties.class).url())
                    .isEqualTo("jdbc:postgresql://localhost:5432/fleetpulse");
            });
    }
}
