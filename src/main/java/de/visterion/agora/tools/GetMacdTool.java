package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.MarketDataService;
import de.visterion.agora.data.OhlcBar;
import de.visterion.agora.research.ResearchDefaults;
import de.visterion.agora.research.Ta4jBars;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.List;

@Component
public class GetMacdTool implements AgoraTool {

    private final MarketDataService service;
    private final int defaultFast;
    private final int defaultSlow;
    private final int defaultSignal;
    private final int fetchDays;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public GetMacdTool(MarketDataService service, ResearchDefaults d) {
        this(service, d.macdFast(), d.macdSlow(), d.macdSignal(), d.fetchDays());
    }

    // Test ctor
    GetMacdTool(MarketDataService service, int defaultFast, int defaultSlow, int defaultSignal, int fetchDays) {
        this.service = service;
        this.defaultFast = defaultFast;
        this.defaultSlow = defaultSlow;
        this.defaultSignal = defaultSignal;
        this.fetchDays = fetchDays;
    }

    public String name() { return "get_macd"; }
    public String description() {
        return "Moving Average Convergence Divergence (MACD) for a symbol. Defaults "
             + defaultFast + "/" + defaultSlow + "/" + defaultSignal + ".";
    }

    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("symbol").put("type", "string").put("description", "Ticker symbol");
        props.putObject("fast").put("type", "integer").put("description", "fast EMA period (default " + defaultFast + ")");
        props.putObject("slow").put("type", "integer").put("description", "slow EMA period (default " + defaultSlow + ")");
        props.putObject("signal").put("type", "integer").put("description", "signal EMA period (default " + defaultSignal + ")");
        schema.putArray("required").add("symbol");
        return schema;
    }

    public ToolResult call(JsonNode args) {
        if (args == null || !args.hasNonNull("symbol")) return ToolResult.unavailable("no symbol provided");
        String symbol = args.get("symbol").asString();
        int fast = intArg(args, "fast", defaultFast);
        int slow = intArg(args, "slow", defaultSlow);
        int signalPeriod = intArg(args, "signal", defaultSignal);
        if (fast <= 0 || slow <= 0 || signalPeriod <= 0) return ToolResult.unavailable("invalid period");

        List<OhlcBar> bars;
        try { bars = service.ohlc(symbol, fetchDays); }
        catch (MarketDataException e) { return ToolResult.unavailable(e.getMessage()); }

        int minBars = slow + signalPeriod;
        if (bars.size() < minBars) return ToolResult.unavailable("insufficient history for macd");
        BarSeries series = Ta4jBars.toSeries(bars);
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        MACDIndicator macd = new MACDIndicator(close, fast, slow);
        EMAIndicator signal = new EMAIndicator(macd, signalPeriod);
        Num macdV = macd.getValue(series.getEndIndex());
        Num signalV = signal.getValue(series.getEndIndex());
        if (macdV.isNaN() || signalV.isNaN()) return ToolResult.unavailable("insufficient history for macd");

        BigDecimal macdBd = Ta4jBars.toBd(macdV, 4);
        BigDecimal signalBd = Ta4jBars.toBd(signalV, 4);

        ObjectNode out = mapper.createObjectNode();
        out.put("symbol", symbol);
        out.put("macd", macdBd);
        out.put("signal", signalBd);
        out.put("histogram", macdBd.subtract(signalBd));
        out.put("available", true);
        return ToolResult.ok(out);
    }

    private static int intArg(JsonNode args, String field, int fallback) {
        return (args.has(field) && !args.get(field).isNull()) ? args.get(field).asInt() : fallback;
    }
}
