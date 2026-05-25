package tn.epos.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ResourceNotFoundException")
class ResourceNotFoundExceptionTest {

    @Test
    @DisplayName("Two-arg constructor formats the canonical 'introuvable' message")
    void twoArg_formatsMessage() {
        ResourceNotFoundException ex = new ResourceNotFoundException("Examen", 42L);
        assertThat(ex.getMessage()).isEqualTo("Examen introuvable avec l'id : 42");
    }

    @Test
    @DisplayName("Single-arg constructor uses the supplied message verbatim")
    void oneArg_usesMessageVerbatim() {
        ResourceNotFoundException ex = new ResourceNotFoundException("free-form text");
        assertThat(ex.getMessage()).isEqualTo("free-form text");
    }

    @Test
    @DisplayName("Is a RuntimeException")
    void isRuntimeException() {
        assertThat(new ResourceNotFoundException("x", 1L)).isInstanceOf(RuntimeException.class);
    }
}
