package com.mobile.standalone.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.*;

@Data
@Component
@ConfigurationProperties(prefix = "analysis-bridge")
public class AnalysisBridgeProperties {
    private String figmaToken = "";
    // Only explicit operator mappings participate in JIRA project discovery.
    private Map<String,String> repositoryAliases = new LinkedHashMap<>();
    private Map<String,String> repositoryGroups = new LinkedHashMap<>();
    private Map<String,String> repositoryBaselines = new LinkedHashMap<>();
    private boolean enabled = false;
    private String node = "node";
    private String script = "scripts/codex-analysis/run.mjs";
    private String workspace = "data/analysis-jobs";
    private int timeoutSeconds = 1800;
    private int callbackMaxAttempts = 5;
    private List<String> allowedClients = new ArrayList<>(List.of("testforge"));
    // Maps project IDs to server-local repository roots. Never take a host path from the task request.
    private Map<String, String> repositories = new LinkedHashMap<>();
    // Optional GitLab clone URLs keyed by the same projectId; used to clone/fetch before analysis.
    private Map<String, String> repositoryUrls = new LinkedHashMap<>();
    // Callback destinations are configured by the operator, not supplied by callers.
    private Map<String, String> callbacks = new LinkedHashMap<>();
}
