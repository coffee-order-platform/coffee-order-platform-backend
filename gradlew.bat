@rem Gradle startup script for Windows
@if "%DEBUG%"=="" @echo off
setlocal
set "APP_HOME=%~dp0"
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"
set "JAVA_EXE=java.exe"
if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%~n0" -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
