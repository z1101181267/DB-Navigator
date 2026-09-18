package com.dbnav.inspection;

/**
 * 目标对象不存在。
 *
 * 单独拎出一个异常类型，是为了让控制器能把「没找到」和「参数不合法」分开：
 * 前者是 404，后者是 400。
 *
 * 如果一律用 IllegalArgumentException，控制器只能统统回 400，于是同一个「不存在」
 * 在同一个模块里会出现两种答案 —— GET /rules/999 是 404，PUT /rules/999 却是 400。
 * 调用方（含预览服务这一侧的实现）很难据此写出一致的判断。
 *
 * 注意适用范围：只有「被寻址的那个资源本身不存在」才算 404。
 * 请求体里引用了一个不存在的 id（如绑定时传了错的 ruleId）仍属参数问题，回 400。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
