# 快速测试指南

## 当前状态

游戏正在启动中... 请等待 Minecraft 窗口打开。

## 发现的问题

从日志中发现：`buildAdvancedHouse()` 方法返回了 `null` 或空列表，导致建造失败。

我已经添加了更详细的调试日志来追踪这个问题。

## 测试步骤

1. **等待游戏完全启动**（会看到 Minecraft 主菜单）

2. **进入游戏世界**
   - 创建新世界或加载现有世界
   - 确保是创造模式（方便测试）

3. **生成 Steve**
   ```
   /steve spawn TestSteve
   ```

4. **测试建造命令**
   ```
   /steve tell TestSteve build a house
   ```

5. **查看日志**
   - 打开 `run/logs/latest.log`
   - 查找这些关键信息：
     - `🏗️ Generating build plan` - 开始生成建造计划
     - `✅ Generated build plan with X blocks` - 成功生成
     - `❌ Generated build plan is NULL or EMPTY` - 失败

## 预期看到的日志

如果修复成功，你应该看到：

```
[INFO] 🏗️ BuildStructureAction starting for structure: house
[INFO]    Task parameters: {structure=house, blocks=[...], dimensions=[9, 6, 9]}
[INFO]    Build materials: [Block{minecraft:cobblestone}, ...]
[INFO]    Build dimensions: 9x6x9 (WxHxD)
[INFO] 🏗️ Generating build plan for type='house' at BlockPos{...}
[INFO] ✅ Generated build plan with 486 blocks
[INFO] Steve 'TestSteve' starting COLLABORATIVE build...
```

## 如果仍然失败

如果看到 `❌ Generated build plan is NULL or EMPTY`，说明 `buildAdvancedHouse()` 方法有问题。

请复制完整的错误日志给我，我会进一步调查。

## 其他测试命令

如果建造不工作，可以测试其他功能确认基本系统正常：

```
/steve tell TestSteve follow me
/steve tell TestSteve go to 100 70 100
/steve tell TestSteve stop
```
