package tn.epos.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour {@link ConflictException}.
 * Vérifie la simple transmission du message et la hiérarchie d'exception.
 */
class ConflictExceptionTest {

    @Test
    @DisplayName("Doit stocker et retourner le message passé au constructeur")
    void constructor_shouldStoreMessage() {
        String message = "Conflit détecté sur le numéro 481";

        ConflictException exception = new ConflictException(message);

        assertThat(exception.getMessage()).isEqualTo(message);
    }

    @Test
    @DisplayName("Doit être une instance de RuntimeException (Unchecked Exception)")
    void inheritance_shouldBeRuntimeException() {
        ConflictException exception = new ConflictException("msg");

        assertThat(exception).isInstanceOf(RuntimeException.class);
    }
}
