package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.data.MarketDataService;
import de.visterion.agora.data.OhlcBar;
import de.visterion.agora.research.IndicatorService;
import de.visterion.agora.research.Indicators;
import de.visterion.agora.research.ResearchDefaults;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;

/**
 * Neutral R-framework: risk unit and R-multiple price levels from a price and a stop level.
 * The stop level may be supplied or derived from ATR (price - atrMultiple * ATR).
 * Pure level arithmetic — no order-management or sizing coupling (that stays in the consumer).
 */
@Component
public class GetRFrameworkTool implements AgoraTool {

    private static final MathContext MC = MathContext.DECIMAL64;

    private final MarketDataService service;
    private final IndicatorService indicators;
    private final BigDecimal defaultAtrMultiple;
    private final List<BigDecimal> defaultRMultiples;
    private final int fetchDays;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public GetRFrameworkTool(MarketDataService service, IndicatorService indicators, ResearchDefaults d) {
        this(service, indicators, d.rAtrMultiple(), d.rMultiples(), d.fetchDays());
    }

    // Test ctor
    GetRFrameworkTool(MarketDataService service, IndicatorService indicators,
                      BigDecimal defaultAtrMultiple, List<BigDecimal> defaultRMultiples, int fetchDays) {
        this.service = service;
        this.indicators = indicators;
        this.defaultAtrMultiple = defaultAtrMultiple;
        this.defaultRMultiples = defaultRMultiples;
        this.fetchDays = fetchDays;
    }

    public String name() { return "get_r_framework"; }
    public String description() {
        return "Risk unit and R-multiple price levels for a symbol. stopLevel is supplied or derived "
             + "from ATR (price - atrMultiple*ATR). Returns riskPerUnit and target levels at each rMultiple."
             + " The derived stop uses the configured ATR period (agora.research.atr-period).";
    }

    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("symbol").put("type", "string").put("description", "Ticker symbol");
        props.putObject("stopLevel").put("type", "number").put("description", "explicit stop price (else derived from ATR)");
        props.putObject("atrMultiple").put("type", "number").put("description", "ATR multiple for derived stop (default " + defaultAtrMultiple + ")");
        props.putObject("direction").put("type", "string").put("description", "long (default) or short");
        ObjectNode rm = props.putObject("rMultiples");
        rm.put("type", "array").put("description", "R multiples for target levels (default " + defaultRMultiples + ")");
        rm.putObject("items").put("type", "number");
        schema.putArray("required").add("symbol");
        return schema;
    }

    public ToolResult call(JsonNode args) {
        if (args == null || !args.hasNonNull("symbol")) return ToolResult.unavailable("no symbol provided");
        String symbol = args.get("symbol").asString();

        BigDecimal atrMultiple = (args.has("atrMultiple") && !args.get("atrMultiple").isNull())
                ? new BigDecimal(args.get("atrMultiple").asString()) : defaultAtrMultiple;
        List<BigDecimal> rMultiples = defaultRMultiples;
        if (args.has("rMultiples") && args.get("rMultiples").isArray()) {
            List<BigDecimal> parsed = new java.util.ArrayList<>();
            for (JsonNode n : args.get("rMultiples")) parsed.add(new BigDecimal(n.asString()));
            if (!parsed.isEmpty()) rMultiples = parsed;
        }

        List<OhlcBar> bars;
        try { bars = service.ohlc(symbol, fetchDays); }
        catch (MarketDataException e) { return ToolResult.unavailable(e.getMessage()); }

        Indicators ind = indicators.compute(bars);
        if (ind.currentClose() == null) return ToolResult.unavailable("no price for " + symbol);
        BigDecimal price = ind.currentClose();

        String direction = args.hasNonNull("direction") ? args.get("direction").asString() : "long";
        boolean isShort = "short".equalsIgnoreCase(direction);

        BigDecimal stopLevel;
        if (args.has("stopLevel") && !args.get("stopLevel").isNull()) {
            stopLevel = new BigDecimal(args.get("stopLevel").asString());
            if (!isShort && stopLevel.compareTo(price) >= 0)
                return ToolResult.unavailable("stopLevel must be below price for a long");
            if (isShort && stopLevel.compareTo(price) <= 0)
                return ToolResult.unavailable("stopLevel must be above price for a short");
        } else if (ind.atrAvailable()) {
            BigDecimal delta = atrMultiple.multiply(ind.atr(), MC);
            stopLevel = isShort ? price.add(delta) : price.subtract(delta);
        } else {
            return ToolResult.unavailable("insufficient history to derive stop and no stopLevel provided");
        }

        BigDecimal riskPerUnit = isShort ? stopLevel.subtract(price) : price.subtract(stopLevel);

        ObjectNode out = mapper.createObjectNode();
        out.put("symbol", symbol);
        out.put("direction", isShort ? "short" : "long");
        out.put("price", price);
        out.put("stopLevel", stopLevel);
        out.put("riskPerUnit", riskPerUnit);
        ArrayNode targets = out.putArray("targets");
        for (BigDecimal m : rMultiples) {
            ObjectNode t = targets.addObject();
            t.put("rMultiple", m);
            t.put("level", isShort ? price.subtract(m.multiply(riskPerUnit, MC))
                                   : price.add(m.multiply(riskPerUnit, MC)));
        }
        out.put("available", true);
        return ToolResult.ok(out);
    }
}
