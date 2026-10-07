package com.campus.item;

import org.apache.http.HttpHost;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.common.xcontent.XContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;



@EnabledIfEnvironmentVariable(named = "ES_TEST", matches = ".+")
public class ElasticTest {

    private RestHighLevelClient client;

    @Test
    public void testIndex() throws IOException {
        System.out.println("1");
    }

    @BeforeEach
    public void setUp() {
        client  = new RestHighLevelClient(RestClient.builder(new HttpHost("192.168.1.4", 9200, "http")));
    }

    @Test
    public void testCreateIndex() throws IOException {
        //1.准备request对象
        CreateIndexRequest request = new CreateIndexRequest("items");
        //2.执行
        request.source(MAPPING_TEMPLATE, XContentType.JSON);
        //3.应答
        client.indices().create(request, RequestOptions.DEFAULT);
    }

    @Test
    public void testGetIndes() throws IOException {
        //1.准备request对象
        GetIndexRequest request = new GetIndexRequest("items");
        //3.应答
        boolean exists = client.indices().exists(request, RequestOptions.DEFAULT);
        System.out.println(exists);
    }


    @Test
    public void testDeleteIndes() throws IOException {
        //1.准备request对象
        DeleteIndexRequest request = new DeleteIndexRequest("items");
        //3.应答
       client.indices().delete(request, RequestOptions.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        try {
            client.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private final static String MAPPING_TEMPLATE ="{\n" +
            "  \"mappings\": {\n" +
            "    \"properties\": {\n" +
            "      \"id\": {\n" +
            "        \"type\": \"keyword\"\n" +
            "      },\n" +
            "      \"name\": {\n" +
            "        \"type\": \"text\",\n" +
            "        \"analyzer\": \"ik_max_word\"\n" +
            "      },\n" +
            "      \"price\": {\n" +
            "        \"type\": \"integer\"\n" +
            "      },\n" +
            "      \"stock\": {\n" +
            "        \"type\": \"integer\"\n" +
            "      },\n" +
            "      \"image\": {\n" +
            "        \"type\": \"keyword\",\n" +
            "        \"index\": false\n" +
            "      },\n" +
            "      \"category\": {\n" +
            "        \"type\": \"keyword\"\n" +
            "      },\n" +
            "      \"brand\": {\n" +
            "        \"type\": \"keyword\"\n" +
            "      },\n" +
            "      \"sold\": {\n" +
            "        \"type\": \"integer\"\n" +
            "      },\n" +
            "      \"commentCount\": {\n" +
            "        \"type\": \"integer\",\n" +
            "        \"index\": false\n" +
            "      },\n" +
            "      \"isAD\": {\n" +
            "        \"type\": \"boolean\"\n" +
            "      },\n" +
            "      \"updateTime\": {\n" +
            "        \"type\": \"date\"\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";
}
