# SECURITY

## 报告问题

如果你发现了安全问题，请不要在公开 issue 里贴出：

- API Key
- 完整的聊天记录
- 能定位到个人的信息

请通过 GitHub 的 private security advisory 提交，或在 issue 里只描述现象、不带数据。

## 这个项目会接触到的数据

- **API Key**：加密后存在本机（Android Keystore），不会上传到任何地方。
- **聊天内容**：存在本机 SQLite；只有你自己填写的服务商会收到内容（聊天服务 + 可选的向量服务）。
- **没有**账号体系、统计上报、崩溃收集。

## 已知边界

- 数据库没有额外加密（SQLCipher 会增加约 4MB 原生库），依赖系统文件级加密。
- 应用禁止系统备份（`allowBackup=false`），导出的 JSON 不含密钥。
- 这是一个实验项目，不提供安全保证。
