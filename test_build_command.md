# 测试建造命令指南

## 问题诊断

我已经修复了以下问题：

### 1. **任务验证过于严格**
- **问题**: `build` 动作要求必须有 `structure`、`blocks` 和 `dimensions` 三个参数
- **修复**: 现在只需要 `structure` 参数，其他参数是可选的
- **文件**: `src/main/java/com/steve/ai/ai/TaskPlanner.java`

### 2. **增强的调试日志**
- 在 `TaskPlanner.validateAndFilterTasks()` 中添加了详细的任务验证日志
- 在 `ActionExecutor.processNaturalLanguageCommand()` 中添加了任务队列日志
- 在 `BuildStructureAction.onStart()` 中添加了详细的建造流程日志

## 测试步骤

1. **重新编译模组**
   ```cmd
   .\gradlew.bat build
   ```

2. **启动游戏**（如果还没运行）
   ```cmd
   .\gradlew.bat runClient
   ```

3. **在游戏中测试**
   
   a. 生成一个 Steve：
   ```
   /steve spawn TestSteve
   ```
   
   b. 测试简单的建造命令：
   ```
   /steve tell TestSteve build a house
   ```
   
   c. 测试其他命令（确认其他功能正常）：
   ```
   /steve tell TestSteve follow me
   /steve tell TestSteve go away
   ```

4. **查看日志**
   
   日志文件位置：`run/logs/latest.log`
   
   关键日志标记：
   - `🏗️` - 建造动作开始
   - `✅` - 任务验证通过
   - `❌` - 任务验证失败或错误
   - `📋` - 任务队列信息
   - `🎮` - 游戏命令接收

## 预期的日志输出

如果一切正常，你应该看到类似这样的日志：

```
[INFO] ╔════════════════════════════════════════════════════════════════
[INFO] ║ 🎮 GAME COMMAND RECEIVED
[INFO] ║ Steve Name: TestSteve
[INFO] ║ Player Command: "build a house"
[INFO] ║ AI Provider: OLLAMA
[INFO] ╠════════════════════════════════════════════════════════════════
[INFO] ║ 📥 AI RESPONSE RECEIVED
[INFO] ║ ✅ PARSING SUCCESSFUL
[INFO] ║ 🎯 TASKS: 1 task(s) generated
[INFO] ║ Task 1: BUILD - {structure=house, blocks=[oak_planks, cobblestone, glass_pane], dimensions=[9, 6, 9]}
[INFO] ╠════════════════════════════════════════════════════════════════
[INFO] ✅ Task VALID: build with parameters: {structure=house, ...}
[INFO] 📋 Steve 'TestSteve' received 1 tasks from AI
[INFO] 🏗️ BuildStructureAction starting for structure: house
[INFO] ✅ Build plan generated with 486 blocks
[INFO] Steve 'TestSteve' starting COLLABORATIVE build...
```

## 可能的问题和解决方案

### 问题 1: AI 返回 NULL 响应
**日志**: `❌ AI returned NULL response for command`

**原因**: 
- Ollama 服务未运行
- AI 模型返回了无效的 JSON

**解决方案**:
```cmd
# 检查 Ollama 是否运行
curl http://localhost:11434/api/tags

# 如果没运行，启动 Ollama
ollama serve
```

### 问题 2: 任务验证失败
**日志**: `❌ Task INVALID: build with parameters: {...} - Missing required parameters!`

**原因**: AI 返回的参数不完整

**解决方案**: 
- 检查 `src/main/java/com/steve/ai/ai/PromptBuilder.java` 中的提示词
- 确保 AI 模型理解建造指令

### 问题 3: 找不到合适的地面
**日志**: `❌ Cannot find suitable ground for building!`

**原因**: Steve 或玩家附近没有合适的建造地点

**解决方案**:
- 站在平坦的地面上
- 确保视线方向有足够的空间
- 尝试在不同的位置

### 问题 4: 建造计划生成失败
**日志**: `❌ Cannot generate build plan for: house`

**原因**: 结构类型不被识别

**解决方案**:
- 使用支持的结构类型：house, castle, tower, barn, modern, wall, platform
- 检查拼写是否正确

## 支持的建造命令示例

```
/steve tell TestSteve build a house
/steve tell TestSteve build a small wooden cabin
/steve tell TestSteve build a stone castle
/steve tell TestSteve build a modern house
/steve tell TestSteve build a tower
/steve tell TestSteve build a barn
/steve tell TestSteve build a two-story house
```

## 下一步

如果问题仍然存在，请：

1. 复制完整的日志输出（从命令发送到错误发生）
2. 告诉我具体使用的命令
3. 描述 Steve 的行为（完全不动？开始移动但停止？）

这样我可以进一步诊断问题。
