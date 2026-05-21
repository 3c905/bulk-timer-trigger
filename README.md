# Bulk Timer Trigger

Jenkins 插件：批量设置/清空 Job 的定时构建 Trigger，支持模糊匹配、预览确认、响应式布局。

## 功能

- 🔍 **模糊匹配**：输入正则表达式，快速筛选出需要设置的 Job
- ✅ **勾选确认**：匹配结果支持复选框全选/取消，避免误操作
- ⏰ **时间设置**：默认当年该日 21:00，支持自定义执行时间，自动校验格式
- 📋 **预览确认**：展示即将设置的 Job 列表和具体执行时间，确认后才生效
- 🗑️ **清空定时**：一键清空匹配 Job 的所有定时构建设置，避免历史定时自动触发
- 🔄 **自动 Reload**：操作后可选自动 Reload Jenkins（后台异步执行，避免 403）
- 🏥 **健康检测**：提供 `/health` 端点，供外部监控检查插件状态

## 环境要求

- Jenkins ≥ 2.555.2（Jakarta EE）
- OpenJDK 21
- Maven 3.9.6+

## 技术要点

本次版本针对 Jenkins 2.555.2 (Jakarta EE) 做了以下核心适配：

| 适配项 | 说明 |
|--------|------|
| **parent POM** | `5.27`，支持 Jakarta EE 命名空间 |
| **Stapler API** | `StaplerRequest` → `StaplerRequest2`，`StaplerResponse` → `StaplerResponse2` |
| **hpi-plugin** | `3.1814`（支持 Java 21），通过 `webResources` 配置确保 `.class` 正确打包 |
| **Cron 格式** | 5 字段标准格式（分 时 日 月 星期），如 `0 21 20 5 *`（每年 5 月 20 日 21:00）|
| **Trigger 管理** | 使用 `removeTrigger` + `addTrigger` 操作 `AbstractProject`，兼容 Jenkins 2.479+ |

## 构建

```bash
mvn clean package -Dmaven.test.skip=true
```

构建产物：`target/bulk-timer-trigger.hpi`

> 注：`-Dmaven.test.skip=true` 用于跳过 `InjectedTest` 测试编译（Jenkins 测试 harness 依赖问题）。

## 安装

1. 打开 Jenkins → Manage Jenkins → Plugins → Advanced
2. 选择 `bulk-timer-trigger.hpi` 上传
3. **重启 Jenkins**（必需，`RootAction` 扩展点需重启才能注册）

## 使用

安装后，在 Jenkins 左侧菜单找到 **Bulk Timer Trigger**，点击进入：

### 设置定时构建

1. 输入 Job 名称正则（如 `edc-.*`）→ 点击 🔍 **搜索**
2. 勾选需要设置的 Job（表头 checkbox 支持全选/取消全选）
3. 填写执行时间（格式 `YYYY-MM-DD HH:mm`，如 `2026-05-20 21:00`）
   - 输入框旁实时显示中文格式时间，如 `（2026年5月20日21点）`
   - 时间已过时自动智能顺延：本月→第二天；非本月→调整为当月
4. 可选勾选 **应用后自动 Reload Jenkins**
5. 点击 📋 **预览执行计划**
6. 核对确认页信息后，点击 ✅ **确认执行**

> 设置后 Job 将**每年在该日期**自动触发构建（Cron 格式如 `0 21 20 5 *`）。

### 清空定时构建

1. 搜索需要清空的 Job（正则匹配）
2. 直接点击 🗑️ **清空所有定时**
3. 在确认对话框中确认后，系统自动移除匹配 Job 的所有定时 Trigger

> 清空操作同样支持 **应用后自动 Reload Jenkins**。

## 页面特性

| 特性 | 说明 |
|------|------|
| **响应式布局** | 依据屏幕分辨率自动适配列数，最小列宽 240px，大屏约 6 列 |
| **Job 名称换行** | 超长 Job 名称自动换行显示，不再截断为 `...` |
| **定时标签高亮** | 已设置定时的 Job 显示绿色背景标签，未设置显示灰色标签 |
| **视觉风格** | 绿色圆角按钮、emoji 图标，整体布局紧凑清爽 |

## 健康检测

插件提供健康检测端点，无需登录即可访问基础状态：

```
GET {JENKINS_URL}/bulk-timer-trigger/health
```

返回示例：
```json
{
  "status": "ok",
  "jenkins_version": "2.555.2",
  "permission_ok": true,
  "job_accessible": true,
  "trigger_available": true,
  "message": "healthy"
}
```

## 常见问题

**Q: 安装后访问 `/bulk-timer-trigger` 返回 404？**
A: 确保已**重启 Jenkins**。`RootAction` 扩展点必须在 Jenkins 启动时注册。

**Q: 设置后定时没有生效？**
A: 检查 Jenkins 日志中是否有 `ANTLRException` 异常。通常是因为 cron 格式不合法。本插件使用标准 5 字段 Jenkins cron 格式。

**Q: 操作结果显示"成功: 0 个，失败: N 个"？**
A: 查看 Jenkins 系统日志（`Failed to set trigger for ...`），可看到具体的异常堆栈。

**Q: 勾选"应用后自动 Reload Jenkins"后返回 403？**
A: 请升级到此版本。旧版本中 Reload 为同步执行会阻塞 HTTP 响应，现已改为后台异步执行。

## License

MIT
