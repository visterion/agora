package de.visterion.agora.fetch.edgar;

import java.util.List;

/**
 * The set of exchange tickers SEC lists for a filer CIK — the authority a heuristically
 * extracted symbol is validated against.
 *
 * <p>Exists so the ticker check is a declared collaborator rather than a static lookup:
 * {@link EdgarCikResolver} is the production implementation (SEC {@code company_tickers.json}),
 * and a test can supply a fixed universe without an HTTP round trip.
 *
 * <p>Contract: an unknown, unlisted or unparsable CIK yields an EMPTY list, and so does an
 * unavailable upstream. Callers must therefore treat "no tickers" as "do not emit a symbol",
 * never as "fall back to guessing" — a fabricated symbol routes a quote lookup and a merger
 * spread to a different company, which is strictly worse than an absent one.
 */
@FunctionalInterface
public interface TickerUniverse {

    /**
     * All tickers SEC lists for {@code cik}, in {@code company_tickers.json} order (SEC orders
     * that file by market cap descending, so the first entry is the filer's primary symbol).
     * Never null; empty when the CIK is unknown or the universe is unavailable.
     */
    List<String> tickersForCik(String cik);
}
