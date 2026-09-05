package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * H5 页面目录项：列表展示 URL，点开后内嵌预览（后续可接 DOM 采集）。
 */
@Data
public class UiH5Page {
    private String id;
    private String name;
    private String url;
    private String appId;
    private String moduleTag;
    private String description;
    /** NONE | TOKEN_QUERY | TOKEN_COOKIE | MANUAL */
    private String authType;
    private String authKey;
    private String authToken;

    /**
     * Cookie 登录态：JSON 对象，名 → 值。
     * <p>
     * 有些活动页的登录态不在 query 上而在 Cookie 里，而且不止一个。实测国服战令这类页面
     * 要 {@code PVP_APP_TOKEN} + {@code PVP_APP_STEAMID} + {@code PVP_APP_USERID} 三个都在
     * 才认，只给 token 页面照样停在「去登录」——而且中间不报任何错，最难查。
     */
    private String authCookies;

    /**
     * Cookie 域；留空时按被测页面的 host 推。
     * <p>
     * 域不匹配的话浏览器会直接把 cookie 丢掉，表现同样是「填了还是未登录」且无报错。
     */
    private String cookieDomain;
    private Boolean enabled;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
