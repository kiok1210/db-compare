@echo off
set JAVA_HOME=C:\Program Files\Java\jdk1.8.0_202
set PATH=D:\apache-maven-3.9.0\bin;%JAVA_HOME%\bin;%PATH%
cd /d D:\db-compare
mvn -q -DskipTests org.codehaus.mojo:exec-maven-plugin:3.1.0:java -Dexec.classpathScope=test -Dexec.mainClass=com.gcoll.dbcompare.loadtest.BigTableLoadGenerator -Dexec.args="jdbc:mysql://127.0.0.1:3306/loadtest?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC sync sync123 %1 %2 big_sync_60m"
exit /b %ERRORLEVEL%
