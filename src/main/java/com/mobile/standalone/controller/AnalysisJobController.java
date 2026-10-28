package com.mobile.standalone.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.mobile.standalone.auth.*;
import com.mobile.standalone.service.analysis.AnalysisJobService;
import com.mobile.standalone.service.analysis.WikiRequirementIngestService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/analysis-jobs")
@RequiredArgsConstructor
public class AnalysisJobController {
    private final AnalysisJobService jobs;
    private final com.mobile.standalone.service.analysis.AnalysisPreparationService preparation;
    private final WikiRequirementIngestService wiki;
    private final com.mobile.standalone.service.analysis.AnalysisBrowserService browser;
    private void requireBrowserAdmin(HttpServletRequest request) {
        Object actor=request.getAttribute(AuthFilter.ATTR_USER);
        if(!(actor instanceof PlatformUser user)||!user.roleEnum().satisfies(PlatformRole.ADMIN))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Local browser access requires administrator role");
    }
    @PostMapping("/browser/document/open") public Object openDocument(HttpServletRequest request,@RequestBody JsonNode body){requireBrowserAdmin(request);return browser.openDocument(body.path("sessionId").asText(),body.path("requirementKey").asText(),body.path("materialId").asText(),body.path("url").asText());}
    @PostMapping("/browser/document/capture") public Object captureDocument(HttpServletRequest request,@RequestBody JsonNode body){requireBrowserAdmin(request);return browser.captureDocument(body.path("sessionId").asText(),body.path("requirementKey").asText(),body.path("materialId").asText());}
    @PostMapping("/browser/reparse") public Object reparseBrowser(HttpServletRequest request,@RequestBody JsonNode body){requireBrowserAdmin(request);return browser.reparse(body);}
    @PostMapping("/browser/open") public Object openBrowser(HttpServletRequest request,@RequestBody JsonNode body){requireBrowserAdmin(request);return browser.open(body.path("requirementKey").asText());}
    @PostMapping("/browser/capture") public Object captureBrowser(HttpServletRequest request,@RequestBody JsonNode body){requireBrowserAdmin(request);return browser.capture(body.path("sessionId").asText(),body.path("requirementKey").asText());}
    @PostMapping("/prepare") public Object prepare(@RequestBody JsonNode body){return preparation.start("testforge",body);}
    @GetMapping("/preparations/{id}") public Object prepared(@PathVariable String id){return preparation.get("testforge",id);}
    @GetMapping public Object list(){return jobs.list(null,null);}
    @GetMapping("/config") public Object config(){return jobs.config();}
    @GetMapping("/wiki-preview") public Object wikiPreview(@RequestParam("page") String page){
        try { return wiki.preview(page); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
        catch (IllegalStateException e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage()); }
    }
    @GetMapping("/{id}") public Object detail(@PathVariable String id){return jobs.detail(null,id);}
    @GetMapping("/{id}/result") public Object result(@PathVariable String id){return jobs.result(null,id);}
    @PostMapping public Object create(@RequestBody JsonNode body){return jobs.create("testforge",body);}
    @PostMapping("/{id}/cancel") public Object cancel(@PathVariable String id){return jobs.cancel(null,id);}
    @PostMapping("/{id}/supplement-sources") public Object supplementSources(HttpServletRequest request,@PathVariable String id,@RequestBody JsonNode body){requireBrowserAdmin(request);return jobs.supplementSources("testforge",id,body);}
    @PostMapping("/{id}/retry") public Object retry(@PathVariable String id){return jobs.retry(id);}
    @PostMapping("/{id}/retry-callback") public Object retryCallback(@PathVariable String id){return jobs.retryCallback(id);}
    @PostMapping("/{id}/result-revisions") public Object publishRevision(HttpServletRequest request,@PathVariable String id,@RequestBody JsonNode result){
        Object actor=request.getAttribute(AuthFilter.ATTR_USER);
        if(!(actor instanceof PlatformUser user)||!user.roleEnum().satisfies(PlatformRole.ADMIN))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"补充并发布分析结果需要管理员账号");
        return jobs.publishResultRevision(id,result);
    }
    // Importing an externally produced result can publish it to TestForge: require an administrator.
    @PostMapping("/{id}/result") public Object importResult(HttpServletRequest request,@PathVariable String id,@RequestBody JsonNode result){
        Object actor=request.getAttribute(AuthFilter.ATTR_USER);
        if(!(actor instanceof PlatformUser user)||!user.roleEnum().satisfies(PlatformRole.ADMIN))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"导入并发布分析结果需要管理员账号");
        return jobs.importResult(id,result);
    }
}
