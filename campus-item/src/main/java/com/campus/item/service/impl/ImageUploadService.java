package com.campus.item.service.impl;

import com.campus.common.exception.BadRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import javax.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 闲置物品图片上传。
 *
 * <p><b>存储策略</b>：按天分目录，{@code itemImage/2026-10-07/}。
 * 原因：单目录文件过多时，Windows 与部分 Linux 文件系统会出现
 * 「目录项过多」的性能问题；按日期拆分后每个目录文件数可控，
 * 清理过期图片也能直接按目录删。
 *
 * <p><b>命名策略</b>：{@code {毫秒时间戳}_{8位随机}.{扩展名}}，
 * 例如 {@code 1728384000000_a3f9c2e1.jpg}。
 * <ul>
 *   <li>带时间戳前缀 → 天然按时间有序，列表页按文件名排序即按时间</li>
 *   <li>带随机后缀 → 同一毫秒内的并发上传不会撞名</li>
 *   <li>不用原始文件名 → 避免中文/特殊字符/路径穿越（{@code ../}）等问题</li>
 * </ul>
 */
@Slf4j
@Service
public class ImageUploadService {

    /**
     * 图片根目录，<b>由 application-{profile}.yaml 的 hm.image.root-dir 配置</b>。
     *
     * <p><b>必须是绝对路径</b>：本服务用它做两件事 ——
     * ① {@code Paths.get(rootDir, 日期)} 落盘；
     * ② {@code load(date,file)} 按相同规则读取，由
     *    {@code ItemController#getItemImage} 通过统一接口 /api/items/image/... 返回。
     * 相对路径会相对于「进程工作目录」解析，换个目录启动就全找不到，
     * 表现为「上传接口返回 200，但图片 404」。
     *
     * <p>这里不给默认值是<b>刻意的</b>：配置缺失就应当启动失败并给出明确提示，
     * 而不是静默落到某个猜测的目录里。
     */
    @Value("${hm.image.root-dir}")
    private String rootDir;

    /** 单文件大小上限（字节）。需 <= spring.servlet.multipart.max-file-size */
    @Value("${hm.image.max-size}")
    private long maxSize;

    /** 启动时打印一次，便于确认配置是否生效、图片落在哪 */
    @PostConstruct
    public void logConfig() {
        log.info("[图片上传] 根目录={}，单张上限={}MB", rootDir, maxSize / 1024 / 1024);
    }

    /** 允许的图片类型（白名单，比黑名单安全） */
    private static final Set<String> ALLOWED_EXT =
            Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp");

    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 保存一张图片。
     *
     * @param file 前端传来的文件
     * @return **存储 key**，形如 {@code 2026-10-07/1728384000000_a3f9c2e1.jpg}
     *         —— 存进 item.image 字段，前端 {@code resolveImage()} 会拼成
     *         {@code /api/items/image/...} 走统一的图片接口访问
     * @throws BadRequestException 文件为空、类型不支持、超出大小限制或磁盘写入失败
     */
    public String upload(MultipartFile file) {
        // 1.基本校验
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("请选择要上传的图片");
        }
        if (file.getSize() > maxSize) {
            throw new BadRequestException("图片过大，单张不能超过 "
                    + (maxSize / 1024 / 1024) + "MB");
        }

        // 2.校验类型：既看扩展名，也看内容头（防止把 .jsp 改名成 .jpg）
        String ext = extractExtension(file.getOriginalFilename());
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BadRequestException("只支持 " + String.join("/", ALLOWED_EXT) + " 格式的图片");
        }
        // 3.校验真实内容：不能只看扩展名 —— 把 .jsp 改名成 .jpg 就能绕过。
        //    读文件头几个字节，与图片魔数比对。
        if (!looksLikeImage(file)) {
            throw new BadRequestException("文件内容不是图片，请重新选择");
        }

        // 4.按天建目录
        String dateDir = LocalDate.now().format(DATE_DIR);
        Path dir = Paths.get(rootDir, dateDir);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.error("创建图片目录失败：{}", dir, e);
            throw new BadRequestException("图片目录创建失败，请联系管理员");
        }

        // 5.生成文件名并落盘
        String fileName = buildFileName(ext);
        Path target = dir.resolve(fileName);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("保存图片失败：{}", target, e);
            throw new BadRequestException("图片保存失败，请稍后重试");
        }

        // 6.返回"存储 key"（形如 2026-10-07/1728384000000_a3f9c2e1.jpg），
        //    而不是完整 URL。原因：图片改走统一的 /api/items/image/{date}/{file}
        //    接口（见 ItemController），复用既有的 /api→网关→服务 链路，
        //    不再需要静态资源映射 / 网关独立路由 / Vite 独立代理这三套特殊配置，
        //    大大降低测试与部署的认知负担。
        //    key 由后端拼接成可访问地址，前端 resolveImage() 负责加 /api 前缀。
        String key = dateDir + "/" + fileName;
        log.info("图片上传成功：{}（{} 字节）", key, file.getSize());
        return key;
    }

    /**
     * 按存储 key 读取图片，供 {@code GET /items/image/{date}/{file}} 接口流式返回。
     *
     * <p><b>路径穿越防护</b>：文件名来自 URL，必须规范化后校验仍位于 rootDir 之内，
     * 否则攻击者可借 {@code ../} 读取服务器任意文件。
     *
     * @param date   日期目录，如 2026-10-07
     * @param file   文件名，如 1728384000000_a3f9c2e1.jpg
     * @return 可直接作为 ResponseEntity body 的 Resource
     * @throws BadRequestException key 非法、文件不存在或越权访问
     */
    public Resource load(String date, String file) {
        if (date == null || file == null || date.isBlank() || file.isBlank()) {
            throw new BadRequestException("图片路径不合法");
        }
        Path base = Paths.get(rootDir).toAbsolutePath().normalize();
        // 仅允许 [字母/数字/./-/_] 的日期与文件名，进一步收敛攻击面
        if (!date.matches("[0-9\\-]+") || !file.matches("[A-Za-z0-9._\\-]+")) {
            throw new BadRequestException("图片路径包含非法字符");
        }
        Path target = base.resolve(date).resolve(file).normalize();
        // 规范化后必须仍以 rootDir 为前缀，否则视为路径穿越
        if (!target.startsWith(base)) {
            throw new BadRequestException("非法的图片路径");
        }
        if (!Files.exists(target) || !Files.isRegularFile(target)) {
            throw new BadRequestException("图片不存在");
        }
        try {
            return new UrlResource(target.toUri());
        } catch (MalformedURLException e) {
            log.error("构造图片资源失败：{}", target, e);
            throw new BadRequestException("图片读取失败");
        }
    }

    /**
     * 读文件头判断是否真的是图片。
     *
     * <p><b>为什么必须校验内容而不能只看扩展名</b>：
     * 把 {@code shell.jsp} 改名成 {@code photo.jpg} 就能通过扩展名检查，
     * 而静态资源映射会把任意文件都当资源返回，等于给了攻击者上传可执行文件的机会。
     *
     * <p>各格式的文件头：
     * <pre>
     *   JPEG: FF D8 FF
     *   PNG : 89 50 4E 47 0D 0A 1A 0A
     *   GIF : "GIF8"
     *   BMP : "BM"
     *   WEBP: "RIFF"...."WEBP"
     * </pre>
     */
    private boolean looksLikeImage(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            byte[] head = new byte[12];
            int read = in.read(head);
            if (read < 4) {
                return false;
            }
            // JPEG: FF D8 FF
            if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8
                    && (head[2] & 0xFF) == 0xFF) {
                return true;
            }
            // PNG: 89 50 4E 47 0D 0A 1A 0A
            if (head.length >= 8
                    && (head[0] & 0xFF) == 0x89 && head[1] == 0x50
                    && head[2] == 0x4E && head[3] == 0x47
                    && head[4] == 0x0D && head[5] == 0x0A
                    && head[6] == 0x1A && head[7] == 0x0A) {
                return true;
            }
            // GIF: "GIF87a" / "GIF89a"
            if (head[0] == 'G' && head[1] == 'I' && head[2] == 'F') {
                return true;
            }
            // BMP: "BM"
            if (head[0] == 'B' && head[1] == 'M') {
                return true;
            }
            // WEBP: "RIFF" .... "WEBP"
            if (head.length >= 12
                    && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
                return true;
            }
            return false;
        } catch (IOException e) {
            log.warn("读取上传文件头失败", e);
            return false;
        }
    }

    /** 从原始文件名里取扩展名（小写），取不到返回空串 */
    private String extractExtension(String originalName) {
        if (originalName == null) {
            return "";
        }
        int dot = originalName.lastIndexOf('.');
        if (dot < 0 || dot == originalName.length() - 1) {
            return "";
        }
        return originalName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 生成文件名：{@code {毫秒时间戳}_{8位随机}.{ext}}
     *
     * <p>不用 {@code UUID.randomUUID()} 全串（36 字符太长），
     * 取后 8 位足够防撞；时间戳前缀保证可排序。
     */
    private String buildFileName(String ext) {
        long ts = System.currentTimeMillis();
        String rand = UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 8);
        return ts + "_" + rand + "." + ext;
    }
}