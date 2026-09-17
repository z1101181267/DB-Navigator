# SQL Server JDBC Driver

Place the following JAR files in this directory:

## drivers/sqlserver/13.4.0.jre11/ (Active)
- `mssql-jdbc-13.4.0.jre11.jar` — Microsoft SQL Server JDBC Driver 13.4.0
  - Download: https://central.sonatype.com/artifact/com.microsoft.sqlserver/mssql-jdbc/13.4.0.jre11
  - Maven: `com.microsoft.sqlserver:mssql-jdbc:13.4.0.jre11`

## Driver Class
```
com.microsoft.sqlserver.jdbc.SQLServerDriver
```

## Connection URL Example
```
jdbc:sqlserver://192.168.1.100:1433;databaseName=mydb;encrypt=false;trustServerCertificate=true
```

## Default Port
1433
