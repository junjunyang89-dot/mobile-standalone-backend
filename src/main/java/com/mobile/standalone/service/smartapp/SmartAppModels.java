package com.mobile.standalone.service.smartapp;

import java.util.*;

/** Portable script contract. Targets are resolved from the live hierarchy, never the element catalog. */
public final class SmartAppModels {
    private SmartAppModels() {}
    public record Target(String text, String contains, String id, String description, String role,
                         String withinText, Integer index) {}
    public record Step(String name, String action, Target target, String value, String saveAs,
                       Integer timeoutSeconds, String direction, String effect) {}
    public record Script(String id, String name, String platform, String packageName,
                         Map<String, String> variables, List<Step> steps, String updatedAt) {}
    public record Start(String deviceId, Map<String, String> variables, boolean allowWrites) {}
    public record Node(String path, String text, String id, String description, String type,
                       boolean editable, int left, int top, int right, int bottom) {
        public boolean bounded() { return right > left && bottom > top; }
    }
    public record Event(int index, String name, String action, String status, String message,
                        String evidence, String startedAt, long durationMs) {}
    public record Run(String id, String scriptId, String scriptName, String deviceId, String platform,
                      String status, String message, String startedAt, String finishedAt,
                      List<Event> events) {}
    public record Evidence(String source, List<Node> nodes, String image, String warning) {}
}
