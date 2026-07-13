package de.visterion.agora.research.fundamentals;

import de.visterion.agora.data.Instrument;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FundamentalsRouterTest {
    private final EdgarFundamentalsSource edgar = mock(EdgarFundamentalsSource.class);
    private final YahooTimeseriesFundamentalsSource yahoo = mock(YahooTimeseriesFundamentalsSource.class);
    private final Set<String> suffixes = Set.of("DE","L","T","HK","PA","AS","SW","AX","MI","TO","ST","CO","OL");
    private final FundamentalsRouter router = new FundamentalsRouter(edgar, yahoo, suffixes);

    private void routes(String input, FundamentalsSource expected) {
        when(edgar.facts(any())).thenReturn(new SourceResult(java.util.Map.of(), AbsenceSemantics.COMPLETE));
        when(yahoo.facts(any())).thenReturn(new SourceResult(java.util.Map.of(), AbsenceSemantics.SPARSE));
        var sem = router.facts(Instrument.raw(input)).semantics();
        assertThat(sem).isEqualTo(expected == edgar ? AbsenceSemantics.COMPLETE : AbsenceSemantics.SPARSE);
    }

    @Test void suffixedGoesYahoo()          { routes("SAP.DE", yahoo); }
    @Test void supersetForeignSuffixYahoo() { routes("EQNR.OL", yahoo); } // OL in the superset -> Yahoo
    @Test void unmappedSuffixGoesEdgar()    { routes("FOO.XX", edgar); }  // XX not in the set -> US default (fail-soft)
    @Test void plainUsGoesEdgar()           { routes("AAPL", edgar); }
    @Test void dottedUsClassGoesEdgar()     { routes("BRK.B", edgar); }   // B not a non-US suffix
    @Test void nonUsIsinGoesYahoo()         { routes("DE0007164600", yahoo); }
    @Test void usIsinGoesEdgar()            { routes("US0378331005", edgar); }
}
