package io.github.muzachio.moneytrail.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class UserIdLoggingFilterTests {

    private final UserIdLoggingFilter filter = new UserIdLoggingFilter();

    @Test
    void putsUserIdInLogContextDuringRequestAndRemovesItAfterwards() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setUserPrincipal(() -> "0199f0a2-7c3e-7d4b-9c1a-2b3c4d5e6f70");
        AtomicReference<String> seenDuringRequest = new AtomicReference<>();

        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (req, res) -> seenDuringRequest.set(MDC.get(UserIdLoggingFilter.MDC_KEY)));

        assertThat(seenDuringRequest.get()).isEqualTo("0199f0a2-7c3e-7d4b-9c1a-2b3c4d5e6f70");
        assertThat(MDC.get(UserIdLoggingFilter.MDC_KEY)).isNull();
    }

    @Test
    void addsNothingForAnonymousRequests() throws Exception {
        AtomicReference<String> seenDuringRequest = new AtomicReference<>("not called");

        filter.doFilter(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                (req, res) -> seenDuringRequest.set(MDC.get(UserIdLoggingFilter.MDC_KEY)));

        assertThat(seenDuringRequest.get()).isNull();
    }
}
