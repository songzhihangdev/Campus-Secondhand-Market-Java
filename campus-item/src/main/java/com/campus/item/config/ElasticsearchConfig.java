package com.campus.item.config;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ElasticsearchConfig {

    @Bean
    public RestHighLevelClient restHighLevelClient(ElasticsearchProperties properties) {
        return new RestHighLevelClient(RestClient.builder(
                new HttpHost(properties.getHost(), properties.getPort(), properties.getScheme())));
    }
}
