@echo off
rem ============================================================
rem  Open Platform 消费者示例 —— 代码库问答助手
rem  用法：run.cmd                      跑内置演示（3 个问题）
rem        run.cmd "你的问题"           单次提问
rem        set CODE_QA_MODE=stub 后运行 走离线模型（不消耗额度）
rem  前置：在项目根目录（本脚本上两级）先执行一次
rem        mvn install -DskipTests
rem        mvn -f examples/code-qa/pom.xml package -DskipTests
rem ============================================================
chcp 65001 >nul
rem 本脚本位于 <项目根>\examples\code-qa\，因此上两级就是项目根
cd /d "%~dp0..\.."

rem --- 从项目根 .env 读取模型配置（AI_API_KEY / AI_BASE_URL / AI_MODEL）---
rem     可选：open-platform 不要求 .env，缺失时仅提示并以离线模式继续
if exist ".env" (
    for /f "usebackq tokens=1,* delims==" %%a in (".env") do set "%%a=%%b"
    set "AI_API_KEY=%AI_API_KEY:"=%"
) else (
    echo [WARN] 未找到 .env，将使用离线 STUB 模型。如需真实模型请创建 .env 并填入 AI_API_KEY
    if not defined CODE_QA_MODE set "CODE_QA_MODE=stub"
)

rem --- 模式：默认 live，可用环境变量覆盖为 stub ---
if not defined CODE_QA_MODE set "CODE_QA_MODE=live"

rem --- 构造 classpath（示例 jar + 平台 jar + 传递依赖）---
set "JAR=examples\code-qa\target\open-platform-code-qa-example-0.0.1-SNAPSHOT.jar"
if not exist "%JAR%" (
    echo [ERROR] 示例 jar 不存在，请先执行：mvn -f examples/code-qa/pom.xml package -DskipTests
    exit /b 1
)

set "PLATFORM_JAR=%USERPROFILE%\.m2\repository\com\yin\open-platform\0.0.1-SNAPSHOT\open-platform-0.0.1-SNAPSHOT.jar"
if not exist "%PLATFORM_JAR%" (
    echo [ERROR] 平台 jar 未安装，请先执行：mvn install -DskipTests
    exit /b 1
)

set "DEPS="
if exist "examples\code-qa\target\cp.txt" set /p DEPS=<"examples\code-qa\target\cp.txt"

echo 模型模式: %CODE_QA_MODE%
echo.
java --enable-preview -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 ^
     -cp "%JAR%;%PLATFORM_JAR%;%DEPS%" com.yin.examples.codeqa.CodeQaCli %*
exit /b %ERRORLEVEL%
