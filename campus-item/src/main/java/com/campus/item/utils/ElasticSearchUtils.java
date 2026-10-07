package com.campus.item.utils;

import cn.hutool.json.JSONUtil;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Elasticsearch 搜索工具类，简化搜索结果解析与高亮处理
 */
public class ElasticSearchUtils {

    /**
     * 获取命中的总条数
     */
    public static long getTotal(SearchResponse response) {
        return response.getHits().getTotalHits().value;
    }

    /**
     * 解析命中的文档为指定类型的Bean列表
     */
    public static <T> List<T> parseHits(SearchResponse response, Class<T> clazz) {
        return parseHits(response, clazz, null, null);
    }

    /**
     * 解析命中的文档为指定类型的Bean列表，并处理高亮字段
     *
     * @param response        搜索响应
     * @param clazz           文档类型
     * @param highlightField  高亮字段名
     * @param highlightSetter 高亮结果回填方法
     */
    public static <T> List<T> parseHits(SearchResponse response, Class<T> clazz,
                                        String highlightField, BiConsumer<T, String> highlightSetter) {
        List<T> list = new ArrayList<>();
        SearchHits searchHits = response.getHits();
        for (SearchHit hit : searchHits.getHits()) {
            // 获取source并反序列化为Bean
            T bean = JSONUtil.toBean(hit.getSourceAsString(), clazz);
            // 处理高亮结果；并不是每个 hit 都会返回该字段（字段缺失/未匹配高亮），必须判空
            if (highlightField != null && highlightSetter != null) {
                HighlightField field = hit.getHighlightFields() == null
                        ? null : hit.getHighlightFields().get(highlightField);
                if (field != null && field.getFragments() != null && field.getFragments().length > 0) {
                    highlightSetter.accept(bean, field.getFragments()[0].string());
                }
            }
            list.add(bean);
        }
        return list;
    }
}
