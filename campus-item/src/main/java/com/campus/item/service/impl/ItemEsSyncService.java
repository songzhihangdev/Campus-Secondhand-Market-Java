package com.campus.item.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campus.item.config.ElasticsearchProperties;
import com.campus.item.domain.po.Item;
import com.campus.item.domain.po.ItemDoc;
import com.campus.item.mapper.ItemMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import cn.hutool.core.util.StrUtil;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 物品 → Elasticsearch 的索引同步。
 *
 * <p><b>为什么需要</b>：搜索走的是 ES（{@code /search/list}），
 * 而发布/编辑/删除只写了 MySQL，**同步逻辑一直缺失** ——
 * 实测发布 5 条物品后 ES 文档数 88475、MySQL 88480，
 * 用户<b>搜不到自己刚发布的闲置</b>。索引里那8 万条是历史导入的存量数据。
 *
 * <p><b>失败语义（关键）</b>：所有方法<b>只记日志、不抛异常</b>。
 * MySQL 是唯一真相源，ES 只是检索副本；ES 挂了不该让用户「发布失败」。
 * 后果是极端情况下 ES 会短暂滞后，靠 {@link #rebuildAll()} 可全量修复。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItemEsSyncService {

    private final RestHighLevelClient restHighLevelClient;
    private final ElasticsearchProperties esProperties;
    private final ItemMapper itemMapper;

    /** 手工new，不注入容器 —— 避免与业务 ObjectMapper 配置（Long转String 等）互相影响 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * updateTime 在 ES mapping 里是 <b>long（毫秒时间戳）</b>，不是字符串。
     *
     * <p>实测历史文档存的是 {@code 1790599498000} 这种值，
     * 传字符串会报 mapper_parsing_exception。
     */

    /**
     * 新建物品后写入索引。
     *
     * @param itemId 刚保存的物品 id
     */
    public void indexNewItem(Long itemId) {
        Item item = itemMapper.selectById(itemId);
        if (item == null) {
            log.warn("ES 同步跳过：物品不存在 itemId={}", itemId);
            return;
        }
        try {
            IndexRequest request = new IndexRequest(esProperties.getIndexName())
                    .id(itemId.toString())
                    // refresh=WAIT_UNTIL 让文档立即可搜。
                    // 不加的话要等 ES 的 1s 自动刷新，用户会看到"刚发布的搜不到"，
                    // 正是我们在修的这个问题的另一种表现。
                    .setRefreshPolicy("wait_for");
            request.source(toMap(toDoc(item)));
            restHighLevelClient.index(request, RequestOptions.DEFAULT);
            log.info("ES 同步：新增索引 itemId={}, name={}", itemId, item.getName());
        } catch (Exception e) {
            // 不抛异常 —— MySQL 已写入成功，不能因此让用户看到"发布失败"
            log.error("ES 同步失败（新增）itemId={}，搜索可能搜不到该物品：{}", itemId, e.getMessage());
        }
    }

    /**
     * 更新物品后同步索引。
     *
     * <p>用<b>全量覆盖</b>（IndexRequest）而非局部更新：字段可能多，
     * 局部更新容易漏字段导致新旧值混杂。
     */
    public void updateItem(Long itemId) {
        indexNewItem(itemId);   // 语义相同：按 MySQL 最新数据覆盖索引
    }

    /**
     * 状态变更（上下架/删除/已售）后同步。
     *
     * <p>这里做「从索引移除」而非更新字段：二手平台里
     * 下架与删除的商品<b>不该出现在搜索结果</b>。
     */
    public void removeFromIndex(Long itemId) {
        try {
            DeleteRequest request = new DeleteRequest(esProperties.getIndexName(), itemId.toString());
            restHighLevelClient.delete(request, RequestOptions.DEFAULT);
            log.info("ES 同步：移除索引 itemId={}", itemId);
        } catch (Exception e) {
            log.error("ES 同步失败（移除）itemId={}：{}", itemId, e.getMessage());
        }
    }

    /**
     * 全量重建索引（修复工具）。
     *
     * <p>ES 与 MySQL 长时间不一致后（如ES 宕机期间一直在发布），
     * 调用此方法可把索引刷成与数据库一致。
     *
     * @return 成功写入的文档数
     */
    public int rebuildAll() {
        List<Item> all = itemMapper.selectList(Wrappers.emptyWrapper());
        int ok = 0;
        for (Item item : all) {
            try {
                IndexRequest request = new IndexRequest(esProperties.getIndexName())
                        .id(item.getId().toString());
                request.source(toMap(toDoc(item)));
                restHighLevelClient.index(request, RequestOptions.DEFAULT);
                ok++;
            } catch (Exception e) {
                log.error("全量重建索引失败 itemId={}：{}", item.getId(), e.getMessage());
            }
        }
        log.info("ES 全量重建完成：成功 {}/{}", ok, all.size());
        return ok;
    }

    /** MySQL 实体 → ES 文档 */
    private ItemDoc toDoc(Item item) {
        ItemDoc doc = new ItemDoc();
        doc.setId(item.getId().toString());
        doc.setName(item.getName());
        doc.setPrice(item.getPrice());
        doc.setStock(item.getStock());
        doc.setImage(item.getImage());
        doc.setCategory(item.getCategory());
        doc.setBrand(item.getBrand());
        doc.setSold(item.getSold());
        doc.setCommentCount(item.getCommentCount());
        doc.setIsAD(false);
        doc.setUpdateTime(LocalDateTime.now());
        return doc;
    }

    /**
     * ItemDoc → JSON 字符串。
     *
     * <p><b>为什么传 Map 而不是 JSON 字符串</b>：
     * {@code IndexRequest.source(String)} 在 ES 7.12 客户端里被当成了
     * 变长参数（Object... source），传单个字符串会报
     * "The number of object passed must be even but was [1]" ——
     * 实际上这个方法期望的是 {@code key, value, key, value...} 交替的键值对。
     * 直接给 Map 由客户端自己序列化，语义清晰且不受版本影响。
     *
     * <p>字段顺序与空值处理仍要自己控制：
     * ① LocalDateTime 缺省会序列化成数组/时间戳，与历史数据的
     * {@code "2021-07-28 19:07:01"} 格式不一致，会让按 updateTime 排序失效；
     * ② null 字段会输出成 {@code "field":null}，文档里多一堆空值。
     * 所以用 LinkedHashMap 保证顺序，并跳过空值。
     */
    private Map<String, Object> toMap(ItemDoc doc) {
        Map<String, Object> m = new LinkedHashMap<>(16);
        putIfNotBlank(m, "id", doc.getId());
        putIfNotBlank(m, "name", doc.getName());
        putIfNotNull(m, "price", doc.getPrice());
        putIfNotNull(m, "stock", doc.getStock());
        putIfNotBlank(m, "image", doc.getImage());
        putIfNotBlank(m, "category", doc.getCategory());
        putIfNotBlank(m, "brand", doc.getBrand());
        putIfNotNull(m, "sold", doc.getSold());
        putIfNotNull(m, "commentCount", doc.getCommentCount());
        putIfNotNull(m, "isAD", doc.getIsAD());
        if (doc.getUpdateTime() != null) {
            // 转成毫秒时间戳 —— 与 ES 里 updateTime 的 long 映射一致
            m.put("updateTime", doc.getUpdateTime()
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli());
        }
        return m;
    }

    private void putIfNotBlank(Map<String, Object> m, String k, String v) {
        if (StrUtil.isNotBlank(v)) {
            m.put(k, v);
        }
    }

    private void putIfNotNull(Map<String, Object> m, String k, Object v) {
        if (v != null) {
            m.put(k, v);
        }
    }
}
