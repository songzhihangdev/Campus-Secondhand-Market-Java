package com.campus.item.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "hm.es")
public class ElasticsearchProperties {
    private String host = "192.168.1.4";
    private Integer port = 9200;
    private String scheme = "http";
    private String indexName = "items";
}
