package tn.epos.common.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the shared {@link ApiResponse} envelope. Covers each static
 * factory and confirms {@code @JsonInclude(NON_NULL)} drops absent fields
 * (verified via direct getter inspection — JSON serialization isn't this
 * test's job, the consumer services exercise that end-to-end).
 */
@DisplayName("ApiResponse — static factories")
class ApiResponseTest {

    @Test
    @DisplayName("ok(data) sets success=true and data, leaves message null")
    void ok_dataOnly() {
        // Note: ok(String) is the message overload — to hit ok(T data) for the
        // data-only case, use a non-String type. Real callers pick the right
        // overload by context; a String payload would use ok(message, data).
        ApiResponse<Integer> r = ApiResponse.ok(42);
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getData()).isEqualTo(42);
        assertThat(r.getMessage()).isNull();
    }

    @Test
    @DisplayName("ok(message) sets success=true and message, leaves data null")
    void ok_messageOnly() {
        ApiResponse<Object> r = ApiResponse.ok("done");
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getMessage()).isEqualTo("done");
        assertThat(r.getData()).isNull();
    }

    @Test
    @DisplayName("ok(message, data) sets all three fields")
    void ok_messageAndData() {
        ApiResponse<Integer> r = ApiResponse.ok("created", 42);
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getMessage()).isEqualTo("created");
        assertThat(r.getData()).isEqualTo(42);
    }

    @Test
    @DisplayName("error(message) sets success=false and message, leaves data null")
    void error_messageOnly() {
        ApiResponse<Object> r = ApiResponse.error("nope");
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getMessage()).isEqualTo("nope");
        assertThat(r.getData()).isNull();
    }

    @Test
    @DisplayName("error(message, data) sets success=false and carries an error payload")
    void error_messageAndData() {
        ApiResponse<String> r = ApiResponse.error("invalid", "field=name");
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getMessage()).isEqualTo("invalid");
        assertThat(r.getData()).isEqualTo("field=name");
    }
}
