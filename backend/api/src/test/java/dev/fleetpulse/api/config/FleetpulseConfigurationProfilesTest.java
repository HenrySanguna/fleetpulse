package dev.fleetpulse.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class FleetpulseConfigurationProfilesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(TestConfig.class);

    @EnableConfigurationProperties({ FleetpulseDataSourceProperties.class, FleetpulseMqttProperties.class })
    static class TestConfig {
    }

    @Test
    void prodProfileFailsFastWithoutRequiredEnvironmentVariables() {
        contextRunner
            .withPropertyValues("spring.profiles.active=prod")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
            });
    }

    @Test
    void localProfileStartsWithBundledLocalDevDefaults() {
        contextRunner
            .withPropertyValues("spring.profiles.active=local")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(FleetpulseDataSourceProperties.class).url())
                    .isEqualTo("jdbc:postgresql://localhost:5432/fleetpulse");
                assertThat(context.getBean(FleetpulseMqttProperties.class).brokerUrl())
                    .isEqualTo("tcp://localhost:1883");
            });
    }
}
