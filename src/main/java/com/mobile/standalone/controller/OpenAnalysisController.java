package com.mobile.standalone.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.mobile.standalone.auth.OpenApiAuth;
import com.mobile.standalone.service.analysis.AnalysisJobService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/open/v1/analysis-jobs")
@RequiredArgsConstructor
public class OpenAnalysisController {
    private final AnalysisJobService jobs;
    private final com.mobile.standalone.service.analysis.AnalysisPreparationService preparation;
    private String client(HttpServletRequest request) {
        Object value=request.getAttribute(OpenApiAuth.ATTR_CLIENT);
        if(value==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"缺少开放接口调用方身份");
        jobs.allowClient(value.toString());return value.toString();
    }
    @PostMapping("/prepare") public ResponseEntity<?> prepare(HttpServletRequest req,@RequestBody JsonNode body){return ResponseEntity.accepted().body(preparation.start(client(req),body));}
    @GetMapping("/preparations/{id}") public Object prepared(HttpServletRequest req,@PathVariable String id){return preparation.get(client(req),id);}
    @PostMapping public ResponseEntity<?> create(HttpServletRequest req,@RequestBody JsonNode body){return ResponseEntity.status(202).body(jobs.create(client(req),body));}
    @GetMapping public Object list(HttpServletRequest req,@RequestParam(required=false)String externalId){return jobs.list(client(req),externalId);}
    @PostMapping("/{id}/supplement-sources") public Object supplement(HttpServletRequest req,@PathVariable String id,@RequestBody JsonNode body){return jobs.supplementSources(client(req),id,body);}
    @GetMapping("/{id}") public Object detail(HttpServletRequest req,@PathVariable String id){return jobs.detail(client(req),id);}
    @GetMapping("/{id}/result") public Object result(HttpServletRequest req,@PathVariable String id){return jobs.result(client(req),id);}
    @PostMapping("/{id}/retry-callback") public Object retryCallback(HttpServletRequest req,@PathVariable String id){return jobs.retryCallback(client(req),id);}
    @PostMapping("/{id}/cancel") public Object cancel(HttpServletRequest req,@PathVariable String id){return jobs.cancel(client(req),id);}
}
