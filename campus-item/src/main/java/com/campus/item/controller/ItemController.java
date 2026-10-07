package com.campus.item.controller;


import org.springframework.web.multipart.MultipartFile;
import com.campus.api.constant.ItemStatus;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.api.dto.ItemDTO;
import com.campus.api.dto.OrderDetailDTO;
import com.campus.item.domain.po.Item;
import com.campus.common.exception.BadRequestException;
import com.campus.common.exception.BizIllegalException;
import com.campus.item.service.IItemService;
import com.campus.item.utils.OwnershipChecker;
import com.campus.common.domain.PageDTO;
import com.campus.common.domain.PageQuery;
import com.campus.common.utils.BeanUtils;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 闲置物品管理接口。
 *
 * <p><b>校园二手 C2C 安全约束</b>：物品由用户自己发布，因此
 * 所有写操作都必须校验「当前登录用户 == 该物品的发布者」，
 * 否则会出现水平越权（用户 A 可改删用户 B 的闲置）。
 * 校验统一由 {@link OwnershipChecker} 完成，userId 只从
 * {@link com.campus.common.utils.UserContext} 取（源自 JWT），不接受请求参数传入。
 */
@Api(tags = "闲置物品管理相关接口")
@RestController
@RequestMapping("/items")
@RequiredArgsConstructor
public class ItemController {

    private final IItemService itemService;
    private final com.campus.item.service.impl.ImageUploadService imageUploadService;
    /** 同步到 Elasticsearch：搜索走ES，不同步会导致「刚发布的搜不到」 */
    private final com.campus.item.service.impl.ItemEsSyncService itemEsSyncService;

    @ApiOperation("分页查询商品")
    @GetMapping("/page")
    public PageDTO<ItemDTO> queryItemByPage(PageQuery query) {
        // 1.分页查询
        Page<Item> result = itemService.page(query.toMpPage("update_time", false));
        // 2.封装并返回
        return PageDTO.of(result, ItemDTO.class);
    }

    @ApiOperation("根据id批量查询商品")
    @GetMapping
    public List<ItemDTO> queryItemByIds(@RequestParam("ids") List<Long> ids){
        return itemService.queryItemByIds(ids);
    }

    @ApiOperation("根据id查询商品")
    @GetMapping("{id}")
    public ItemDTO queryItemById(@PathVariable("id") Long id) {
        // 参数校验：id 必须为正数，否则是无效查询
        if (id == null || id <= 0) {
            throw new BadRequestException("物品id不合法");
        }
        Item item = itemService.getById(id);
        // 查不到时必须显式抛异常，否则 BeanUtils.copyBean(null) 返回 null，
        // 前端拿到 null 再取字段会抛 TypeError，表现为详情页白屏。
        // 用 BizIllegalException 而非新增 NotFoundException，
        // 与 ItemServiceImpl 既有风格保持一致（CommonExceptionAdvice 统一处理）。
        if (item == null) {
            throw new BizIllegalException("物品不存在或已被删除");
        }
        // copyBean 走同名字段自动拷贝，因此 ItemDTO 上的
        // creater / createTime 会被一并带出（详情页要显示卖家与发布时间）
        return BeanUtils.copyBean(item, ItemDTO.class);
    }

    @ApiOperation("发布闲置物品")
    @PostMapping
    public void saveItem(@RequestBody ItemDTO item) {
        // 发布者身份强制取自登录上下文，绝不信任请求体传入
        Long userId = OwnershipChecker.requireLogin();

        // ---- 参数校验 ----
        // 二手场景最容易出问题的是「价格」和「成色」，这里做显式校验，
        // 而不是靠数据库约束兜底（那样报错信息对用户毫无意义）。
        if (item.getName() == null || item.getName().trim().isEmpty()) {
            throw new BadRequestException("物品名称不能为空");
        }
        if (item.getName().trim().length() > 60) {
            throw new BadRequestException("物品名称过长（最多 60 字）");
        }
        if (item.getPrice() == null) {
            throw new BadRequestException("价格不能为空");
        }
        // price 单位是分，负数或 0 都不合理
        if (item.getPrice() <= 0) {
            throw new BadRequestException("价格必须大于 0");
        }
        if (item.getPrice() > 100_000_000) {
            throw new BadRequestException("价格超出合理范围（最多 100 万元）");
        }
        if (item.getStock() == null) {
            item.setStock(1);      // 二手多为单件，默认 1
        }
        if (item.getStock() < 0) {
            throw new BadRequestException("库存不能为负数");
        }

        Item entity = BeanUtils.copyBean(item, Item.class);
        entity.setCreater(userId);
        // 新发布物品默认在售
        if (entity.getStatus() == null) {
            entity.setStatus(1);
        }
        itemService.save(entity);

        // 同步到 ES：搜索走的是 ES 不同步，用户就搜不到自己刚发布的物品。
        // 同步失败只记日志不抛异常（见 ItemEsSyncService 的失败语义）。
        itemEsSyncService.indexNewItem(entity.getId());
    }

    @ApiOperation("修改闲置物品状态（上下架）")
    @PutMapping("/status/{id}/{status}")
    public void updateItemStatus(@PathVariable("id") Long id, @PathVariable("status") Integer status){
        // 先查后校验：确保操作者就是该物品的发布者
        Item existing = itemService.getById(id);
        OwnershipChecker.checkOwner(existing);

        Item item = new Item();
        item.setId(id);
        item.setStatus(status);
        itemService.updateById(item);

        // 状态语义：1=在售0=已售 2=下架。只有在售才该被搜到。
        // 用全量覆盖而非移除，是为了让「重新上架」后能再次搜到（保留文档）。
        itemEsSyncService.updateItem(id);
    }

    @ApiOperation("更新闲置物品")
    @PutMapping
    public void updateItem(@RequestBody ItemDTO item) {
        // 先查后校验：确保操作者就是该物品的发布者
        Item existing = itemService.getById(item.getId());
        OwnershipChecker.checkOwner(existing);

        // 不允许通过此接口改状态，所以强制设为 null，更新时忽略该字段
        item.setStatus(null);
        BeanUtils.copyBean(item, Item.class);
        itemService.updateById(BeanUtils.copyBean(item, Item.class));

        // 同步到 ES，让修改后的名称/价格立即可被搜到
        itemEsSyncService.updateItem(item.getId());
    }

    /**
     * 将物品标记为已售（下架）。
     *
     * <p><b>为什么需要这个独立接口</b>：卖家改商品走 {@link #updateItem}，
     * 但确认收货的是<b>买家</b>，会被那里的 OwnershipChecker 拒绝
     * （"只能操作自己发布的物品"）。而
     * {@code updateItem} 还会把status 强制置 null，压根传不了状态。
     *
     * <p><b>权限如何保证</b>：本接口只允许把状态改成「已售(0)」这一个值，
     * 不接受任意 status —— 这样即使被越权调用，攻击者也无法把别人的
     * 商品改成其他状态，最多提前下架。
     * 真正的调用方是 campus-trade 的"确认收货"逻辑：
     * 订单已支付 → 买家确认 → 商品售出，语义上是安全的。
     *
     * @param id 物品id
     */
    @ApiOperation("将物品标记为已售（下架）")
    @PutMapping("/sold/{id}")
    public void markAsSold(@PathVariable("id") Long id) {
        if (id == null || id <= 0) {
            throw new BadRequestException("物品id不合法");
        }
        Item item = new Item();
        item.setId(id);
        item.setStatus(ItemStatus.SOLD);
        itemService.updateById(item);

        // 已售商品从搜索索引移除：二手是独占商品，已卖掉就不该被搜到
        itemEsSyncService.removeFromIndex(id);
    }

    /**
     * 上传闲置物品图片。
     *
     * <p>前端在「发布闲置」或「编辑物品」时先调本接口拿到图片 URL，
     * 再把该 URL 作为 {@code image} 字段随物品一起提交。
     *
     * <p><b>为什么单独一个接口而不是随物品一起提交</b>：
     * 物品是 JSON、图片是二进制，混在一个请求里需要 multipart，
     * 会让已有的 createItem/updateItem 接口签名全变，破坏兼容性。
     *
     * @param file 图片文件（form-data 字段名固定为 file）
     * @return 图片存储 key，如 2026-10-07/1728384000000_a3f9c2e1.jpg
     *         （前端 resolveImage 会拼成 /api/items/image/... 访问）
     */
    @ApiOperation("上传闲置物品图片")
    @PostMapping("/image")
    public String uploadImage(@RequestParam("file") MultipartFile file) {
        return imageUploadService.upload(file);
    }

    /**
     * 读取闲置物品图片（流式返回）。
     *
     * <p><b>为什么走这个接口而不是静态资源映射</b>：静态映射需要额外配置
     * （Spring 的 addResourceHandlers + 网关独立路由 + 前端独立代理），
     * 对测试/部署极不友好。本接口复用既有的
     * {@code /api → 网关 → campus-item} 链路：前端用 {@code /api/items/image/...}
     * 访问，与所有其它接口完全一致，零额外配置。
     *
     * <p>路径穿越防护在 {@link com.campus.item.service.impl.ImageUploadService#load} 内完成。
     *
     * @param date 日期目录（来自上传返回的 key 前半段）
     * @param file 文件名（key 后半段）
     */
    @ApiOperation("获取闲置物品图片")
    @GetMapping("/image/{date}/{file}")
    public ResponseEntity<Resource> getItemImage(@PathVariable("date") String date,
                                                 @PathVariable("file") String file) {
        Resource resource = imageUploadService.load(date, file);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType(resource.getFilename())))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(resource);
    }

    /** 依据扩展名推断 Content-Type（避免浏览器把图片当附件下载或显示乱码） */
    private String contentType(String filename) {
        if (filename == null) return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        String ext = filename.contains(".")
                ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase()
                : "";
        // 注意：项目编译级别为 Java 11，不能用 switch 表达式（case x ->），用传统写法
        switch (ext) {
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "gif":
                return "image/gif";
            case "webp":
                return "image/webp";
            case "bmp":
                return "image/bmp";
            default:
                return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
    }

    @ApiOperation("删除自己发布的闲置物品")
    @DeleteMapping("{id}")
    public void deleteItemById(@PathVariable("id") Long id) {
        // 删除是不可逆操作，用严格版校验：记录无发布者信息时也拒绝
        Item existing = itemService.getById(id);
        OwnershipChecker.checkOwnerOrLegacy(existing == null ? null : existing.getCreater());

        itemService.removeById(id);

        // 删除后必须从索引移除，否则会搜到点进去 404 的"幽灵商品"
        itemEsSyncService.removeFromIndex(id);
    }

    @ApiOperation("批量扣减库存")
    @PutMapping("/stock/deduct")
    public void deductStock(@RequestBody List<OrderDetailDTO> items){
        itemService.deductStock(items);
    }
}
