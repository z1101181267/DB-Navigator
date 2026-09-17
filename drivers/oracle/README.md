# Oracle JDBC Driver

Place the following JAR files in this directory:

## drivers/oracle/8/ (Active - recommended for Oracle 12c/19c/21c)
- `ojdbc8.jar` — Oracle JDBC driver for JDK 8+
  - Download: https://central.sonatype.com/artifact/com.oracle.database.jdbc/ojdbc8
  - Maven: `com.oracle.database.jdbc:ojdbc8:23.3.0.23.09`

## drivers/oracle/6/ (Legacy - Oracle 11g R2)
- `ojdbc6.jar` — Oracle JDBC driver for JDK 6
  - Download: Oracle Technology Network (requires Oracle account)

## drivers/oracle/19.20.0.0/ (Latest - Oracle 19c)
- `ojdbc11.jar` — Oracle JDBC driver for JDK 11+
  - Maven: `com.oracle.database.jdbc:ojdbc11:23.3.0.23.09`

## Driver Class
```
oracle.jdbc.OracleDriver
```

## Connection URL Examples
```
# Service Name (default):
jdbc:oracle:thin:@//192.168.1.100:1521/ORCL

# SID mode (alternative):
jdbc:oracle:thin:@192.168.1.100:1521:ORCL
```

## Default Port
1521
