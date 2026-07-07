package de.visterion.agora.security;

import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolRegistry;
import de.visterion.agora.tool.ToolResult;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BearerTokenFilterTest {

    // --- Stub tools for namespace tests ---

    private static AgoraTool generalTool(String name) {
        ObjectMapper m = new ObjectMapper();
        return new AgoraTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "general stub"; }
            @Override public ObjectNode inputSchema() { return m.createObjectNode(); }
            @Override public ToolResult call(JsonNode args) { return ToolResult.unavailable("stub"); }
            // namespace() default = "general"
        };
    }

    private static AgoraTool tradingTool(String name) {
        ObjectMapper m = new ObjectMapper();
        return new AgoraTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "trading stub"; }
            @Override public ObjectNode inputSchema() { return m.createObjectNode(); }
            @Override public ToolResult call(JsonNode args) { return ToolResult.unavailable("stub"); }
            @Override public String namespace() { return "trading"; }
        };
    }

    private static ToolRegistry registryWith(AgoraTool... tools) {
        return new ToolRegistry(List.of(tools));
    }

    // --- Original tests (updated to new ctor: general, trading, registry) ---

    private final ToolRegistry emptyRegistry = registryWith();

    private final BearerTokenFilter filter =
            new BearerTokenFilter(List.of("good-token"), List.of(), List.of(), emptyRegistry);

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

    // --- New guard tests (Task 3) ---

    private BearerTokenFilter guardFilter() {
        ToolRegistry registry = registryWith(generalTool("gen"), tradingTool("trade"));
        return new BearerTokenFilter(List.of("gen-token"), List.of("trade-token"), List.of("live-token"), registry);
    }

    @Test
    void tradingToolWithTradingToken_proceeds() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/tools/trade");
        req.addHeader("Authorization", "Bearer trade-token");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        guardFilter().doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void tradingToolWithGeneralOnlyToken_isRejected() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/tools/trade");
        req.addHeader("Authorization", "Bearer gen-token");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        guardFilter().doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void generalToolWithGeneralToken_proceeds() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/tools/gen");
        req.addHeader("Authorization", "Bearer gen-token");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        guardFilter().doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void generalToolWithTradingToken_proceeds() throws Exception {
        // Trading token is superset — should work for general tools too
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/tools/gen");
        req.addHeader("Authorization", "Bearer trade-token");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        guardFilter().doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void unknownToolPath_generalTokenSuffices() throws Exception {
        // /tools/unknown -> tool not in registry → fall back to general ∪ trading
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/tools/unknown");
        req.addHeader("Authorization", "Bearer gen-token");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        guardFilter().doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    // --- Live-token tests (connection router) ---

    @Test
    void tradingToolWithLiveToken_proceeds() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/tools/trade");
        req.addHeader("Authorization", "Bearer live-token");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        guardFilter().doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void generalToolWithLiveToken_proceeds() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/tools/gen");
        req.addHeader("Authorization", "Bearer live-token");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        guardFilter().doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void authorizedRequestCarriesTokenAttribute() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/tools/trade");
        req.addHeader("Authorization", "Bearer trade-token");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        guardFilter().doFilter(req, res, chain);
        assertThat(req.getAttribute(BearerTokenFilter.TOKEN_ATTR)).isEqualTo("trade-token");
    }

    @Test
    void unauthorizedRequestHasNoTokenAttribute() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/tools/trade");
        req.addHeader("Authorization", "Bearer wrong-token");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        guardFilter().doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(req.getAttribute(BearerTokenFilter.TOKEN_ATTR)).isNull();
    }

    @Test
    void saxoAuthPathsArePublic() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/auth/saxo/callback");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(req, res, chain);          // no Authorization header
        verify(chain).doFilter(any(), any());
    }
}
