package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.edgar.EdgarSearchService;
import de.visterion.agora.fetch.edgar.Form4Transaction;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

class GetForm4TransactionsToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void returnsTransactions() {
        EdgarSearchService svc = Mockito.mock(EdgarSearchService.class);
        when(svc.form4Transactions(any(), any(), anyInt())).thenReturn(List.of(
                new Form4Transaction("AAPL", "Cook Timothy", "CEO", LocalDate.parse("2025-05-05"),
                        new BigDecimal("1000"), new BigDecimal("190000"), "P")));
        var r = new GetForm4TransactionsTool(svc).call(mapper.createObjectNode());
        assertThat(r.available()).isTrue();
        var t = r.output().get("transactions").get(0);
        assertThat(t.get("ticker").asString()).isEqualTo("AAPL");
        assertThat(t.get("filerName").asString()).isEqualTo("Cook Timothy");
        assertThat(t.get("filerRole").asString()).isEqualTo("CEO");
        assertThat(t.get("transactionDate").asString()).isEqualTo("2025-05-05");
        assertThat(t.get("shares").decimalValue()).isEqualByComparingTo("1000");
        assertThat(t.get("dollarValue").decimalValue()).isEqualByComparingTo("190000");
        assertThat(t.get("code").asString()).isEqualTo("P");
    }

    @Test void invalidDateUnavailable() {
        var r = new GetForm4TransactionsTool(Mockito.mock(EdgarSearchService.class))
                .call(mapper.createObjectNode().put("from", "not-a-date"));
        assertThat(r.available()).isFalse();
    }

    @Test void serviceExceptionUnavailable() {
        EdgarSearchService svc = Mockito.mock(EdgarSearchService.class);
        when(svc.form4Transactions(any(), any(), anyInt()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "EDGAR down", null));
        assertThat(new GetForm4TransactionsTool(svc).call(mapper.createObjectNode()).available()).isFalse();
    }

    @Test void namespaceIsGeneral() {
        assertThat(new GetForm4TransactionsTool(Mockito.mock(EdgarSearchService.class)).namespace())
                .isEqualTo("general");
    }

    @Test void oversizedLimitIsClampedTo100() {
        EdgarSearchService svc = Mockito.mock(EdgarSearchService.class);
        when(svc.form4Transactions(any(), any(), anyInt())).thenReturn(List.of());
        var args = mapper.createObjectNode();
        args.put("limit", 100_000);
        new GetForm4TransactionsTool(svc).call(args);
        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        verify(svc).form4Transactions(any(), any(), captor.capture());
        assertThat(captor.getValue()).isEqualTo(100);
    }
}
