#!/bin/bash

# 清理 Mac 系统生成的 ._ 资源分叉文件和 .DS_Store 文件
# 使用方法: ./clean-mac-files.sh 或 bash clean-mac-files.sh

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 获取脚本所在目录
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR"

echo -e "${YELLOW}开始清理 Mac 系统文件...${NC}"

# 统计变量
COUNT_DOT_UNDERSCORE=0
COUNT_DS_STORE=0

# 清理 ._ 文件（包括所有目录，包括 .idea）
echo -e "${YELLOW}清理 ._ 资源分叉文件...${NC}"
# 先统计数量
COUNT_DOT_UNDERSCORE=$(find . -name "._*" -type f 2>/dev/null | wc -l | tr -d ' ')

if [ "$COUNT_DOT_UNDERSCORE" -gt 0 ]; then
    # 删除文件并显示进度
    find . -name "._*" -type f 2>/dev/null | while read -r file; do
        if [ -f "$file" ]; then
            rm -f "$file"
            echo -e "  删除: ${file}"
        fi
    done
    echo -e "  已删除 ${GREEN}${COUNT_DOT_UNDERSCORE}${NC} 个 ._ 文件"
else
    echo -e "  未找到 ._ 文件"
fi

# 清理 .DS_Store 文件
echo -e "${YELLOW}清理 .DS_Store 文件...${NC}"
COUNT_DS_STORE=$(find . -name ".DS_Store" -type f 2>/dev/null | wc -l | tr -d ' ')

if [ "$COUNT_DS_STORE" -gt 0 ]; then
    find . -name ".DS_Store" -type f -delete 2>/dev/null
    echo -e "  已删除 ${GREEN}${COUNT_DS_STORE}${NC} 个 .DS_Store 文件"
else
    echo -e "  未找到 .DS_Store 文件"
fi

# 显示统计信息
echo ""
echo -e "${GREEN}清理完成！${NC}"
echo -e "  删除 ._ 文件: ${GREEN}${COUNT_DOT_UNDERSCORE}${NC} 个"
echo -e "  删除 .DS_Store 文件: ${GREEN}${COUNT_DS_STORE}${NC} 个"
echo -e "  总计: ${GREEN}$((COUNT_DOT_UNDERSCORE + COUNT_DS_STORE))${NC} 个文件"

# 如果没有任何文件被删除，给出提示
if [ $((COUNT_DOT_UNDERSCORE + COUNT_DS_STORE)) -eq 0 ]; then
    echo -e "${GREEN}没有找到需要清理的文件。${NC}"
fi

# 验证清理结果
REMAINING=$(find . -name "._*" -type f 2>/dev/null | wc -l | tr -d ' ')
if [ "$REMAINING" -gt 0 ]; then
    echo -e "${YELLOW}警告: 仍有 ${REMAINING} 个 ._ 文件未被删除${NC}"
    echo -e "${YELLOW}请使用以下命令手动删除:${NC}"
    echo -e "  find . -name '._*' -type f -delete"
fi
