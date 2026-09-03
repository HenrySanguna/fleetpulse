package dev.fleetpulse.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class FleetpulseHeartbeatPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfig.class);

    @EnableConfigurationProperties(FleetpulseHeartbeatProperties.class)
    static class TestConfig {
    }

    @Test
    void defaultsToTheSharedTopicAndANinetySecondStalenessThreshold() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            FleetpulseHeartbeatProperties properties = context.getBean(FleetpulseHeartbeatProperties.class);
            assertThat(properties.topic()).isEqualTo("fleetpulse/processor/heartbeat");
            assertThat(properties.stalenessThreshold()).isEqualTo(Duration.ofSeconds(90));
        });
    }

    @Test
    void honorsExplicitlyConfiguredTopicAndStalenessThreshold() {
        contextRunner
            .withPropertyValues(
                "fleetpulse.mqtt.heartbeat.topic=fleetpulse/custom/heartbeat",
                "fleetpulse.mqtt.heartbeat.staleness-threshold=45s"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                FleetpulseHeartbeatProperties properties = context.getBean(FleetpulseHeartbeatProperties.class);
                assertThat(properties.topic()).isEqualTo("fleetpulse/custom/heartbeat");
                assertThat(properties.stalenessThreshold()).isEqualTo(Duration.ofSeconds(45));
            });
    }
}
