package com.dbnav.common;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 让 HTTP 状态码与业务响应体中的 {@code Result.code} 保持一致。
 *
 * <p>背景：控制器统一返回 {@link Result}，业务错误在响应体内以 {@code code=400/404}
 * 表达，但 HTTP 状态码默认仍是 200。这会造成两个问题：
 * <ul>
 *   <li>与 {@code scripts/preview_inspection.py}（本项目的 API 契约可执行规格）不一致——
 *       预览服务返回的是真实状态码；</li>
 *   <li>不符合 REST 惯例，也让 {@code curl -f} / 网关重试策略 / 监控告警失去判断依据。</li>
 * </ul>
 *
 * <p>这里集中在一处做映射，避免在 30 多个控制器分支上逐个改造成 {@code ResponseEntity}。
 * 只处理「响应体是 {@link Result} 且 {@code code} 不是 2xx」的情形，其余一律不动：
 * 例如 SQL 执行失败是通过 {@code Result.ok(...)} 内嵌 {@code success=false} 表达的，
 * 外层仍是 200，不会被误改。
 */
@RestControllerAdvice
public class ApiStatusAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        // 对所有 @ResponseBody 生效；非 Result 的响应体在 beforeBodyWrite 中直接放行
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (!(body instanceof Result<?> result)) {
            return body;
        }
        int code = result.getCode();
        if (code >= 200 && code < 300) {
            return body;   // 成功响应：保持 200
        }
        HttpStatus status = HttpStatus.resolve(code);
        if (status != null) {
            response.setStatusCode(status);
        }
        return body;
    }
}
