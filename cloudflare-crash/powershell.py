import subprocess
import os
import sys

def open_powershell_in_current_dir():
    # 获取当前工作目录
    current_dir = os.getcwd()

    if sys.platform.startswith("win"):
        # Windows 下启动 PowerShell
        subprocess.Popen(
            ["powershell.exe", "-NoExit"],  # -NoExit 保持窗口打开
            cwd=current_dir,
            creationflags=subprocess.CREATE_NEW_CONSOLE  # 新开一个控制台窗口
        )
    else:
        print("当前系统不是 Windows，无法启动 PowerShell")

if __name__ == "__main__":
    open_powershell_in_current_dir()