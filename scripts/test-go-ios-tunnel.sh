#!/bin/bash
#
# go-iOS隧道方案测试脚本
# 用法: ./test-go-ios-tunnel.sh <udid> <xcode-project-path> [iterations]
#

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 参数检查
if [ $# -lt 2 ]; then
    echo -e "${RED}错误: 参数不足${NC}"
    echo "用法: $0 <udid> <xcode-project-path> [iterations]"
    echo "示例: $0 00008030-000434891E91802E /Users/xxx/WebDriverAgent/WebDriverAgent.xcodeproj 3"
    exit 1
fi

UDID=$1
XCODE_PROJECT=$2
ITERATIONS=${3:-3}

echo -e "${GREEN}==============================================${NC}"
echo -e "${GREEN}  go-iOS隧道方案测试${NC}"
echo -e "${GREEN}==============================================${NC}"
echo ""
echo "设备UDID: $UDID"
echo "Xcode项目: $XCODE_PROJECT"
echo "测试轮数: $ITERATIONS"
echo ""

# 检查先决条件
echo -e "${YELLOW}[1/5] 检查先决条件...${NC}"

# 检查ios命令
if ! command -v ios &> /dev/null; then
    echo -e "${RED}错误: 未找到 'ios' 命令${NC}"
    echo "请先安装 go-ios: https://github.com/danielpaulus/go-ios"
    exit 1
fi

echo -e "${GREEN}✓ go-iOS 已安装${NC}"
ios --version

# 检查Xcode项目
if [ ! -d "$XCODE_PROJECT" ] && [ ! -f "$XCODE_PROJECT" ]; then
    echo -e "${RED}错误: Xcode项目路径不存在: $XCODE_PROJECT${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Xcode项目存在${NC}"

# 检查设备连接
echo -e "${YELLOW}[2/5] 检查设备连接...${NC}"
DEVICE_LIST=$(ios list 2>/dev/null || echo "")
if ! echo "$DEVICE_LIST" | grep -q "$UDID"; then
    echo -e "${RED}错误: 设备 $UDID 未连接或未配对${NC}"
    echo "已连接设备:"
    ios list
    exit 1
fi
echo -e "${GREEN}✓ 设备已连接${NC}"

# 检查iOS版本
echo -e "${YELLOW}[3/5] 检查设备iOS版本...${NC}"
VERSION=$(ios info --udid=$UDID 2>/dev/null | grep -o '"ProductVersion":"[^"]*"' | cut -d'"' -f4 || echo "unknown")
echo "设备iOS版本: $VERSION"

if [ "$VERSION" != "unknown" ]; then
    MAJOR_VERSION=$(echo $VERSION | cut -d'.' -f1)
    if [ "$MAJOR_VERSION" -lt 17 ]; then
        echo -e "${YELLOW}⚠ 警告: 设备iOS版本为 $VERSION，go-iOS隧道方案主要适用于iOS 17+${NC}"
        read -p "是否继续测试? [y/N] " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            exit 1
        fi
    fi
fi

# 检查隧道支持
echo -e "${YELLOW}[4/5] 检查go-iOS隧道支持...${NC}"
if ios tunnel --help &> /dev/null; then
    echo -e "${GREEN}✓ go-iOS支持隧道命令${NC}"
else
    echo -e "${RED}✗ go-iOS版本过旧，不支持隧道命令${NC}"
    echo "请升级到最新版本: go install github.com/danielpaulus/go-ios@latest"
    exit 1
fi

# 检查WDA安装
echo -e "${YELLOW}[5/5] 检查WDA安装状态...${NC}"
WDA_BUNDLE_ID="com.weoqa.WebDriverAgentRunnerTester"
APP_LIST=$(ios apps --udid=$UDID 2>/dev/null || echo "")
if echo "$APP_LIST" | grep -q "$WDA_BUNDLE_ID"; then
    echo -e "${GREEN}✓ WDA已安装在设备上${NC}"
else
    echo -e "${YELLOW}⚠ WDA未检测到，请先安装:${NC}"
    echo "  1. 构建WDA: xcodebuild -project WebDriverAgent.xcodeproj -scheme WebDriverAgentRunner -destination 'id=$UDID' build-for-testing"
    echo "  2. 安装WDA: ios install --path=/path/to/WebDriverAgentRunner-Runner.app --udid=$UDID"
    read -p "是否继续测试? [y/N] " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

echo ""
echo -e "${GREEN}==============================================${NC}"
echo -e "${GREEN}  开始测试${NC}"
echo -e "${GREEN}==============================================${NC}"
echo ""

# 编译测试类
echo -e "${YELLOW}编译测试类...${NC}"
cd $(dirname $0)/..
mvn compile -q

if [ $? -ne 0 ]; then
    echo -e "${RED}编译失败${NC}"
    exit 1
fi

# 运行测试
echo -e "${YELLOW}运行A/B对比测试...${NC}"
echo ""

java -cp "target/classes:$(mvn dependency:build-classpath -q -DincludeScope=compile -Dmdep.outputFile=/dev/stdout)" \
    org.cloud.sonic.agent.bridge.ios.SibToolGoIosTest \
    "$UDID" \
    "$XCODE_PROJECT" \
    "$ITERATIONS"

echo ""
echo -e "${GREEN}==============================================${NC}"
echo -e "${GREEN}  测试完成${NC}"
echo -e "${GREEN}==============================================${NC}"
echo ""
echo "提示:"
echo "1. 查看上面的测试结果对比"
echo "2. JSON格式的详细结果可用于进一步分析"
echo "3. 建议多次运行测试以获得更准确的统计数据"
echo ""
