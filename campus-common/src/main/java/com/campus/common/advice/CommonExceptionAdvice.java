package com.campus.common.advice;

import com.campus.common.domain.R;
import com.campus.common.exception.BadRequestException;
import com.campus.common.exception.CommonException;
import com.campus.common.exception.DbException;
import com.campus.common.exception.ForbiddenException;
import com.campus.common.exception.UnauthorizedException;
import com.campus.common.utils.WebUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.util.NestedServletException;

import java.net.BindException;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class CommonExceptionAdvice {

    @ExceptionHandler(DbException.class)
    public Object handleDbException(DbException e) {
        log.error("mysql数据库操作异常 -> ", e);
        return processResponse(e);
    }

    /**
     * 鉴权类异常：未登录 / 无权限。
     *
     * <p><b>为什么不打堆栈</b>：这两类是<b>预期内的客户端错误</b>
     * （token 过期、没带 token、访问他人资源），不是系统缺陷。
     * 实测问题：前端只要有一个请求漏带 token，就会以每 40~60ms 的频率
     * 触发一次，原实现每次都打 55 行堆栈 —— 171 次请求能刷出近万行日志，
     * 真正有用的错误反而被淹没。
     *
     * <p>排查建议：这类问题只需看 warn 行的 message（已带接口路径与原因），
     * 需要完整堆栈时临时把包级别调成 debug 即可。
     */
    @ExceptionHandler({UnauthorizedException.class, ForbiddenException.class})
    public Object handleAuthException(CommonException e) {
        // 只打一行摘要，不打堆栈：用 warn 而非 error，因为这是客户端问题不是服务端故障
        log.warn("鉴权失败 -> {} | uri: {} | 原因: {}",
                e.getClass().getSimpleName(), safeUri(), e.getMessage());
        return processResponse(e);
    }

    @ExceptionHandler(CommonException.class)
    public Object handleBadRequestException(CommonException e) {
        log.error("自定义异常 -> {} , 异常原因：{}  ",e.getClass().getName(), e.getMessage());
        log.debug("", e);
        return processResponse(e);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Object handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getAllErrors()
                .stream().map(ObjectError::getDefaultMessage)
                .collect(Collectors.joining("|"));
        log.error("请求参数校验异常 -> {}", msg);
        log.debug("", e);
        return processResponse(new BadRequestException(msg));
    }
    @ExceptionHandler(BindException.class)
    public Object handleBindException(BindException e) {
        log.error("请求参数绑定异常 ->BindException， {}", e.getMessage());
        log.debug("", e);
        return processResponse(new BadRequestException("请求参数格式错误"));
    }

    @ExceptionHandler(NestedServletException.class)
    public Object handleNestedServletException(NestedServletException e) {
        log.error("参数异常 -> NestedServletException，{}", e.getMessage());
        log.debug("", e);
        return processResponse(new BadRequestException("请求参数处理异常"));
    }

    @ExceptionHandler(Exception.class)
    public Object handleRuntimeException(Exception e) {
        log.error("其他异常 uri : {} -> ", WebUtils.getRequest().getRequestURI(), e);
        return processResponse(new CommonException("服务器内部异常", 500));
    }

    private ResponseEntity<R<Void>> processResponse(CommonException e){
        return ResponseEntity.status(e.getCode()).body(R.error(e));
    }

    /**
     * 取当前请求 URI，取不到时返回占位符而不是抛异常。
     *
     * <p>异常处理器里再抛异常会把原始异常掩盖掉，
     * 排查问题时连"是哪个接口出的问题"都看不到。
     */
    private String safeUri() {
        try {
            return WebUtils.getRequest().getRequestURI();
        } catch (Exception ex) {
            return "<no-request>";
        }
    }
}
