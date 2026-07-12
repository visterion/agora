package de.visterion.agora.fetch.reference.change;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class RussellConstituentListParserTest {

    // Mirrors the real ru3000-additions PDF text layer: repeated per-page header/footer noise, the
    // "Company Symbol Industry" column header, data rows "<COMPANY> <TICKER> <INDUSTRY>", and the
    // trailing legal disclaimer — none of which end in an industry name except the data rows.
    private static final String ADDITIONS = """
            Russell US Indexes
            lseg.com/ftse-russell 1

            CORPORATE
            Reconstitution

            Russell 3000® Index - Additions

            Company Symbol Industry
            AARDVARK THERAPEUTICS AARD Health Care
            ACME UTD CORP ACU Health Care
            ACUREN (NYSE AMERICA) TIC Industrials
            BARNES & NOBLE EDUCATION BNED Consumer Discretionary
            BROOKFIELD ASSET MANAGEM BAM Financials
            CHEWY CHWY Consumer Discretionary
            For more information about our indexes, please visit lseg.com/ftse-russell.
            No part of this information may be reproduced, stored in a retrieval system.
            """;

    private ListAppender<ILoggingEvent> logs;
    private Logger parserLogger;

    @BeforeEach void attachAppender() {
        parserLogger = (Logger) LoggerFactory.getLogger(RussellConstituentListParser.class);
        logs = new ListAppender<>();
        logs.start();
        parserLogger.addAppender(logs);
    }

    @AfterEach void detachAppender() {
        parserLogger.detachAppender(logs);
    }

    private List<ILoggingEvent> warnings() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    @Test void parsesDataRowsAnchoredOnIndustryVocabulary() {
        List<RussellConstituentListParser.Row> rows = RussellConstituentListParser.parse(ADDITIONS);
        assertThat(rows).extracting(RussellConstituentListParser.Row::ticker,
                        RussellConstituentListParser.Row::companyName)
                .containsExactly(
                        tuple("AARD", "AARDVARK THERAPEUTICS"),
                        tuple("ACU", "ACME UTD CORP"),
                        tuple("TIC", "ACUREN (NYSE AMERICA)"),
                        tuple("BNED", "BARNES & NOBLE EDUCATION"),
                        tuple("BAM", "BROOKFIELD ASSET MANAGEM"),
                        tuple("CHWY", "CHEWY"));
    }

    @Test void excludesHeadersFootersAndTheColumnHeaderRow() {
        List<RussellConstituentListParser.Row> rows = RussellConstituentListParser.parse(ADDITIONS);
        // "Company Symbol Industry" header, "Russell 3000® Index - Additions", page/footer lines
        // and legal prose must never surface as a constituent.
        assertThat(rows).extracting(RussellConstituentListParser.Row::ticker)
                .doesNotContain("Symbol", "Reconstitution", "Indexes");
        assertThat(rows).hasSize(6);
    }

    @Test void cleanFixtureEmitsNoWarnings() {
        RussellConstituentListParser.parse(ADDITIONS);
        // None of the header/footer/legal lines are constituent-shaped, so nothing looks dropped.
        assertThat(warnings()).isEmpty();
    }

    @Test void parsesDotSeparatedTicker() {
        // Class-share tickers like BRK.B carry a dot; the ticker regex must accept it.
        List<RussellConstituentListParser.Row> rows = RussellConstituentListParser.parse(
                "BERKSHIRE HATHAWAY INC BRK.B Financials\n");
        assertThat(rows).extracting(RussellConstituentListParser.Row::ticker,
                        RussellConstituentListParser.Row::companyName)
                .containsExactly(tuple("BRK.B", "BERKSHIRE HATHAWAY INC"));
    }

    @Test void dropsUnrecognisedIndustryButLogsAndCountsIt() {
        // A future FTSE relabel ("Telecom" for "Telecommunications") or a new sub-sector: the row is
        // constituent-shaped but its industry tail is unknown -> dropped, yet the loss is logged.
        String text = """
                Company Symbol Industry
                AARDVARK THERAPEUTICS AARD Health Care
                FUTURE RELABEL CORP FRC Telecom
                """;
        List<RussellConstituentListParser.Row> rows = RussellConstituentListParser.parse(text);

        assertThat(rows).extracting(RussellConstituentListParser.Row::ticker).containsExactly("AARD");
        List<ILoggingEvent> warns = warnings();
        assertThat(warns).hasSize(1);
        assertThat(warns.get(0).getFormattedMessage())
                .contains("dropped 1")
                .contains("FUTURE RELABEL CORP FRC Telecom");
    }

    @Test void deduplicatesRepeatedTickers() {
        String withDup = "FOO CORP FOO Technology\nFOO CORP FOO Technology\n";
        assertThat(RussellConstituentListParser.parse(withDup)).hasSize(1);
    }

    @Test void emptyOrNullTextYieldsEmptyList() {
        assertThat(RussellConstituentListParser.parse(null)).isEmpty();
        assertThat(RussellConstituentListParser.parse("   ")).isEmpty();
        assertThat(RussellConstituentListParser.parse("no data rows here at all")).isEmpty();
    }
}
