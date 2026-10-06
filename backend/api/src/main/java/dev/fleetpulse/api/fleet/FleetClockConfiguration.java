package dev.fleetpulse.api.fleet;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
class FleetClockConfiguration {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock fleetClock() {
        return Clock.systemUTC();
    }
}
