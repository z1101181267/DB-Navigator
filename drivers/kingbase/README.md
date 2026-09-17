# KingbaseES JDBC Driver

Place the following JAR files in this directory:

## drivers/kingbase/9.0.0/ (Active)
- `kingbase8-9.0.0.jar` — KingbaseES V8 JDBC Driver 9.0.0
  - Download: KingbaseES official website (人大金仓)
  - Source: http://www.kingbase.com.cn/

## Driver Class
```
com.kingbase8.Driver
```

## Connection URL Example
```
jdbc:kingbase8://192.168.1.100:54321/mydb
```

## Default Port
54321

## Notes
- KingbaseES is a PostgreSQL-compatible database developed by Renmin University (人大金仓)
- Protocol is PostgreSQL-compatible but uses its own JDBC driver class
- Default user is typically "system" (not "postgres")
