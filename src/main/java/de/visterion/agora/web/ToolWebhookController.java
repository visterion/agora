package de.visterion.agora.web;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import de.visterion.agora.tool.ToolNotFoundException;
import de.visterion.agora.tool.ToolRegistry;
import de.visterion.agora.tool.ToolResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Vistierie-compatible front-door: each tool is reachable at POST /tools/{name}.
 *  A ToolDef.webhook_url pointing here makes the tool callable by any Vistierie
 *  agent — no MCP needed on that path. Response envelope: {"output": {...}}. */
@RestController
public class ToolWebhookController {

    private final ToolRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    public ToolWebhookController(ToolRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/tools/{name}")
    public ResponseEntity<ObjectNode> call(@PathVariable String name,
                                           @RequestBody(required = false) JsonNode args) {
        ToolResult result = registry.invoke(name, args == null ? mapper.createObjectNode() : args);
        ObjectNode envelope = mapper.createObjectNode();
        if (result.available()) {
            envelope.set("output", result.output());
        } else {
            ObjectNode out = envelope.putObject("output");
            out.put("available", false);
            out.put("error", result.error());
        }
        return ResponseEntity.ok(envelope);
    }

    @ExceptionHandler(ToolNotFoundException.class)
    public ResponseEntity<Void> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
}
