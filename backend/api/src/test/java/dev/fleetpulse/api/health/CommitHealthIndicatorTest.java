package dev.fleetpulse.api.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.info.GitProperties;

import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class CommitHealthIndicatorTest {

    @Test
    void reportsUpWithCommitShaWhenGitPropertiesArePresent() {
        Properties raw = new Properties();
        raw.setProperty("commit.id", "950326bd726b3e87e23db09dbded45cb17ed0302");
        GitProperties gitProperties = new GitProperties(raw);

        CommitHealthIndicator indicator = new CommitHealthIndicator(Optional.of(gitProperties));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(indicator.health().getDetails())
            .containsEntry("sha", "950326bd726b3e87e23db09dbded45cb17ed0302");
    }

    @Test
    void reportsDownWhenGitPropertiesAreMissing() {
        CommitHealthIndicator indicator = new CommitHealthIndicator(Optional.empty());

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }
}
