package de.visterion.agora.tools;

import de.visterion.agora.tool.ToolResult;
import de.visterion.agora.trading.BrokerService;
import de.visterion.agora.trading.OrderResult;
import de.visterion.agora.trading.TestConnections;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CancelOrderToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void cancels_returnsAcceptedEnvelope() {
        BrokerService broker = mock(BrokerService.class);
        when(broker.cancel(TestConnections.CONN, "oid-1")).thenReturn(OrderResult.accepted("oid-1", null, "canceled"));
        var tool = new CancelOrderTool(broker);
        ObjectNode args = mapper.createObjectNode(); args.put("connection", TestConnections.CONN).put("orderId", "oid-1");
        ToolResult r = tool.call(args);
        assertThat(r.output().path("accepted").asBoolean()).isTrue();
        assertThat(r.output().path("status").asString()).isEqualTo("canceled");
        assertThat(tool.namespace()).isEqualTo("trading");
    }

    @Test void missingOrderId_unavailable() {
        var tool = new CancelOrderTool(mock(BrokerService.class));
        ToolResult r = tool.call(mapper.createObjectNode().put("connection", TestConnections.CONN));
        assertThat(r.available()).isFalse();
    }

    @Test void missingConnectionUnavailable() {
        BrokerService broker = mock(BrokerService.class);
        var tool = new CancelOrderTool(broker);
        ToolResult r = tool.call(mapper.createObjectNode().put("orderId", "oid-1"));
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("connection");
        verifyNoInteractions(broker);
    }
}
