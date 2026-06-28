package de.visterion.agora.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BearerTokenFilterTest {

    private final BearerTokenFilter filter = new BearerTokenFilter(List.of("good-token"));

    @Test
    void rejectsMissingToken() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/tools/ping");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void acceptsGoodToken() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/tools/ping");
        req.addHeader("Authorization", "Bearer good-token");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));
    }

    @Test
    void healthIsPublic() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(req, res, chain);
        verify(chain).doFilter(any(), any());
    }
}
