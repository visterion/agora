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
        when(svc.form4Transactions(any(), any(), anyInt())).thenReturn(new EdgarSearchService.Form4Result(List.of(
                new Form4Transaction("AAPL", "Cook Timothy", "CEO", LocalDate.parse("2025-05-05"),
                        new BigDecimal("1000"), new BigDecimal("190000"), "P", "A", "4",
                        new BigDecimal("190.00"), new BigDecimal("34567"), Boolean.TRUE, "0001214156")), false));
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
        assertThat(t.get("acquiredDisposedCode").asString()).isEqualTo("A");
        assertThat(t.get("form").asString()).isEqualTo("4");
        assertThat(t.get("price").decimalValue()).isEqualByComparingTo("190.00");
        assertThat(t.get("sharesOwnedFollowing").decimalValue()).isEqualByComparingTo("34567");
        assertThat(t.get("aff10b5One").asBoolean()).isTrue();
        assertThat(t.get("filerCik").asString()).isEqualTo("0001214156");
        assertThat(r.output().get("truncated").asBoolean()).isFalse();
    }

    // Wire semantics for the tri-state/nullable additions: absent-in-filing values serialize as
    // JSON null (aff10b5One null = "unknown", pre-2023 filing), never as false/0.
    @Test void nullableNewFieldsSerializeAsJsonNull() {
        EdgarSearchService svc = Mockito.mock(EdgarSearchService.class);
        when(svc.form4Transactions(any(), any(), anyInt())).thenReturn(new EdgarSearchService.Form4Result(List.of(
                new Form4Transaction("AAPL", "Cook Timothy", "", LocalDate.parse("2025-05-05"),
                        new BigDecimal("1000"), BigDecimal.ZERO, "G", "", "4",
                        null, null, null, "")), false));
        var r = new GetForm4TransactionsTool(svc).call(mapper.createObjectNode());
        assertThat(r.available()).isTrue();
        var t = r.output().get("transactions").get(0);
        assertThat(t.get("price").isNull()).isTrue();
        assertThat(t.get("sharesOwnedFollowing").isNull()).isTrue();
        assertThat(t.get("aff10b5One").isNull()).isTrue();
        assertThat(t.get("filerCik").asString()).isEmpty();
    }

    @Test void truncatedFlagPassedThrough() {
        EdgarSearchService svc = Mockito.mock(EdgarSearchService.class);
        when(svc.form4Transactions(any(), any(), anyInt()))
                .thenReturn(new EdgarSearchService.Form4Result(List.of(), true));
        var r = new GetForm4TransactionsTool(svc).call(mapper.createObjectNode());
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("truncated").asBoolean()).isTrue();
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

    @Test void fromAfterToUnavailable() {
        var args = mapper.createObjectNode().put("from", "2025-05-10").put("to", "2025-05-01");
        assertThat(new GetForm4TransactionsTool(Mockito.mock(EdgarSearchService.class)).call(args).available()).isFalse();
    }

    @Test void nonIntegralLimitUnavailable() {
        var args = mapper.createObjectNode().put("limit", 2.5);
        assertThat(new GetForm4TransactionsTool(Mockito.mock(EdgarSearchService.class)).call(args).available()).isFalse();
    }

    @Test void descriptionMentionsReturnedTransactions() {
        String description = new GetForm4TransactionsTool(Mockito.mock(EdgarSearchService.class)).inputSchema()
                .path("properties").path("limit").path("description").asString();
        assertThat(description).contains("transactions to return");
    }

    @Test void oversizedLimitIsClampedTo100() {
        EdgarSearchService svc = Mockito.mock(EdgarSearchService.class);
        when(svc.form4Transactions(any(), any(), anyInt()))
                .thenReturn(new EdgarSearchService.Form4Result(List.of(), false));
        var args = mapper.createObjectNode();
        args.put("limit", 100_000);
        new GetForm4TransactionsTool(svc).call(args);
        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        verify(svc).form4Transactions(any(), any(), captor.capture());
        assertThat(captor.getValue()).isEqualTo(100);
    }
}
