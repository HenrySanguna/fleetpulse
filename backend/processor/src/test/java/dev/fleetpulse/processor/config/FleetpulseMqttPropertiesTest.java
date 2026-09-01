package dev.fleetpulse.processor.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class FleetpulseMqttPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfig.class);

    @EnableConfigurationProperties(FleetpulseMqttProperties.class)
    static class TestConfig {
    }

    @Test
    void failsFastWhenBrokerUrlIsMissing() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(BindValidationException.class)
                .rootCause()
                .hasMessageContaining("fleetpulse.mqtt")
                .hasMessageContaining("brokerUrl")
                .hasMessageContaining("must not be blank");
        });
    }

    @Test
    void startsSuccessfullyWhenBrokerUrlIsPresent() {
        contextRunner
            .withPropertyValues("fleetpulse.mqtt.broker-url=tcp://localhost:1883")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(FleetpulseMqttProperties.class).brokerUrl())
                    .isEqualTo("tcp://localhost:1883");
            });
    }
}
