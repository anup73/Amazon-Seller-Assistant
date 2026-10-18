package com.agent.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "amazon.sp-api")
public class AmazonSpApiProperties {

    @NotBlank
    private String endpoint;

    @NotBlank
    private String marketplaceId;

    private String sellerId;

    @NotBlank
    private String lwaClientId;

    @NotBlank
    private String lwaClientSecret;

    @NotBlank
    private String lwaRefreshToken;
    private String timeZone = "Asia/Kolkata";

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getMarketplaceId() {
        return marketplaceId;
    }

    public String getSellerId() {
        return sellerId;
    }

    public void setSellerId(String sellerId) {
        this.sellerId = sellerId;
    }

    public void setMarketplaceId(String marketplaceId) {
        this.marketplaceId = marketplaceId;
    }

    public String getLwaClientId() {
        return lwaClientId;
    }

    public void setLwaClientId(String lwaClientId) {
        this.lwaClientId = lwaClientId;
    }

    public String getLwaClientSecret() {
        return lwaClientSecret;
    }

    public void setLwaClientSecret(String lwaClientSecret) {
        this.lwaClientSecret = lwaClientSecret;
    }

    public String getLwaRefreshToken() {
        return lwaRefreshToken;
    }

    public void setLwaRefreshToken(String lwaRefreshToken) {
        this.lwaRefreshToken = lwaRefreshToken;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

}
