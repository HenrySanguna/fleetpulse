package dev.fleetpulse.api.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.info.GitProperties;
import org.springframework.stereotype.Component;

import java.util.Optional;

// Reports DOWN when the deployed build was not instrumented with the commit
// SHA (see build.gradle.kts com.gorylenko.gradle-git-properties): deploying
// without knowing which commit is running is itself a build/deploy defect.
@Component
public class CommitHealthIndicator implements HealthIndicator {

    private final Optional<GitProperties> gitProperties;

    public CommitHealthIndicator(Optional<GitProperties> gitProperties) {
        this.gitProperties = gitProperties;
    }

    @Override
    public Health health() {
        return gitProperties
            .map(GitProperties::getCommitId)
            .filter(sha -> !sha.isBlank())
            .map(sha -> Health.up().withDetail("sha", sha).build())
            .orElseGet(() -> Health
                .down()
                .withDetail("reason", "commit SHA not available in build metadata")
                .build());
    }
}
