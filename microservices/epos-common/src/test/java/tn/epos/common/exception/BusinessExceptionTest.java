package tn.epos.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BusinessException")
class BusinessExceptionTest {

    @Test
    @DisplayName("Carries the supplied message and is a RuntimeException")
    void carries_message() {
        BusinessException ex = new BusinessException("rule violated");
        assertThat(ex.getMessage()).isEqualTo("rule violated");
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}
