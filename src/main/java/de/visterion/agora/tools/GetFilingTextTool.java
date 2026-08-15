package de.visterion.agora.tools;

import de.visterion.agora.data.MarketDataException;
import de.visterion.agora.fetch.edgar.EdgarSearchService;
import de.visterion.agora.fetch.edgar.FilingTextExtractor;
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
             + "Input: the archive document url returned by search_filings. Optional "
             + "exhibit_type (e.g. EX-99.1) resolves and reads that named exhibit from the "
             + "filing's index page instead of the primary document, falling back to the "
             + "primary document when the exhibit is absent. Optional extract_mode (SECTION, "
             + "the default, or LEADING) controls where the returned text starts: SECTION seeks "
             + "a known summary heading, LEADING always starts at character 0 — use LEADING for "
             + "documents (like spin-off information statements) that state their terms before "
             + "any heading exists.";
    }

    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("url").put("type", "string")
                .put("description", "SEC archive document URL (from search_filings); required");
        props.putObject("exhibit_type").put("type", "string")
                .put("description", "Optional exhibit type to resolve via the filing's index page "
                        + "(e.g. EX-99.1); falls back to the primary document when absent");
        props.putObject("extract_mode").put("type", "string")
                .put("description", "Optional: SECTION (default) or LEADING; any other or "
                        + "unrecognised value falls back to SECTION");
        schema.putArray("required").add("url");
        return schema;
    }

    public ToolResult call(JsonNode args) {
        String url = args == null ? null : args.path("url").asString(null);
        if (url == null || url.isBlank()) return ToolResult.unavailable("url required");
        String exhibitType = args.path("exhibit_type").asString(null);
        String modeStr = args.path("extract_mode").asString(null);
        FilingTextExtractor.Mode mode = "LEADING".equalsIgnoreCase(modeStr)
                ? FilingTextExtractor.Mode.LEADING : FilingTextExtractor.Mode.SECTION;
        try {
            EdgarSearchService.FilingText ft = service.filingText(url, exhibitType, mode);
            ObjectNode out = mapper.createObjectNode();
            out.put("text", ft.text());
            out.put("section_found", ft.sectionFound());
            out.put("truncated", ft.truncated());
            out.put("char_count", ft.charCount());
            out.put("source_url", ft.sourceUrl());
            out.put("resolved_exhibit", ft.resolvedExhibit());
            return ToolResult.ok(out);
        } catch (MarketDataException e) {
            return ToolResult.unavailable(e.getMessage());
        }
    }
}
