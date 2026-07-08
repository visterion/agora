package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.edgar.EdgarSearchService;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class GetFilingTextTool implements AgoraTool {

    private final EdgarSearchService service;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetFilingTextTool(EdgarSearchService service) { this.service = service; }

    public String name() { return "get_filing_text"; }

    public String description() {
        return "Fetch a SEC filing's primary document as cleaned text, extracting its "
             + "summary/term-sheet section when present, truncated to a budget. "
             + "Input: the archive document url returned by search_filings.";
    }

    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("url").put("type", "string")
                .put("description", "SEC archive document URL (from search_filings); required");
        schema.putArray("required").add("url");
        return schema;
    }

    public ToolResult call(JsonNode args) {
        String url = args == null ? null : args.path("url").asString(null);
        if (url == null || url.isBlank()) return ToolResult.unavailable("url required");
        try {
            EdgarSearchService.FilingText ft = service.filingText(url);
            ObjectNode out = mapper.createObjectNode();
            out.put("text", ft.text());
            out.put("section_found", ft.sectionFound());
            out.put("truncated", ft.truncated());
            out.put("char_count", ft.charCount());
            out.put("source_url", ft.sourceUrl());
            return ToolResult.ok(out);
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
