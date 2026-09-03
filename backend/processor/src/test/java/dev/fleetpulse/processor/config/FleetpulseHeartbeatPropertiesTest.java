package dev.fleetpulse.processor.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class FleetpulseHeartbeatPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfig.class);

    @EnableConfigurationProperties(FleetpulseHeartbeatProperties.class)
    static class TestConfig {
    }

    @Test
    void defaultsToTheSharedHeartbeatTopicWhenNotConfigured() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(FleetpulseHeartbeatProperties.class).topic())
                .isEqualTo("fleetpulse/processor/heartbeat");
        });
    }

    @Test
    void honorsAnExplicitlyConfiguredTopic() {
        contextRunner
            .withPropertyValues("fleetpulse.mqtt.heartbeat.topic=fleetpulse/custom/heartbeat")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(FleetpulseHeartbeatProperties.class).topic())
                    .isEqualTo("fleetpulse/custom/heartbeat");
            });
    }
}
