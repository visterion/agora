package de.visterion.agora.tools;

import de.visterion.agora.fetch.reference.change.IndexChange;
import de.visterion.agora.fetch.reference.change.IndexChangeService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class GetIndexConstituentChangesToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test void returnsChangesJsonShape() {
        IndexChangeService svc = Mockito.mock(IndexChangeService.class);
        when(svc.changes(any(), anyInt())).thenReturn(List.of(
                new IndexChange("SEI", "add", "sp500",
                        LocalDate.of(2026, 7, 9), LocalDate.of(2026, 7, 15), "sp_press"),
                new IndexChange("CPRX", "remove", "sp500",
                        LocalDate.of(2026, 7, 9), LocalDate.of(2026, 7, 15), "sp_press")));

        var r = new GetIndexConstituentChangesTool(svc).call(mapper.createObjectNode());
        assertThat(r.available()).isTrue();
        var changes = r.output().get("changes");
        assertThat(changes.size()).isEqualTo(2);
        assertThat(changes.get(0).get("symbol").asString()).isEqualTo("SEI");
        assertThat(changes.get(0).get("action").asString()).isEqualTo("add");
        assertThat(changes.get(0).get("index").asString()).isEqualTo("sp500");
        assertThat(changes.get(0).get("announcementDate").asString()).isEqualTo("2026-07-09");
        assertThat(changes.get(0).get("effectiveDate").asString()).isEqualTo("2026-07-15");
        assertThat(changes.get(0).get("source").asString()).isEqualTo("sp_press");
        assertThat(changes.get(1).get("action").asString()).isEqualTo("remove");
    }

    @Test void defaultsIndexAndLookback() {
        IndexChangeService svc = Mockito.mock(IndexChangeService.class);
        when(svc.changes(eq("sp500"), eq(30))).thenReturn(List.of());
        var r = new GetIndexConstituentChangesTool(svc).call(mapper.createObjectNode());
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("changes").size()).isEqualTo(0);
        Mockito.verify(svc).changes("sp500", 30);
    }

    @Test void blankIndexDefaultsToSp500() {
        IndexChangeService svc = Mockito.mock(IndexChangeService.class);
        when(svc.changes(eq("sp500"), anyInt())).thenReturn(List.of());
        var r = new GetIndexConstituentChangesTool(svc)
                .call(mapper.createObjectNode().put("index", "").put("lookback_days", 7));
        assertThat(r.available()).isTrue();
        Mockito.verify(svc).changes("sp500", 7);
    }

    @Test void degradesToEmptyArrayNeverUnavailable() {
        IndexChangeService svc = Mockito.mock(IndexChangeService.class);
        when(svc.changes(any(), anyInt())).thenReturn(List.of());
        var r = new GetIndexConstituentChangesTool(svc).call(mapper.createObjectNode().put("index", "nasdaq100"));
        assertThat(r.available()).isTrue();
        assertThat(r.output().get("changes").size()).isEqualTo(0);
    }
}
