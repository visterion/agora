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
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.num.Num;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.List;

@Component
public class GetBollingerTool implements AgoraTool {

    private final MarketDataService service;
    private final int defaultPeriod;
    private final BigDecimal defaultK;
    private final int fetchDays;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetBollingerTool(MarketDataService service, ResearchDefaults d) {
        this(service, d.bollingerPeriod(), d.bollingerK(), d.fetchDays());
    }

    // Test ctor
    GetBollingerTool(MarketDataService service, int defaultPeriod, BigDecimal defaultK, int fetchDays) {
        this.service = service;
        this.defaultPeriod = defaultPeriod;
        this.defaultK = defaultK;
        this.fetchDays = fetchDays;
    }

    public String name() { return "get_bollinger"; }
    public String description() {
        return "Bollinger Bands (upper/middle/lower) for a symbol. Default period "
             + defaultPeriod + ", k=" + defaultK.toPlainString() + ".";
    }

    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("symbol").put("type", "string").put("description", "Ticker symbol");
        props.putObject("period").put("type", "integer").put("description", "look-back period (default " + defaultPeriod + ")");
        props.putObject("k").put("type", "number").put("description", "standard-deviation multiple (default " + defaultK.toPlainString() + ")");
        schema.putArray("required").add("symbol");
        return schema;
    }

    public ToolResult call(JsonNode args) {
        if (args == null || !args.hasNonNull("symbol")) return ToolResult.unavailable("no symbol provided");
        String symbol = args.get("symbol").asString();
        int period = (args.has("period") && !args.get("period").isNull()) ? args.get("period").asInt() : defaultPeriod;
        if (period <= 0) return ToolResult.unavailable("invalid period");
        BigDecimal k = (args.has("k") && !args.get("k").isNull()) ? new BigDecimal(args.get("k").asString()) : defaultK;

        List<OhlcBar> bars;
        try { bars = service.ohlc(symbol, fetchDays); }
        catch (MarketDataException e) { return ToolResult.unavailable(e.getMessage()); }

        if (bars.size() < period) return ToolResult.unavailable("insufficient history for bollinger");
        BarSeries series = Ta4jBars.toSeries(bars);
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        SMAIndicator sma = new SMAIndicator(close, period);
        BollingerBandsMiddleIndicator bbm = new BollingerBandsMiddleIndicator(sma);
        StandardDeviationIndicator sd = new StandardDeviationIndicator(close, period);
        Num kNum = series.numFactory().numOf(k);
        BollingerBandsUpperIndicator up = new BollingerBandsUpperIndicator(bbm, sd, kNum);
        BollingerBandsLowerIndicator low = new BollingerBandsLowerIndicator(bbm, sd, kNum);

        int end = series.getEndIndex();
        Num upperV = up.getValue(end);
        Num middleV = bbm.getValue(end);
        Num lowerV = low.getValue(end);
        if (upperV.isNaN() || middleV.isNaN() || lowerV.isNaN())
            return ToolResult.unavailable("insufficient history for bollinger");

        ObjectNode out = mapper.createObjectNode();
        out.put("symbol", symbol);
        out.put("upper", Ta4jBars.toBd(upperV, 4));
        out.put("middle", Ta4jBars.toBd(middleV, 4));
        out.put("lower", Ta4jBars.toBd(lowerV, 4));
        out.put("available", true);
        return ToolResult.ok(out);
    }
}
