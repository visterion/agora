package de.visterion.agora.tool;

public class ToolNotFoundException extends RuntimeException {
    public ToolNotFoundException(String name) { super("unknown tool: " + name); }
}
