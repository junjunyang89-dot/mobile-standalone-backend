package com.mobile.standalone.controller;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestControllerAdvice(assignableTypes={AnalysisJobController.class,OpenAnalysisController.class})
public class AnalysisErrorAdvice {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> invalid(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",e.getReason()==null?"任务请求无效":e.getReason()));
    }
}
