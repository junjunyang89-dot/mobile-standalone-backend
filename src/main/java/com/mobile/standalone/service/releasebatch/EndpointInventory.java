package com.mobile.standalone.service.releasebatch;

import com.mobile.standalone.service.apichange.ApiEndpoint;
import com.mobile.standalone.service.apichange.JavaApiSourceParser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 后端待测代码里的全部接口。前端新写了一个调用，要知道它对得上哪个服务的哪个接口——
 * 只看本次改了的接口不够，前端经常是新调一个后端早就有的接口。
 */
public final class EndpointInventory {

    private EndpointInventory() {
    }

    public static List<ApiEndpoint> scan(Path root) throws IOException {
        List<ApiEndpoint> out = new ArrayList<>();
        for (String[] f : CallSiteScanner.listFiles(root)) {
            if (!f[0].endsWith(".java") || f[0].contains("src/test/")) {
                continue;
            }
            String src = CallSiteScanner.read(Path.of(f[1]));
            if (JavaApiSourceParser.looksLikeController(src)) {
                out.addAll(JavaApiSourceParser.parseEndpoints(src));
            }
        }
        return out;
    }
}
