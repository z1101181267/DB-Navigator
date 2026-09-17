package com.dbnav.inspection;

import com.dbnav.inspection.model.BaselineCheckResult;
import com.dbnav.inspection.model.InspectionBaseline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置基线判定器。
 *
 * 对齐 RaccoonX 的 operator 语义，把「采集到的实际值」与「期望值」比对：
 *
 *   =        实际 == 期望                      → 合规
 *   !=       实际 != 期望                      → 合规
 *   >  >=    实际 大于（或等于）期望             → 合规
 *   <  <=    实际 小于（或等于）期望             → 合规
 *   BETWEEN  期望下界 <= 实际 <= 期望上界        → 合规
 *   LIKE     实际包含期望子串（忽略大小写）       → 合规
 *
 * 取值比较做了三层归一化：
 *   1) 内存/容量串（128MB、4GB）→ 字节数后按数值比较
 *   2) 纯数值串 → 数值比较
 *   3) 枚举串（on/off、wal_level、recovery model 等）→ 按语义序位比较
 *   4) 其余 → 忽略大小写的字符串比较
 */
@Slf4j
@Component
public class BaselineChecker {

    /** 容量单位换算 */
    private static final Map<String, Long> UNIT = Map.of(
            "KB", 1024L,
            "MB", 1024L * 1024,
            "GB", 1024L * 1024 * 1024,
            "TB", 1024L * 1024 * 1024 * 1024
    );

    private static final Pattern SIZE_PATTERN =
            Pattern.compile("^\\s*([0-9]+(?:\\.[0-9]+)?)\\s*([KMGT]B)\\s*$", Pattern.CASE_INSENSITIVE);

    /**
     * 枚举值的语义序位表。用于 wal_level、recovery model 这类
     * "字面量无法直接比大小、但业务上有强弱顺序" 的参数。
     * key = 归一化后的字面量，value = 序位。
     */
    private static final Map<String, Integer> RANKS = new LinkedHashMap<>();

    static {
        // 开关类
        rank("off", 0); rank("on", 1); rank("false", 0); rank("true", 1); rank("no", 0); rank("yes", 1);
        // PostgreSQL / KingbaseES
        rank("minimal", 0); rank("replica", 1); rank("logical", 2);              // wal_level
        rank("md5", 0); rank("scram-sha-256", 1);                                // password_encryption
        rank("none", 0); rank("archive", 1); rank("hot_standby", 2);             // archive_mode 等
        // MySQL
        rank("low", 0); rank("medium", 1); rank("strong", 2);                    // validate_password.policy
        // SQL Server
        rank("simple", 0); rank("bulk_logged", 1); rank("full", 2);              // recovery model
        rank("torn_page_detection", 1); rank("checksum", 2);                     // page_verify_option
        // Oracle
        rank("os", 1); rank("db", 2); rank("db_extended", 3);                    // audit_trail
    }

    private static void rank(String literal, int value) {
        RANKS.putIfAbsent(literal.toLowerCase(Locale.ROOT), value);
    }

    /**
     * 判定一条基线。
     *
     * @param baseline    基线定义
     * @param actualValue 实际采集值（null 表示未采集，结果标记为 checked=false）
     */
    public BaselineCheckResult check(InspectionBaseline baseline, String actualValue) {
        BaselineCheckResult.BaselineCheckResultBuilder r = BaselineCheckResult.builder()
                .dbType(baseline.getDbType())
                .paramName(baseline.getParamName())
                .operator(baseline.getOperator())
                .expectedValue(describeExpected(baseline))
                .riskLevel(baseline.getRiskLevel())
                .actualValue(actualValue)
                .descriptionZh(baseline.getDescriptionZh());

        // 仅当完全未采集到（null）才判为无法判定。
        // 空字符串属于「采集到了但值为空」，对 != / = 这类运算符是有意义的。
        if (actualValue == null) {
            return r.pass(false).checked(false)
                    .message("未采集到参数值，无法判定")
                    .build();
        }

        String op = baseline.getOperator() == null ? "=" :
                baseline.getOperator().trim().toUpperCase(Locale.ROOT);

        Boolean pass = evaluate(op, actualValue, baseline);
        if (pass == null) {
            return r.pass(false).checked(false)
                    .message("无法比较：" + actualValue + " " + op + " " + describeExpected(baseline))
                    .build();
        }

        return r.pass(pass).checked(true)
                .message(pass
                        ? "合规：" + actualValue + " " + op + " " + describeExpected(baseline)
                        : "不合规：实际 " + actualValue + " 不满足 " + op + " " + describeExpected(baseline))
                .build();
    }

    /**
     * 执行比较。返回 null 表示无法比较。
     */
    private Boolean evaluate(String op, String actualRaw, InspectionBaseline b) {
        switch (op) {
            case "LIKE": {
                String needle = b.getExpectedValue() == null ? "" : b.getExpectedValue();
                return actualRaw.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
            }
            case "BETWEEN": {
                Double a = toNumber(actualRaw);
                if (a == null || b.getExpectedValueMin() == null || b.getExpectedValueMax() == null) {
                    return null;
                }
                return a >= b.getExpectedValueMin() && a <= b.getExpectedValueMax();
            }
            default:
                break;
        }

        // 1) 数值比较（含容量串归一化）
        Double actualNum = toNumber(actualRaw);
        Double expectedNum = toNumber(b.getExpectedValue());
        if (actualNum != null && expectedNum != null) {
            return compare(op, Double.compare(actualNum, expectedNum));
        }

        // 2) 枚举语义序位比较
        String actualKey = actualRaw.trim().toLowerCase(Locale.ROOT);
        String expectedKey = b.getExpectedValue() == null ? ""
                : b.getExpectedValue().trim().toLowerCase(Locale.ROOT);
        Integer actualRank = RANKS.get(actualKey);
        Integer expectedRank = RANKS.get(expectedKey);
        if (actualRank != null && expectedRank != null) {
            return compare(op, Integer.compare(actualRank, expectedRank));
        }

        // 3) 字符串比较（仅 = 与 != 有意义）
        int cmp = actualRaw.trim().compareToIgnoreCase(
                b.getExpectedValue() == null ? "" : b.getExpectedValue().trim());
        return compare(op, cmp);
    }

    private Boolean compare(String op, int cmp) {
        switch (op) {
            case "=":  return cmp == 0;
            case "!=": return cmp != 0;
            case ">":  return cmp > 0;
            case ">=": return cmp >= 0;
            case "<":  return cmp < 0;
            case "<=": return cmp <= 0;
            default:   return null;
        }
    }

    /**
     * 把取值归一化为数值：支持纯数字与 128MB / 4GB 这类容量串。
     */
    private Double toNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();

        Matcher m = SIZE_PATTERN.matcher(s);
        if (m.matches()) {
            double v = Double.parseDouble(m.group(1));
            Long mul = UNIT.get(m.group(2).toUpperCase(Locale.ROOT));
            return mul == null ? null : v * mul;
        }

        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 期望值的可读描述，BETWEEN 时展开为区间 */
    private String describeExpected(InspectionBaseline b) {
        if ("BETWEEN".equalsIgnoreCase(b.getOperator())) {
            return "[" + b.getExpectedValueMin() + ", " + b.getExpectedValueMax() + "]";
        }
        return b.getExpectedValue() == null ? "" : b.getExpectedValue();
    }
}
