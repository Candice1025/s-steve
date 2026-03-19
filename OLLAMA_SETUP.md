# Ollama 集成说明

## 已完成的修改

1. **创建了 OllamaClient.java**
   - 位置: `src/main/java/com/steve/ai/ai/OllamaClient.java`
   - 支持 OpenAI 兼容的 API 格式
   - 支持 Ollama 原生格式
   - 包含重试机制和错误处理

2. **更新了 SteveConfig.java**
   - 添加了 Ollama 相关配置项：
     - `OLLAMA_API_URL`: API 端点地址
     - `OLLAMA_API_KEY`: API 密钥（可选）
     - `OLLAMA_MODEL`: 模型名称

3. **更新了 TaskPlanner.java**
   - 添加了 Ollama 客户端支持
   - 在 AI provider 选择中加入 "ollama" 选项

4. **更新了配置文件**
   - `config/steve-common.toml` 已配置为使用 Ollama
   - 默认模型: `gpt-oss:120b-cloud`

## 使用方法

### 配置文件设置

编辑 `config/steve-common.toml`:

```toml
[ai]
    provider = "ollama"

[ollama]
    # 云端 API 地址（替换为你的实际地址）
    apiUrl = "https://your-cloud-api.com/v1/chat/completions"
    
    # 如果需要认证，填入 API key
    apiKey = "your-api-key-here"
    
    # 模型名称
    model = "gpt-oss:120b-cloud"
```

### 本地 Ollama 使用

如果使用本地 Ollama:

```toml
[ollama]
    apiUrl = "http://localhost:11434/api/chat"
    apiKey = ""
    model = "llama2"  # 或其他本地模型
```

### 云端 Ollama 使用

如果使用云端服务:

```toml
[ollama]
    apiUrl = "https://your-cloud-endpoint.com/api/chat"
    apiKey = "your-cloud-api-key"
    model = "gpt-oss:120b-cloud"
```

## API 格式支持

OllamaClient 支持多种响应格式：

1. **OpenAI 兼容格式**:
```json
{
  "choices": [{
    "message": {
      "content": "response text"
    }
  }]
}
```

2. **Ollama 原生格式**:
```json
{
  "message": {
    "content": "response text"
  }
}
```

3. **简单格式**:
```json
{
  "response": "response text"
}
```

## 重新编译

修改代码后需要重新编译:

```bash
./gradlew build
./gradlew runClient
```

## 测试

1. 启动 Minecraft
2. 进入游戏
3. 执行命令: `/steve spawn Bob`
4. 按 `K` 键打开面板
5. 输入指令测试，例如: "mine some iron"

日志会显示使用的 AI provider 和请求状态。

## 故障排查

如果遇到问题，检查日志文件中的错误信息：
- 位置: `run/logs/latest.log`
- 搜索 "Ollama" 关键词查看相关日志

常见问题：
1. **连接失败**: 检查 `apiUrl` 是否正确
2. **认证失败**: 检查 `apiKey` 是否有效
3. **模型不存在**: 确认 `model` 名称正确
