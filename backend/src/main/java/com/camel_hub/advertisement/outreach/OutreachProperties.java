package com.camel_hub.advertisement.outreach;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.outreach")
public class OutreachProperties {
    private String productionHoldReason = "";
    public String getProductionHoldReason() { return productionHoldReason; }
    public void setProductionHoldReason(String value) { productionHoldReason = value == null ? "" : value.strip(); }
}
