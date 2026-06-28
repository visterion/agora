package de.visterion.agora.web;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import de.visterion.agora.tool.AgoraTool;
import de.visterion.agora.tool.ToolRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Plain-HTTP discovery mirroring MCP tools/list — handy for consumers that build
 *  Vistierie ToolDefs from Agora's catalog. */
@RestController
public class ToolCatalogController {

    private final ToolRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    public ToolCatalogController(ToolRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/tools")
    public ObjectNode list() {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode tools = root.putArray("tools");
        for (AgoraTool t : registry.all()) {
            ObjectNode entry = tools.addObject();
            entry.put("name", t.name());
            entry.put("description", t.description());
            entry.set("inputSchema", t.inputSchema());
        }
        return root;
    }
}
