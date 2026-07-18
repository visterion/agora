package de.visterion.agora.fetch.finnhub;

import de.visterion.agora.research.fundamentals.YahooCrumbClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Maps Yahoo {@code quoteSummary} responses (via {@link YahooCrumbClient}) to the
 *  existing {@link Recommendation} / {@link Profile} shapes, so Yahoo can serve as a
 *  fallback company-data source alongside Finnhub. */
@Component
public class YahooCompanyDataSource {

    private final YahooCrumbClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public YahooCompanyDataSource(YahooCrumbClient client) {
        this.client = client;
    }

    public List<Recommendation> recommendations(String symbol) {
        JsonNode trend = client.quoteSummary(symbol, "recommendationTrend")
                .path("quoteSummary").path("result").path(0)
                .path("recommendationTrend").path("trend");
        if (!trend.isArray() || trend.isEmpty()) return List.of();
        List<Recommendation> out = new ArrayList<>();
        for (JsonNode n : trend) {
            out.add(new Recommendation(
                    n.path("period").asString(""),
                    n.path("strongBuy").asInt(0),
                    n.path("buy").asInt(0),
                    n.path("hold").asInt(0),
                    n.path("sell").asInt(0),
                    n.path("strongSell").asInt(0)));
        }
        out.sort(Comparator.comparingInt(r -> monthMagnitude(r.period())));
        return out;
    }

    private static int monthMagnitude(String period) {
        if (period == null || period.isBlank()) return Integer.MAX_VALUE;
        try {
            String digits = period.startsWith("-") ? period.substring(1) : period;
            if (!digits.endsWith("m")) return Integer.MAX_VALUE;
            return Integer.parseInt(digits.substring(0, digits.length() - 1));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    public Profile profile(String symbol) {
        JsonNode ap = client.quoteSummary(symbol, "assetProfile")
                .path("quoteSummary").path("result").path(0).path("assetProfile");
        ObjectNode out = mapper.createObjectNode();
        if (ap.isObject() && !ap.isEmpty()) {
            copyIfPresent(ap, "sector", out, "finnhubIndustry");
            copyIfPresent(ap, "sector", out, "sector");
            copyIfPresent(ap, "industry", out, "industry");
            copyIfPresent(ap, "country", out, "country");
        }
        return new Profile(symbol, out);
    }

    private static void copyIfPresent(JsonNode source, String sourceField, ObjectNode out, String targetField) {
        String value = source.path(sourceField).asString("");
        if (!value.isBlank()) out.put(targetField, value);
    }
}
