@echo off
rem Weaveora: pi token/cost dashboard (local only, no model calls -> zero tokens)
rem Double-click = default view (anomaly + per-day + top sessions).
rem CLI:  tok_report.cmd day|sessions|compose|images|anomaly [args]
chcp 65001 >nul
setlocal
node "%~dp0tok_report.js" %*
if "%~1"=="" pause
