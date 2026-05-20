# Bulk Timer Trigger

Jenkins 插件 —— 批量设置 Job 的定时构建 Trigger。

## 功能

- 🔍 **模糊匹配**：输入正则表达式，快速筛选出需要设置的 Job
- ✅ **勾选确认**：匹配结果支持复选框选择，避免误操作
- ⏰ **时间设置**：默认当天 21:00，支持自定义执行时间，自动校验格式
- 📋 **预览确认**：展示即将设置的 Job 列表和具体执行时间（年月日小时分钟），确认后才生效
- 🔄 **自动 Reload**：应用后可选自动 Reload Jenkins 配置

## 环境要求

- Jenkins ≥ 2.479（Jakarta EE）
- OpenJDK 17 / 21
- Maven 3.9.6+

## 构建

```bash
mvn clean package -Dmaven.test.skip=true
```

构建产物：`target/bulk-timer-trigger.hpi`

## 安装

1. 打开 Jenkins → Manage Jenkins → Plugins → Advanced
2. 选择 `bulk-timer-trigger.hpi` 上传
3. 重启 Jenkins

## 使用

安装后，在 Jenkins 左侧菜单找到 **Bulk Timer Trigger**，点击进入：

1. 输入 Job 名称正则（如 `edc-.*`）→ 点击搜索
2. 勾选需要设置的 Job
3. 填写执行时间（格式 `YYYY-MM-DD HH:mm`）
4. 点击 **预览执行计划**
5. 核对确认页信息后，点击 **确认执行**

## 截图

（待补充）

## License

MIT
