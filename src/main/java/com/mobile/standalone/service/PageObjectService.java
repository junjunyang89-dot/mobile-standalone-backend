package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.entity.PageObject;
import com.mobile.standalone.mapper.PageObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 页面对象模型（POM）服务
 * 管理页面对象和元素定义。按 (platform, appId, pagePath) 做内存缓存，减少重复读表。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PageObjectService {

    private static final String CACHE_KEY_SEP = "|";
    /** 默认缓存 5 分钟，避免长时间占用内存且元素有更新时能较快生效 */
    private static final int DEFAULT_CACHE_TTL_SECONDS = 300;

    private final PageObjectMapper pageObjectMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.page-object-cache-ttl-seconds:" + DEFAULT_CACHE_TTL_SECONDS + "}")
    private int cacheTtlSeconds;

    /** key: platform|appId|pagePath, value: (pageObject, expireAtMillis) */
    private final ConcurrentHashMap<String, CacheEntry> findByPagePathCache = new ConcurrentHashMap<>();

    private static String cacheKey(String platform, String appId, String pagePath) {
        return (platform != null ? platform : "") + CACHE_KEY_SEP + (appId != null ? appId : "") + CACHE_KEY_SEP + (pagePath != null ? pagePath : "");
    }

    private static final class CacheEntry {
        final PageObject pageObject;
        final long expireAtMillis;

        CacheEntry(PageObject pageObject, long expireAtMillis) {
            this.pageObject = pageObject;
            this.expireAtMillis = expireAtMillis;
        }
    }
    
    public List<PageObject> getAllPageObjects() {
        return pageObjectMapper.findAll();
    }
    
    public PageObject getPageObjectById(String id) {
        PageObject pageObject = pageObjectMapper.findById(id);
        if (pageObject != null) {
            deserializeElements(pageObject);
        }
        return pageObject;
    }
    
    public List<PageObject> getPageObjectsByPlatform(PageObject.Platform platform) {
        return pageObjectMapper.findByPlatform(platform != null ? platform.name() : null);
    }
    
    public List<PageObject> getPageObjectsByAppId(String appId) {
        return pageObjectMapper.findByAppId(appId);
    }

    /**
     * 按应用 + 平台查询页面对象（便于前端按「安卓 / iOS」分别维护元素）
     */
    public List<PageObject> getPageObjectsByAppIdAndPlatform(String appId, PageObject.Platform platform) {
        if (platform == null) {
            return pageObjectMapper.findByAppId(appId);
        }
        List<PageObject> list = pageObjectMapper.findByAppIdAndPlatform(appId, platform.name());
        list.forEach(this::deserializeElements);
        return list;
    }

    /**
     * 按目录 / 应用 / 平台筛选。catalog 为空则不过滤目录。
     */
    public List<PageObject> listByFilters(PageObject.Catalog catalog, String appId, PageObject.Platform platform) {
        String catalogName = catalog != null ? catalog.name() : null;
        String platformName = platform != null ? platform.name() : null;
        String app = (appId != null && !appId.isBlank()) ? appId.trim() : null;
        List<PageObject> list = pageObjectMapper.findByFilters(catalogName, app, platformName);
        if (list != null) {
            list.forEach(this::deserializeElements);
        }
        return list != null ? list : List.of();
    }

    /**
     * 按平台 + 应用 + 页面路径查 POM。优先走内存缓存，未命中再查表并回填缓存，减少重复读表。
     * 配置 app.page-object-cache-ttl-seconds=0 时关闭缓存，每次都读表。
     */
    public PageObject findByPagePath(String platform, String appId, String pagePath) {
        if (cacheTtlSeconds > 0) {
            String key = cacheKey(platform, appId, pagePath);
            long now = System.currentTimeMillis();
            CacheEntry entry = findByPagePathCache.get(key);
            if (entry != null && entry.expireAtMillis > now) {
                return entry.pageObject;
            }
        }
        PageObject pageObject = pageObjectMapper.findByPagePath(platform, appId, pagePath);
        if (pageObject != null) {
            deserializeElements(pageObject);
            if (cacheTtlSeconds > 0) {
                String key = cacheKey(platform, appId, pagePath);
                findByPagePathCache.put(key, new CacheEntry(pageObject, System.currentTimeMillis() + cacheTtlSeconds * 1000L));
            }
        } else if (cacheTtlSeconds > 0) {
            findByPagePathCache.remove(cacheKey(platform, appId, pagePath));
        }
        return pageObject;
    }

    /** 使某 (platform, appId) 下所有页面缓存失效（增删改 POM 后调用） */
    public void invalidateCacheFor(String platform, String appId) {
        String prefix = (platform != null ? platform : "") + CACHE_KEY_SEP + (appId != null ? appId : "") + CACHE_KEY_SEP;
        findByPagePathCache.keySet().removeIf(k -> k.startsWith(prefix));
        log.debug("已失效 POM 缓存: platform={}, appId={}", platform, appId);
    }

    /** 清空全部 POM 缓存 */
    public void invalidateAllPageObjectCache() {
        findByPagePathCache.clear();
        log.debug("已清空 POM 缓存");
    }
    
    @Transactional
    public PageObject createPageObject(PageObject pageObject) {
        if (pageObject.getId() == null) {
            pageObject.setId(UUID.randomUUID().toString());
        }
        if (pageObject.getCatalog() == null) {
            pageObject.setCatalog(PageObject.Catalog.AUTOMATION);
        }
        LocalDateTime now = LocalDateTime.now();
        if (pageObject.getCreatedAt() == null) {
            pageObject.setCreatedAt(now);
        }
        if (pageObject.getUpdatedAt() == null) {
            pageObject.setUpdatedAt(now);
        }
        normalizeAndValidateElements(pageObject);
        serializeElements(pageObject);
        pageObjectMapper.insert(pageObject);
        invalidateCacheFor(pageObject.getPlatform() != null ? pageObject.getPlatform().name() : null, pageObject.getAppId());
        return pageObject;
    }
    
    @Transactional
    public PageObject updatePageObject(String id, PageObject pageObjectData) {
        PageObject pageObject = pageObjectMapper.findById(id);
        if (pageObject != null) {
            if (pageObjectData.getName() != null) pageObject.setName(pageObjectData.getName());
            if (pageObjectData.getDescription() != null) pageObject.setDescription(pageObjectData.getDescription());
            if (pageObjectData.getPagePath() != null) pageObject.setPagePath(pageObjectData.getPagePath());
            if (pageObjectData.getCatalog() != null) pageObject.setCatalog(pageObjectData.getCatalog());
            if (pageObjectData.getElements() != null) pageObject.setElements(pageObjectData.getElements());

            pageObject.setUpdatedAt(LocalDateTime.now());
            normalizeAndValidateElements(pageObject);
            serializeElements(pageObject);
            pageObjectMapper.update(pageObject);
            invalidateCacheFor(pageObject.getPlatform() != null ? pageObject.getPlatform().name() : null, pageObject.getAppId());
            return pageObject;
        }
        return null;
    }
    
    @Transactional
    public boolean deletePageObject(String id) {
        PageObject pageObject = pageObjectMapper.findById(id);
        if (pageObject != null) {
            pageObjectMapper.deleteById(id);
            invalidateCacheFor(pageObject.getPlatform() != null ? pageObject.getPlatform().name() : null, pageObject.getAppId());
            return true;
        }
        return false;
    }

    /**
     * 按元素删除：从指定页面对象中移除一个元素（不删页面本身）。
     * @param pageObjectId 页面对象 ID
     * @param elementKey 元素标识（elements Map 的 key）
     * @return 更新后的页面对象；若页面不存在或元素不存在则返回 null
     */
    @Transactional
    public PageObject removeElement(String pageObjectId, String elementKey) {
        PageObject po = getPageObjectById(pageObjectId);
        if (po == null) return null;
        Map<String, PageObject.PageElement> elements = po.getElements();
        if (elements == null || !elements.containsKey(elementKey)) return null;
        Map<String, PageObject.PageElement> newMap = new HashMap<>(elements);
        newMap.remove(elementKey);
        po.setElements(newMap);
        return updatePageObject(pageObjectId, po);
    }
    
    /**
     * 规范化并校验元素：每个元素必须带定位方式（selectorType）与定位值（selector）。
     * 允许类型：id / xpath / name / text / accessibilityId / accessibility-id / className / other
     */
    private void normalizeAndValidateElements(PageObject pageObject) {
        Map<String, PageObject.PageElement> elements = pageObject.getElements();
        if (elements == null || elements.isEmpty()) {
            return;
        }
        List<String> errors = new ArrayList<>();
        for (Map.Entry<String, PageObject.PageElement> e : elements.entrySet()) {
            String key = e.getKey();
            PageObject.PageElement el = e.getValue();
            if (el == null) {
                errors.add(key + "：元素定义为空");
                continue;
            }
            if (el.getName() == null || el.getName().isBlank()) {
                el.setName(key);
            }
            String type = el.getSelectorType() != null ? el.getSelectorType().trim() : "";
            if (type.isEmpty()) {
                errors.add(key + "：未指定定位方式 selectorType（如 xpath / id）");
            } else {
                String normalized = normalizeSelectorType(type);
                if (normalized == null) {
                    errors.add(key + "：不支持的定位方式「" + type + "」，请用 id/xpath/name/text/accessibilityId/className/other");
                } else {
                    el.setSelectorType(normalized);
                }
            }
            if (el.getSelector() == null || el.getSelector().isBlank()) {
                errors.add(key + "：定位值 selector 为空");
            } else {
                el.setSelector(el.getSelector().trim());
            }
            if (el.getTimeout() == null || el.getTimeout() < 0) {
                el.setTimeout(10);
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("页面元素校验失败：" + String.join("；", errors));
        }
    }

    /** 统一成前端/ElementResolver 常用写法；无法识别返回 null */
    private static String normalizeSelectorType(String raw) {
        String t = raw.trim().toLowerCase().replace('_', '-');
        return switch (t) {
            case "id", "resource-id", "resourceid" -> "id";
            case "xpath" -> "xpath";
            case "name" -> "name";
            case "text" -> "text";
            case "accessibilityid", "accessibility-id", "accessibility id" -> "accessibilityId";
            case "classname", "class-name", "class name" -> "className";
            case "other" -> "other";
            default -> null;
        };
    }

    /**
     * 序列化页面元素（Map -> JSON String）
     */
    private void serializeElements(PageObject pageObject) {
        // elements 由 MyBatis JsonMapTypeHandler 在 insert/update 时序列化，此处仅做校验
        if (pageObject.getElements() != null) {
            try {
                objectMapper.writeValueAsString(pageObject.getElements());
            } catch (Exception e) {
                log.error("序列化页面元素失败", e);
            }
        }
    }
    
    /**
     * 反序列化页面元素（JSON String -> Map）
     */
    private void deserializeElements(PageObject pageObject) {
        // 从数据库读取 JSON 字符串，转换为 Map
        // 需要在 Mapper XML 中处理
        // 这里假设 elements 字段在数据库中是 JSON 格式的字符串
    }
}
