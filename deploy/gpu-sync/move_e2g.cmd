@echo off
robocopy "E:\ComfyUI\weaveora" "G:\ComfyUI\weaveora" /E /MOVE /MT:16 /R:1 /W:1 /NP /NFL /NDL /LOG:"G:\ComfyUI\_mirror\logs\move-e2g.log"
echo MOVE_EXIT=%ERRORLEVEL% %DATE% %TIME% > "G:\ComfyUI\_mirror\logs\move-e2g.status"
