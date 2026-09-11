package dev.fleetpulse.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

// Task 2.1: password hashing with Argon2 (preferred over BCrypt per tasks.md).
// Pure unit test, no Spring context needed -- the encoder bean is a plain
// object once constructed.
class PasswordEncoderConfigurationTest {

    private final PasswordEncoder encoder = new SecurityConfig().passwordEncoder();

    @Test
    void verifiesTheCorrectRawPasswordAgainstItsEncodedHash() {
        String encoded = encoder.encode("correct-horse-battery-staple");

        assertThat(encoder.matches("correct-horse-battery-staple", encoded)).isTrue();
    }

    @Test
    void rejectsAnIncorrectRawPassword() {
        String encoded = encoder.encode("correct-horse-battery-staple");

        assertThat(encoder.matches("wrong-password", encoded)).isFalse();
    }

    @Test
    void saltsEachEncodedHashDifferently() {
        String first = encoder.encode("correct-horse-battery-staple");
        String second = encoder.encode("correct-horse-battery-staple");

        assertThat(first).isNotEqualTo(second);
    }
}
