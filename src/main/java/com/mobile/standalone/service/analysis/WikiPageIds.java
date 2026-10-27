package com.mobile.standalone.service.analysis;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WikiPageIds {
    private static final Pattern PAGE_ID = Pattern.compile("(?:pageId=|/pages/viewpage.action.*?pageId=)(\\d+)");
    private static final Pattern BARE = Pattern.compile("^\\d{3,}$");

    private WikiPageIds() {}

    static Optional<String> extract(String urlOrId) {
        if (urlOrId == null) return Optional.empty();
        String s = urlOrId.trim();
        if (s.isEmpty()) return Optional.empty();
        if (BARE.matcher(s).matches()) return Optional.of(s);
        Matcher m = PAGE_ID.matcher(s);
        if (m.find()) return Optional.of(m.group(1));
        return Optional.empty();
    }
}
