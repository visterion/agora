package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.edgar.EdgarSearchService;
import de.visterion.agora.fetch.edgar.EdgarService;
import de.visterion.agora.fetch.edgar.Form4Transaction;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GetForm4OwnerHistoryToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private static Form4Transaction tx(String name, String cik, String role, String date, String code,
                                       String shares, String price, String owned, Boolean aff10b5One) {
        BigDecimal sh = new BigDecimal(shares);
        BigDecimal pr = price == null ? null : new BigDecimal(price);
        return new Form4Transaction("AAPL", name, role, LocalDate.parse(date), sh,
                pr == null ? BigDecimal.ZERO : sh.multiply(pr), code, "A", "4",
                pr, owned == null ? null : new BigDecimal(owned), aff10b5One, cik);
    }

    private GetForm4OwnerHistoryTool tool(EdgarSearchService search, EdgarService edgar) {
        return new GetForm4OwnerHistoryTool(search, edgar);
    }

    @Test void groupsTransactionsPerOwnerSortedNewestFirst() {
        EdgarSearchService search = Mockito.mock(EdgarSearchService.class);
        EdgarService edgar = Mockito.mock(EdgarService.class);
        when(edgar.resolveCik("AAPL", null)).thenReturn("0000320193");
        when(search.form4TransactionsByCik(eq("0000320193"), any(), any(), anyInt()))
                .thenReturn(new EdgarSearchService.Form4Result(List.of(
                        tx("Cook Timothy", "0001214156", "CEO", "2025-02-05", "P", "100", "180.00", "1100", true),
                        tx("Jane Doe", "0009999999", "CFO", "2025-03-01", "S", "50", "200.00", "500", false),
                        tx("Cook Timothy", "0001214156", "", "2025-05-05", "P", "200", "190.00", "1300", null)), false));

        var r = tool(search, edgar).call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("cik").asString()).isEqualTo("0000320193");
        var owners = r.output().get("owners");
        assertThat(owners).hasSize(2);

        var cook = owners.get(0);
        assertThat(cook.get("name").asString()).isEqualTo("Cook Timothy");
        assertThat(cook.get("cik").asString()).isEqualTo("0001214156");
        assertThat(cook.get("role").asString()).isEqualTo("CEO"); // first non-empty across the group
        var cookTx = cook.get("transactions");
        assertThat(cookTx).hasSize(2);
        // newest first
        assertThat(cookTx.get(0).get("transactionDate").asString()).isEqualTo("2025-05-05");
        assertThat(cookTx.get(1).get("transactionDate").asString()).isEqualTo("2025-02-05");
        var newest = cookTx.get(0);
        assertThat(newest.get("code").asString()).isEqualTo("P");
        assertThat(newest.get("shares").decimalValue()).isEqualByComparingTo("200");
        assertThat(newest.get("price").decimalValue()).isEqualByComparingTo("190.00");
        assertThat(newest.get("dollarValue").decimalValue()).isEqualByComparingTo("38000");
        assertThat(newest.get("sharesOwnedFollowing").decimalValue()).isEqualByComparingTo("1300");
        assertThat(newest.get("aff10b5One").isNull()).isTrue(); // pre-2023 filing → unknown
        assertThat(cookTx.get(1).get("aff10b5One").asBoolean()).isTrue();

        var doe = owners.get(1);
        assertThat(doe.get("name").asString()).isEqualTo("Jane Doe");
        assertThat(doe.get("transactions").get(0).get("aff10b5One").asBoolean()).isFalse();
        assertThat(r.output().get("truncated").asBoolean()).isFalse();
    }

    @Test void ownersWithoutCikAreGroupedByName() {
        EdgarSearchService search = Mockito.mock(EdgarSearchService.class);
        EdgarService edgar = Mockito.mock(EdgarService.class);
        when(edgar.resolveCik("AAPL", null)).thenReturn("0000320193");
        when(search.form4TransactionsByCik(any(), any(), any(), anyInt()))
                .thenReturn(new EdgarSearchService.Form4Result(List.of(
                        tx("Old Filer", "", "", "2024-01-05", "P", "10", "5.00", null, null),
                        tx("Old Filer", "", "Director", "2024-06-05", "P", "10", "6.00", null, null)), false));
        var r = tool(search, edgar).call(mapper.createObjectNode().put("symbol", "AAPL"));
        var owners = r.output().get("owners");
        assertThat(owners).hasSize(1);
        assertThat(owners.get(0).get("cik").asString()).isEmpty();
        assertThat(owners.get(0).get("role").asString()).isEqualTo("Director");
        assertThat(owners.get(0).get("transactions")).hasSize(2);
    }

    @Test void defaultWindowIsThreeYears() {
        EdgarSearchService search = Mockito.mock(EdgarSearchService.class);
        EdgarService edgar = Mockito.mock(EdgarService.class);
        when(edgar.resolveCik("AAPL", null)).thenReturn("0000320193");
        when(search.form4TransactionsByCik(any(), any(), any(), anyInt()))
                .thenReturn(new EdgarSearchService.Form4Result(List.of(), false));
        var r = tool(search, edgar).call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.available()).isTrue();
        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(search).form4TransactionsByCik(eq("0000320193"), from.capture(), to.capture(), eq(200));
        assertThat(to.getValue()).isEqualTo(LocalDate.now());
        assertThat(from.getValue()).isEqualTo(LocalDate.now().minusYears(3));
        assertThat(r.output().get("from").asString()).isEqualTo(from.getValue().toString());
        assertThat(r.output().get("to").asString()).isEqualTo(to.getValue().toString());
    }

    @Test void yearsAndLimitAreClamped() {
        EdgarSearchService search = Mockito.mock(EdgarSearchService.class);
        EdgarService edgar = Mockito.mock(EdgarService.class);
        when(edgar.resolveCik("AAPL", null)).thenReturn("0000320193");
        when(search.form4TransactionsByCik(any(), any(), any(), anyInt()))
                .thenReturn(new EdgarSearchService.Form4Result(List.of(), false));
        var args = mapper.createObjectNode().put("symbol", "AAPL").put("years", 50).put("limit", 100_000);
        tool(search, edgar).call(args);
        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        verify(search).form4TransactionsByCik(eq("0000320193"), from.capture(), any(), eq(500));
        assertThat(from.getValue()).isEqualTo(LocalDate.now().minusYears(5)); // years capped at 5
    }

    @Test void missingSymbolAndCikUnavailable() {
        var r = tool(Mockito.mock(EdgarSearchService.class), Mockito.mock(EdgarService.class))
                .call(mapper.createObjectNode());
        assertThat(r.available()).isFalse();
        assertThat(r.error()).contains("symbol or cik");
    }

    @Test void cikInputWorksWithoutSymbol() {
        EdgarSearchService search = Mockito.mock(EdgarSearchService.class);
        EdgarService edgar = Mockito.mock(EdgarService.class);
        when(edgar.resolveCik(null, "320193")).thenReturn("0000320193");
        when(search.form4TransactionsByCik(any(), any(), any(), anyInt()))
                .thenReturn(new EdgarSearchService.Form4Result(List.of(), false));
        var r = tool(search, edgar).call(mapper.createObjectNode().put("cik", "320193"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("cik").asString()).isEqualTo("0000320193");
        verify(edgar).resolveCik(null, "320193");
        verify(search).form4TransactionsByCik(eq("0000320193"), any(), any(), anyInt());
    }

    // When symbol AND cik are given, both are forwarded to resolveCik — which prefers the
    // explicit CIK (see EdgarService.resolveCik), so no symbol lookup decides the entity.
    @Test void explicitCikTakesPrecedenceOverSymbol() {
        EdgarSearchService search = Mockito.mock(EdgarSearchService.class);
        EdgarService edgar = Mockito.mock(EdgarService.class);
        when(edgar.resolveCik("AAPL", "320193")).thenReturn("0000320193");
        when(search.form4TransactionsByCik(any(), any(), any(), anyInt()))
                .thenReturn(new EdgarSearchService.Form4Result(List.of(), false));
        var r = tool(search, edgar).call(mapper.createObjectNode().put("symbol", "AAPL").put("cik", "320193"));
        assertThat(r.available()).isTrue();
        verify(edgar).resolveCik("AAPL", "320193");
        verify(search).form4TransactionsByCik(eq("0000320193"), any(), any(), anyInt());
    }

    @Test void unknownSymbolUnavailable() {
        EdgarService edgar = Mockito.mock(EdgarService.class);
        when(edgar.resolveCik(any(), any()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.NOT_FOUND, "no CIK for NOPE", null));
        var r = tool(Mockito.mock(EdgarSearchService.class), edgar)
                .call(mapper.createObjectNode().put("symbol", "NOPE"));
        assertThat(r.available()).isFalse();
    }

    @Test void nonIntegralYearsUnavailable() {
        var args = mapper.createObjectNode().put("symbol", "AAPL").put("years", 2.5);
        assertThat(tool(Mockito.mock(EdgarSearchService.class), Mockito.mock(EdgarService.class))
                .call(args).available()).isFalse();
    }

    @Test void serviceExceptionUnavailable() {
        EdgarSearchService search = Mockito.mock(EdgarSearchService.class);
        EdgarService edgar = Mockito.mock(EdgarService.class);
        when(edgar.resolveCik(any(), any())).thenReturn("0000320193");
        when(search.form4TransactionsByCik(any(), any(), any(), anyInt()))
                .thenThrow(new MarketDataException(MarketDataException.Kind.UNAVAILABLE, "EDGAR down", null));
        assertThat(tool(search, edgar).call(mapper.createObjectNode().put("symbol", "AAPL")).available()).isFalse();
    }

    @Test void truncatedFlagPassedThrough() {
        EdgarSearchService search = Mockito.mock(EdgarSearchService.class);
        EdgarService edgar = Mockito.mock(EdgarService.class);
        when(edgar.resolveCik(any(), any())).thenReturn("0000320193");
        when(search.form4TransactionsByCik(any(), any(), any(), anyInt()))
                .thenReturn(new EdgarSearchService.Form4Result(List.of(), true));
        var r = tool(search, edgar).call(mapper.createObjectNode().put("symbol", "AAPL"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("truncated").asBoolean()).isTrue();
    }

    @Test void namespaceIsGeneral() {
        assertThat(tool(Mockito.mock(EdgarSearchService.class), Mockito.mock(EdgarService.class)).namespace())
                .isEqualTo("general");
    }
}
