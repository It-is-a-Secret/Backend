package com.blursome.blursome.auth.cookie;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.cookie")
public record CookieProperties(
    boolean secure,
    String sameSite
) {
}
