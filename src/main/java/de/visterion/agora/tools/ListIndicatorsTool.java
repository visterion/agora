package de.visterion.agora.tools;

import de.visterion.agora.research.IndicatorDef;
import de.visterion.agora.research.IndicatorRegistry;
import de.visterion.agora.research.ParamDef;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Discovery tool for the indicator catalog: names, descriptions, params with
 *  defaults/bounds, input arity and output fields — everything a consumer needs
 *  to build get_indicators specs. */
@Component
public class ListIndicatorsTool implements AgoraTool {

    private final IndicatorRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    public ListIndicatorsTool(IndicatorRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() { return "list_indicators"; }

    @Override
    public String description() {
        return "Lists the technical-indicator catalog (names, params with defaults, outputs). "
             + "Use these names in get_indicators specs. Optional: name (substring filter).";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("name").put("type", "string")
             .put("description", "substring filter on indicator names");
        return schema;
    }

    @Override
    public ToolResult call(JsonNode args) {
        String filter = (args != null && args.hasNonNull("name"))
                ? args.get("name").asString() : null;

        ObjectNode out = mapper.createObjectNode();
        ArrayNode arr = out.putArray("indicators");
        for (IndicatorDef def : registry.all()) {
            if (filter != null && !def.name().contains(filter)) continue;
            ObjectNode e = arr.addObject();
            e.put("name", def.name());
            e.put("description", def.description());
            ArrayNode params = e.putArray("params");
            for (ParamDef p : def.params()) {
                ObjectNode pn = params.addObject();
                pn.put("name", p.name());
                pn.put("type", p.type() == ParamDef.Type.INT ? "int" : "decimal");
                pn.put("default", p.defaultValue());
                if (p.min() != null) pn.put("min", p.min());
                if (p.max() != null) pn.put("max", p.max());
            }
            e.put("inputs", def.inputs());
            ArrayNode outs = e.putArray("outputs");
            def.outputs().forEach(outs::add);
        }
        out.put("count", arr.size());
        return ToolResult.ok(out);
    }
}
