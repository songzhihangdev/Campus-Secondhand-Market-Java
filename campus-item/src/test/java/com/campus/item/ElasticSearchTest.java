package com.campus.item;

import cn.hutool.json.JSONUtil;
import com.campus.item.domain.po.ItemDoc;
import org.apache.http.HttpHost;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;
import org.elasticsearch.search.sort.SortOrder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@SpringBootTest(properties = "spring.profiles.active=local")
@EnabledIfEnvironmentVariable(named = "ES_TEST", matches = ".+")
public class ElasticSearchTest {
    private RestHighLevelClient client;

    @Test
    void testMathchAll() throws IOException {
        //创建reques对象
        SearchRequest request = new SearchRequest("items");
        //准备请求参数
        request.source().query(QueryBuilders.matchAllQuery());

        //发送请求
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);
        praseResponse(response);
    }

    private static void praseResponse(SearchResponse response) {
        //解析结果
        SearchHits searchHits = response.getHits();
        //4.1获取总条数
        long total = searchHits.getTotalHits().value;
        //4.2命中的数据
        SearchHit[] hits = searchHits.getHits();

        for (SearchHit hit : hits) {
            //4.2.1获取source
            String json = hit.getSourceAsString();
            ItemDoc bean = JSONUtil.toBean(json, ItemDoc.class);
            //处理高亮结果
            Map<String, HighlightField> hf = hit.getHighlightFields();
            //根据高亮字段名获取高亮结果；并不是每个 hit 都会返回该字段（字段缺失/未匹配高亮），必须判空
            HighlightField highlightField = (hf != null) ? hf.get("name") : null;
            if (highlightField != null && highlightField.getFragments() != null
                    && highlightField.getFragments().length > 0) {
                bean.setName(highlightField.getFragments()[0].string());
            }

            System.out.println(bean);
        }
    }

    @Test
    void testSearch() throws IOException {
        //创建reques对象
        SearchRequest request = new SearchRequest("items");
        //准备请求参数
        request.source().query(QueryBuilders.boolQuery()
                .must(QueryBuilders.matchQuery("name", "脱脂牛奶"))
                .filter(QueryBuilders.rangeQuery("price").lt(30000))
                );

        //发送请求
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);
        praseResponse(response);
    }

    @Test
    void testSortAndPage() throws IOException {
        int pageNo = 1;
        int pageSize = 5;
        //创建reques对象
        SearchRequest request = new SearchRequest("items");
        //准备请求参数
        request.source().query(QueryBuilders.matchAllQuery());

        //1.分页
        request.source().from((pageNo-1)*pageSize).size(pageSize);

        //2.排序
        request.source().sort("sold", SortOrder.DESC).sort("price",SortOrder.ASC);
        //发送请求
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);
        praseResponse(response);
    }

    @AfterEach
    void tearDown() {
        try {
            client.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @BeforeEach
    public void setUp() {
        client  = new RestHighLevelClient(RestClient.builder(new HttpHost("192.168.1.4", 9200, "http")));
    }


    @Test
    void testHighLight() throws IOException {
        int pageNo = 1;
        int pageSize = 5;
        //创建reques对象
        SearchRequest request = new SearchRequest("items");
        //准备请求参数
        request.source().query(QueryBuilders.matchQuery("name", "脱脂牛奶"));

        request.source().highlighter(
                new HighlightBuilder()
                        .field("name")
                        .requireFieldMatch(false));


        //发送请求
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);
        praseResponse(response);
    }

    @Test
    void testAgg() throws IOException {
        SearchRequest request = new SearchRequest("items");
        request.source().size(0);
        request.source().aggregation(AggregationBuilders.terms("brandAgg").field("brand.keyword")).size(10);
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);
        //4.根据聚合名称获取聚合结果
        Terms brandAgg = response.getAggregations().get("brandAgg");
        //5.获取桶
        List<? extends Terms.Bucket> buckets = brandAgg.getBuckets();
        for (Terms.Bucket bucket : buckets) {
            //6.获取桶中的key
            String key = bucket.getKeyAsString();
            //7.获取桶中的文档数量
            long docCount = bucket.getDocCount();
            System.out.println(key + ":" + docCount);
        }
    }


}
