package com.campus.item.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.api.dto.ItemDTO;
import com.campus.api.dto.OrderDetailDTO;
import com.campus.common.domain.PageDTO;
import com.campus.common.exception.BizIllegalException;
import com.campus.common.utils.BeanUtils;
import com.campus.item.config.ElasticsearchProperties;
import com.campus.item.domain.dto.StockChangeDTO;
import com.campus.item.domain.po.Item;
import com.campus.item.domain.po.ItemDoc;
import com.campus.item.domain.query.ItemPageQuery;
import com.campus.item.mapper.ItemMapper;
import com.campus.item.service.IItemService;
import com.campus.item.utils.ElasticSearchUtils;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.*;

/**
 * <p>
 * 商品表 服务实现类
 * </p>
 *
 * @author 虎哥
 */
@Slf4j
@Service
public class ItemServiceImpl extends ServiceImpl<ItemMapper, Item> implements IItemService {

    private final RestHighLevelClient restHighLevelClient;
    private final ElasticsearchProperties elasticsearchProperties;
    private final RabbitTemplate rabbitTemplate;

    public ItemServiceImpl(RestHighLevelClient restHighLevelClient,
                           ElasticsearchProperties elasticsearchProperties,
                           RabbitTemplate rabbitTemplate) {
        this.restHighLevelClient = restHighLevelClient;
        this.elasticsearchProperties = elasticsearchProperties;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Transactional
    @Override
    public void deductStock(List<OrderDetailDTO> items) {
        String sqlStatement = "com.campus.item.mapper.ItemMapper.updateStock";
        boolean r = false;
        try {
            r = executeBatch(items, (sqlSession, entity) -> sqlSession.update(sqlStatement, entity));
        } catch (Exception e) {
            throw new BizIllegalException("更新库存异常，可能是库存不足!", e);
        }
        if (!r) {
            throw new BizIllegalException("库存不足！");
        }
        // 扣减成功后发送库存变更消息，通知同步Elasticsearch库存
        for (OrderDetailDTO item : items) {
            StockChangeDTO message = new StockChangeDTO();
            message.setItemId(item.getItemId());
            message.setNum(item.getNum());
            try {
                rabbitTemplate.convertAndSend("item.direct", "stock.change", message);
            } catch (Exception e) {
                log.error("发送库存变更消息失败，商品id：{}", item.getItemId(), e);
            }
        }
    }

    @Override
    public List<ItemDTO> queryItemByIds(Collection<Long> ids) {
        return BeanUtils.copyList(listByIds(ids), ItemDTO.class);
    }

    @Override
    public PageDTO<ItemDoc> search(ItemPageQuery query) {
        // 1.准备request对象
        SearchRequest request = new SearchRequest(elasticsearchProperties.getIndexName());
        // 2.构建bool查询
        BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();
        // 2.1.关键字匹配name，无关键字时查询全部
        if (StrUtil.isNotBlank(query.getKey())) {
            boolQuery.must(QueryBuilders.matchQuery("name", query.getKey()));
        } else {
            boolQuery.must(QueryBuilders.matchAllQuery());
        }
        // 2.2.品牌过滤，brand是text类型，精确匹配走keyword子字段
        if (StrUtil.isNotBlank(query.getBrand())) {
            boolQuery.filter(QueryBuilders.termQuery("brand.keyword", query.getBrand()));
        }
        // 2.3.类目过滤，category是text类型，精确匹配走keyword子字段
        if (StrUtil.isNotBlank(query.getCategory())) {
            boolQuery.filter(QueryBuilders.termQuery("category.keyword", query.getCategory()));
        }
        // 2.4.价格区间过滤
        if (query.getMinPrice() != null || query.getMaxPrice() != null) {
            RangeQueryBuilder rangeQuery = QueryBuilders.rangeQuery("price");
            if (query.getMinPrice() != null) {
                rangeQuery.gte(query.getMinPrice());
            }
            if (query.getMaxPrice() != null) {
                rangeQuery.lte(query.getMaxPrice());
            }
            boolQuery.filter(rangeQuery);
        }
        request.source().query(boolQuery);
        // 3.分页
        request.source().from(query.from()).size(query.getPageSize());
        // 4.排序，默认按更新时间降序
        request.source().sort("updateTime", SortOrder.DESC);
        // 5.高亮
        request.source().highlighter(new HighlightBuilder()
                .field("name")
                .requireFieldMatch(false));
        // 6.发送请求
        SearchResponse response;
        try {
            response = restHighLevelClient.search(request, RequestOptions.DEFAULT);
        } catch (IOException e) {
            throw new BizIllegalException("搜索商品失败", e);
        }
        // 7.解析结果
        long total = ElasticSearchUtils.getTotal(response);
        List<ItemDoc> list = ElasticSearchUtils.parseHits(response, ItemDoc.class, "name", ItemDoc::setName);
        long pages = (total + query.getPageSize() - 1) / query.getPageSize();
        return new PageDTO<>(total, pages, list);
    }

    @Override
    public void updateEsStock(Long itemId) {
        // 1.查询数据库最新库存，以数据库为准
        Item item = getById(itemId);
        if (item == null) {
            return;
        }
        // 2.准备UpdateRequest，局部更新ES文档的stock与sold字段
        Map<String, Object> doc = new HashMap<>(2);
        doc.put("stock", item.getStock());
        doc.put("sold", item.getSold());
        UpdateRequest request = new UpdateRequest(
                elasticsearchProperties.getIndexName(), itemId.toString())
                .doc(doc);
        // 3.发送请求
        try {
            restHighLevelClient.update(request, RequestOptions.DEFAULT);
        } catch (IOException e) {
            throw new BizIllegalException("同步商品库存到Elasticsearch失败", e);
        }
    }
}
