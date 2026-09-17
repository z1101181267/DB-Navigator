# MySQL JDBC Driver

Place the following JAR files in this directory:

## drivers/mysql/8.0.33/ (Active - recommended)
- `mysql-connector-j-8.0.33.jar` — MySQL Connector/J 8.0.33
  - Download: https://central.sonatype.com/artifact/com.mysql/mysql-connector-j/8.0.33
  - Maven: `com.mysql:mysql-connector-j:8.0.33`

## drivers/mysql/9.7.0/ (Latest)
- `mysql-connector-j-9.7.0.jar` — MySQL Connector/J 9.7.0
  - Maven: `com.mysql:mysql-connector-j:9.7.0`

## Driver Class
```
com.mysql.cj.jdbc.Driver
```

## Connection URL Example
```
jdbc:mysql://192.168.1.100:3306/mydb?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true&characterEncoding=utf8
```

## Default Port
3306
