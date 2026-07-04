package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.MarketDataService;
import de.visterion.agora.data.OhlcBar;
import de.visterion.agora.research.ResearchDefaults;
import de.visterion.agora.research.Ta4jBars;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.StochasticOscillatorKIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.num.Num;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

@Component
public class GetStochasticTool implements AgoraTool {

    private final MarketDataService service;
    private final int defaultKPeriod;
    private final int defaultDPeriod;
    private final int fetchDays;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetStochasticTool(MarketDataService service, ResearchDefaults d) {
        this(service, d.stochasticK(), d.stochasticD(), d.fetchDays());
    }

    // Test ctor
    GetStochasticTool(MarketDataService service, int defaultKPeriod, int defaultDPeriod, int fetchDays) {
        this.service = service;
        this.defaultKPeriod = defaultKPeriod;
        this.defaultDPeriod = defaultDPeriod;
        this.fetchDays = fetchDays;
    }

    public String name() { return "get_stochastic"; }
    public String description() {
        return "Stochastic Oscillator (%K / %D) for a symbol. Defaults K=" + defaultKPeriod + ", D=" + defaultDPeriod + ".";
    }

    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("symbol").put("type", "string").put("description", "Ticker symbol");
        props.putObject("kPeriod").put("type", "integer").put("description", "%K look-back period (default " + defaultKPeriod + ")");
        props.putObject("dPeriod").put("type", "integer").put("description", "%D SMA period (default " + defaultDPeriod + ")");
        schema.putArray("required").add("symbol");
        return schema;
    }

    public ToolResult call(JsonNode args) {
        if (args == null || !args.hasNonNull("symbol")) return ToolResult.unavailable("no symbol provided");
        String symbol = args.get("symbol").asString();
        int kPeriod = intArg(args, "kPeriod", defaultKPeriod);
        int dPeriod = intArg(args, "dPeriod", defaultDPeriod);
        if (kPeriod <= 0 || dPeriod <= 0) return ToolResult.unavailable("invalid period");

        List<OhlcBar> bars;
        try { bars = service.ohlc(symbol, fetchDays); }
        catch (MarketDataException e) { return ToolResult.unavailable(e.getMessage()); }

        int minBars = kPeriod + dPeriod;
        if (bars.size() < minBars) return ToolResult.unavailable("insufficient history for stochastic");
        BarSeries series = Ta4jBars.toSeries(bars);
        StochasticOscillatorKIndicator kIndicator = new StochasticOscillatorKIndicator(series, kPeriod);
        SMAIndicator dIndicator = new SMAIndicator(kIndicator, dPeriod);

        int end = series.getEndIndex();
        Num kV = kIndicator.getValue(end);
        Num dV = dIndicator.getValue(end);
        if (kV.isNaN() || dV.isNaN()) return ToolResult.unavailable("insufficient history for stochastic");

        ObjectNode out = mapper.createObjectNode();
        out.put("symbol", symbol);
        out.put("k", Ta4jBars.toBd(kV, 4));
        out.put("d", Ta4jBars.toBd(dV, 4));
        out.put("available", true);
        return ToolResult.ok(out);
    }

    private static int intArg(JsonNode args, String field, int fallback) {
        return (args.has(field) && !args.get(field).isNull()) ? args.get(field).asInt() : fallback;
    }
}
