package com.mobile.standalone.service.analysis;
import java.util.Map;
import java.util.Set;
/** Result availability does not depend on callback acknowledgement. */
final class AnalysisDeliveryStatus {
    private AnalysisDeliveryStatus() {}
    static Map<String,Object> describe(String id, String status) {
        boolean ready="SUCCEEDED".equals(status);
        boolean terminal=Set.of("SUCCEEDED","FAILED","CANCELLED","BLOCKED").contains(status);
        String path="/api/open/v1/analysis-jobs/"+id;
        return Map.of("resultReady",ready,"terminal",terminal,"statusUrl",path,
                "resultUrl",ready?path+"/result":"","pollAfterSeconds",terminal?0:5);
    }
}
