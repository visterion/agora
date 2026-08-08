package de.visterion.agora.fetch.search;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class YahooSearchParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Hand-written, synthetic. Shaped like Yahoo's /v1/finance/search response. */
    private static final String PAYLOAD = """
        {"count":6,"quotes":[
          {"symbol":"SYNA","shortname":"Synthetic A Corp","longname":"Synthetic Alpha Oyj",
           "exchDisp":"NYSE","quoteType":"EQUITY"},
          {"symbol":"SYNA.HE","shortname":"Synthetic A Corp","exchDisp":"HEL","quoteType":"EQUITY"},
          {"symbol":"SYNETF.SG","shortname":"Synthetic UCITS ETF","exchDisp":"Stuttgart",
           "quoteType":"MUTUALFUND"},
          {"symbol":"^SYNIDX","shortname":"Synthetic Index","exchDisp":"IDX","quoteType":"INDEX"},
          {"symbol":"SYNA.HE","shortname":"Synthetic A Corp","exchDisp":"HEL","quoteType":"EQUITY"},
          {"shortname":"No Symbol Here","exchDisp":"NYSE","quoteType":"EQUITY"}
        ]}
        """;

    @Test void keepsEquitiesAndExchangeListedFunds_dropsIndexAndSymbolLessRows() {
        List<SearchHit> hits = YahooSearchParser.parse(MAPPER.readTree(PAYLOAD));

        assertThat(hits).extracting(SearchHit::symbol)
                .containsExactly("SYNA", "SYNA.HE", "SYNETF.SG");
    }

    @Test void prefersLongnameThenShortnameThenSymbol() {
        List<SearchHit> hits = YahooSearchParser.parse(MAPPER.readTree("""
            {"quotes":[
              {"symbol":"AAA","longname":"Long Name","shortname":"Short","quoteType":"EQUITY","exchDisp":"NYSE"},
              {"symbol":"BBB","shortname":"Only Short","quoteType":"EQUITY","exchDisp":"NYSE"},
              {"symbol":"CCC","quoteType":"EQUITY","exchDisp":"NYSE"}
            ]}
            """));

        assertThat(hits).extracting(SearchHit::name)
                .containsExactly("Long Name", "Only Short", "CCC");
    }

    @Test void emptyResultIsAnEmptyListNotAnException() {
        assertThat(YahooSearchParser.parse(MAPPER.readTree("{\"count\":0,\"quotes\":[]}"))).isEmpty();
        assertThat(YahooSearchParser.parse(MAPPER.readTree("{}"))).isEmpty();
    }

    @Test void passesLongSymbolsThroughUnchanged() {
        List<SearchHit> hits = YahooSearchParser.parse(MAPPER.readTree("""
            {"quotes":[{"symbol":"AT0000A324Q2.VI","shortname":"Synthetic Fund",
                        "exchDisp":"Vienna","quoteType":"EQUITY"}]}
            """));

        assertThat(hits).singleElement()
                .satisfies(h -> assertThat(h.symbol()).isEqualTo("AT0000A324Q2.VI"));
    }

    @Test void keepsRowsWithMissingOrBlankQuoteType_withEmptyType() {
        List<SearchHit> hits = YahooSearchParser.parse(MAPPER.readTree("""
            {"quotes":[
              {"symbol":"SYNNOTYPE","shortname":"Synthetic Typeless Corp","exchDisp":"NYSE"},
              {"symbol":"SYNBLANK","shortname":"Synthetic Blank Corp","exchDisp":"HEL","quoteType":""}
            ]}
            """));

        assertThat(hits).extracting(SearchHit::symbol)
                .containsExactly("SYNNOTYPE", "SYNBLANK");
        assertThat(hits).extracting(SearchHit::type)
                .containsExactly("", "");
    }

    @Test void dropsCurrencyFutureAndCrypto() {
        List<SearchHit> hits = YahooSearchParser.parse(MAPPER.readTree("""
            {"quotes":[
              {"symbol":"EURUSD=X","quoteType":"CURRENCY","exchDisp":"CCY","shortname":"x"},
              {"symbol":"ALI=F","quoteType":"FUTURE","exchDisp":"CMX","shortname":"y"},
              {"symbol":"BTC-USD","quoteType":"CRYPTOCURRENCY","exchDisp":"CCC","shortname":"z"}
            ]}
            """));

        assertThat(hits).isEmpty();
    }
}
