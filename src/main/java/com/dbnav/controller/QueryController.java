package com.dbnav.controller;

import com.dbnav.common.Result;
import com.dbnav.datasource.DataSourceManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.*;

/**
 * REST API for SQL query execution against managed data sources.
 *
 * Endpoint:
 *   POST /api/query/{datasourceId}/execute  — execute SQL and return results
 *
 * Supports:
 *   - SELECT queries (returns column metadata + rows)
 *   - DDL/DML statements (returns affected row count)
 *   - Execution timing
 *   - Query history recording
 */
@Slf4j
@RestController
@RequestMapping("/api/query")
@RequiredArgsConstructor
public class QueryController {

    private final DataSourceManager dataSourceManager;
    private final JdbcTemplate registryJdbc;

    /**
     * Execute a SQL statement against a managed data source.
     *
     * @param datasourceId target data source ID
     * @param body request body containing SQL text
     * @return query result with columns, rows, and timing
     */
    @PostMapping("/{datasourceId}/execute")
    public Result<Map<String, Object>> execute(
            @PathVariable Long datasourceId,
            @RequestBody Map<String, String> body) {

        String sql = body.get("sql");
        if (sql == null || sql.trim().isEmpty()) {
            return Result.fail(400, "SQL is empty");
        }

        sql = sql.trim();
        String upperSql = sql.toUpperCase();
        long start = System.currentTimeMillis();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("datasourceId", datasourceId);
        result.put("sql", sql);

        try (Connection conn = dataSourceManager.getConnection(datasourceId)) {
            boolean isQuery = upperSql.startsWith("SELECT") || upperSql.startsWith("WITH")
                    || upperSql.startsWith("SHOW") || upperSql.startsWith("DESC")
                    || upperSql.startsWith("EXPLAIN");

            if (isQuery) {
                // Execute query and return result set
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setMaxRows(1000); // safety limit
                    try (ResultSet rs = stmt.executeQuery()) {
                        ResultSetMetaData meta = rs.getMetaData();
                        int columnCount = meta.getColumnCount();

                        // Build column list
                        List<Map<String, Object>> columns = new ArrayList<>();
                        for (int i = 1; i <= columnCount; i++) {
                            Map<String, Object> col = new LinkedHashMap<>();
                            col.put("name", meta.getColumnLabel(i));
                            col.put("type", meta.getColumnTypeName(i));
                            col.put("nullable", meta.isNullable(i) == ResultSetMetaData.columnNullable);
                            columns.add(col);
                        }
                        result.put("columns", columns);

                        // Build row list
                        List<List<Object>> rows = new ArrayList<>();
                        while (rs.next()) {
                            List<Object> row = new ArrayList<>();
                            for (int i = 1; i <= columnCount; i++) {
                                Object value = rs.getObject(i);
                                row.add(value != null ? value.toString() : null);
                            }
                            rows.add(row);
                        }
                        result.put("rows", rows);
                        result.put("rowCount", rows.size());
                        result.put("type", "QUERY");
                    }
                }
            } else {
                // Execute DDL/DML
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    int affected = stmt.executeUpdate();
                    result.put("affectedRows", affected);
                    result.put("type", "UPDATE");
                }
            }

            long elapsed = System.currentTimeMillis() - start;
            result.put("success", true);
            result.put("elapsedMs", elapsed);

            // Record success in query history
            recordHistory(datasourceId, sql, "SUCCESS", null, 0, elapsed);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("errorType", e.getClass().getSimpleName());
            result.put("elapsedMs", elapsed);
            log.error("Query execution failed: {}", sql, e);

            // Record failure in query history
            recordHistory(datasourceId, sql, "FAILED", e.getMessage(), 0, elapsed);
        }

        return Result.ok(result);
    }

    /**
     * Get query execution history for a data source.
     */
    @GetMapping("/{datasourceId}/history")
    public Result<List<Map<String, Object>>> getHistory(
            @PathVariable Long datasourceId,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {

        List<Map<String, Object>> history = registryJdbc.query(
                "SELECT id, sql_text, status, duration_ms, error_msg, executed_at " +
                "FROM query_history WHERE datasource_id=? " +
                "ORDER BY executed_at DESC LIMIT ?",
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("sql", rs.getString("sql_text"));
                    row.put("status", rs.getString("status"));
                    row.put("durationMs", rs.getLong("duration_ms"));
                    row.put("error", rs.getString("error_msg"));
                    row.put("executedAt", rs.getTimestamp("executed_at"));
                    return row;
                },
                datasourceId, limit);

        return Result.ok(history);
    }

    private void recordHistory(Long datasourceId, String sql, String status,
                                String errorMsg, int rowCount, long durationMs) {
        try {
            registryJdbc.update("""
                    INSERT INTO query_history
                        (datasource_id, sql_text, status, row_count, duration_ms, error_msg)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, datasourceId, sql, status, rowCount, durationMs, errorMsg);
        } catch (Exception e) {
            log.warn("Failed to record query history", e);
        }
    }
}
