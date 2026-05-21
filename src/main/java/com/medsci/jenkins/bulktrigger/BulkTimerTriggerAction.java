package com.medsci.jenkins.bulktrigger;

import hudson.Extension;
import hudson.model.RootAction;
import hudson.model.AbstractProject;
import hudson.triggers.TimerTrigger;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

import org.kohsuke.stapler.verb.POST;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Jenkins 批量定时构建设置插件
 *
 * 访问地址: {JENKINS_URL}/bulk-timer-trigger
 *
 * 功能：
 *   1. 输入正则模糊匹配 Job，勾选需要设置的具体 Job
 *   2. 定时时间默认当天晚上 21:00，允许修改，验证格式并给出错误提示
 *   3. 预览确认：展示哪些 Job 将在何时（年月日小时分钟）被执行
 *
 * 适配：Jenkins 2.555.2 (Jakarta EE) / OpenJDK 21 / parent POM 5.x
 */
@Extension
public class BulkTimerTriggerAction implements RootAction {

    private static final Logger LOGGER = Logger.getLogger(BulkTimerTriggerAction.class.getName());

    private static final DateTimeFormatter INPUT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DISPLAY_FMT = DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH时mm分");

    @Override
    public String getIconFileName() {
        // 使用 Jenkins 核心内置的 clock 图标，避免依赖 ionicons-api
        return "clock";
    }

    @Override
    public String getDisplayName() {
        return "Bulk Timer Trigger";
    }

    @Override
    public String getUrlName() {
        return "bulk-timer-trigger";
    }

    /**
     * Stapler 入口：GET 请求到 /bulk-timer-trigger 时渲染 index.jelly
     */
    public void doIndex(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        req.getView(this, "index").forward(req, rsp);
    }

    /**
     * 健康检测端点：GET /bulk-timer-trigger/health
     * 供外部监控或 Jenkins 自身检查插件状态，无需登录即可访问基础状态
     */
    public void doHealth(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        rsp.setContentType("application/json;charset=UTF-8");
        rsp.setStatus(200);

        Jenkins jenkins = Jenkins.get();
        boolean permissionOk = jenkins.hasPermission(Jenkins.ADMINISTER);
        boolean jobAccessible = false;
        boolean triggerAvailable = false;
        String message = "healthy";

        try {
            jenkins.getAllItems(AbstractProject.class);
            jobAccessible = true;
        } catch (Throwable t) {
            message = "unable to access jobs: " + t.getMessage();
        }

        try {
            new TimerTrigger("0 0 * * *");
            triggerAvailable = true;
        } catch (Throwable t) {
            message = "timer trigger unavailable: " + t.getMessage();
        }

        String version = "";
        try {
            hudson.util.VersionNumber vn = jenkins.getVersion();
            version = (vn != null) ? vn.toString() : "unknown";
        } catch (Throwable t) {
            version = "unknown";
        }

        // 简单的 JSON 转义，防止特殊字符破坏输出
        version = version.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
        message = message.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");

        String json = "{" +
            "\"status\":\"ok\"," +
            "\"jenkins_version\":\"" + version + "\"," +
            "\"permission_ok\":" + permissionOk + "," +
            "\"job_accessible\":" + jobAccessible + "," +
            "\"trigger_available\":" + triggerAvailable + "," +
            "\"message\":\"" + message + "\"" +
            "}";
        rsp.getWriter().print(json);
    }

    /**
     * 获取默认执行时间（当天晚上 21:00）
     */
    public String getDefaultScheduleTime() {
        return LocalDate.now().toString() + " 21:00";
    }

    /**
     * 搜索匹配的 Job（供 Jelly 页面展示）
     */
    public List<JobMatch> searchJobs(String pattern) {
        List<JobMatch> result = new ArrayList<>();
        Pattern regex = null;
        if (pattern != null && !pattern.isEmpty()) {
            regex = Pattern.compile(pattern);
        }
        Jenkins jenkins = Jenkins.get();
        for (AbstractProject<?, ?> job : jenkins.getAllItems(AbstractProject.class)) {
            if (regex == null || regex.matcher(job.getFullName()).find()) {
                String currentCron = "";
                for (Trigger<?> t : job.getTriggers().values()) {
                    if (t instanceof TimerTrigger) {
                        currentCron = ((TimerTrigger) t).getSpec();
                        break;
                    }
                }
                result.add(new JobMatch(job.getFullName(), currentCron));
            }
        }
        return result;
    }

    /**
     * 预览执行计划：验证时间、生成 Cron、展示确认页
     */
    @POST
    public void doPreview(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);

        String timeStr = req.getParameter("scheduleTime");
        String[] selectedJobs = req.getParameterValues("selectedJobs");
        String pattern = req.getParameter("pattern");

        if (selectedJobs == null || selectedJobs.length == 0) {
            rsp.sendRedirect(buildRedirectUrl("no_jobs_selected", timeStr, pattern));
            return;
        }

        // 验证时间格式
        LocalDateTime scheduleTime;
        try {
            if (timeStr == null || timeStr.trim().isEmpty()) {
                throw new DateTimeParseException("Empty time", "", 0);
            }
            scheduleTime = LocalDateTime.parse(timeStr.trim(), INPUT_FMT);
        } catch (DateTimeParseException e) {
            rsp.sendRedirect(buildRedirectUrl("invalid_time", timeStr, pattern));
            return;
        }

        // 时间调整策略
        boolean adjusted = false;
        LocalDateTime now = LocalDateTime.now();
        if (scheduleTime.isBefore(now)) {
            if (scheduleTime.getYear() == now.getYear()
                    && scheduleTime.getMonthValue() == now.getMonthValue()) {
                // 本月，时间已过 → 本月第二天
                scheduleTime = scheduleTime.plusDays(1);
            } else {
                // 非本月，且是过去 → 本月本日 21:00（若已过则明天 21:00）
                scheduleTime = now.withHour(21).withMinute(0).withSecond(0).withNano(0);
                if (scheduleTime.isBefore(now)) {
                    scheduleTime = scheduleTime.plusDays(1);
                }
            }
            adjusted = true;
        }

        // 生成 Cron 表达式（每年该日期执行）
        String cron = String.format("%d %d %d %d *",
            scheduleTime.getMinute(),
            scheduleTime.getHour(),
            scheduleTime.getDayOfMonth(),
            scheduleTime.getMonthValue());

        req.setAttribute("scheduleTime", scheduleTime.format(DISPLAY_FMT));
        req.setAttribute("cron", cron);
        req.setAttribute("adjusted", adjusted);
        req.setAttribute("selectedJobs", selectedJobs);
        req.setAttribute("originalTime", timeStr.trim());
        req.setAttribute("jobCount", selectedJobs.length);
        req.setAttribute("reloadParam", req.getParameter("reload"));

        req.getView(this, "preview").forward(req, rsp);
    }

    /**
     * 表单提交处理（真正执行设置）
     */
    @POST
    public void doApply(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);

        String cron = req.getParameter("cron");
        String[] selectedJobs = req.getParameterValues("selectedJobs");
        boolean doReload = "on".equals(req.getParameter("reload"));

        if (cron == null || cron.isEmpty() || selectedJobs == null || selectedJobs.length == 0) {
            rsp.sendRedirect(".");
            return;
        }

        Jenkins jenkins = Jenkins.get();
        int success = 0;
        int fail = 0;

        for (String jobName : selectedJobs) {
            AbstractProject<?, ?> job = jenkins.getItemByFullName(jobName, AbstractProject.class);
            if (job == null) {
                fail++;
                continue;
            }
            try {
                // 移除旧的 TimerTrigger
                List<TriggerDescriptor> toRemove = new ArrayList<>();
                for (Map.Entry<TriggerDescriptor, Trigger<?>> entry : job.getTriggers().entrySet()) {
                    if (entry.getValue() instanceof TimerTrigger) {
                        toRemove.add(entry.getKey());
                    }
                }
                for (TriggerDescriptor td : toRemove) {
                    job.removeTrigger(td);
                }

                // 添加新的 TimerTrigger（5 字段 cron：分 时 日 月 星期）
                job.addTrigger(new TimerTrigger(cron));
                job.save();
                success++;
            } catch (Exception e) {
                LOGGER.warning("Failed to set trigger for " + jobName + ": " + e);
                e.printStackTrace();
                fail++;
            }
        }

        rsp.sendRedirect(".?result=done&success=" + success + "&fail=" + fail);

        // Reload Jenkins 在后台异步执行，避免阻塞当前 HTTP 响应导致 403
        if (success > 0 && doReload) {
            final Jenkins j = jenkins;
            new Thread("bulk-timer-trigger-reload") {
                @Override
                public void run() {
                    try {
                        Thread.sleep(2000);
                        j.reload();
                    } catch (Exception e) {
                        LOGGER.warning("Background reload failed: " + e.getMessage());
                    }
                }
            }.start();
        }
    }

    /**
     * 清空所有匹配 Job 的定时构建设置
     */
    @POST
    public void doClearAll(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);

        String pattern = req.getParameter("pattern");
        boolean doReload = "on".equals(req.getParameter("reload"));

        Jenkins jenkins = Jenkins.get();
        int success = 0;
        int fail = 0;

        Pattern regex = null;
        if (pattern != null && !pattern.isEmpty()) {
            regex = Pattern.compile(pattern);
        }

        for (AbstractProject<?, ?> job : jenkins.getAllItems(AbstractProject.class)) {
            if (regex != null && !regex.matcher(job.getFullName()).find()) {
                continue;
            }
            try {
                List<TriggerDescriptor> toRemove = new ArrayList<>();
                for (Map.Entry<TriggerDescriptor, Trigger<?>> entry : job.getTriggers().entrySet()) {
                    if (entry.getValue() instanceof TimerTrigger) {
                        toRemove.add(entry.getKey());
                    }
                }
                if (!toRemove.isEmpty()) {
                    for (TriggerDescriptor td : toRemove) {
                        job.removeTrigger(td);
                    }
                    job.save();
                    success++;
                }
            } catch (Exception e) {
                LOGGER.warning("Failed to clear trigger for " + job.getFullName() + ": " + e);
                e.printStackTrace();
                fail++;
            }
        }

        rsp.sendRedirect(".?result=done&success=" + success + "&fail=" + fail);

        // Reload Jenkins 在后台异步执行，避免阻塞当前 HTTP 响应导致 403
        if (success > 0 && doReload) {
            final Jenkins j = jenkins;
            new Thread("bulk-timer-trigger-reload") {
                @Override
                public void run() {
                    try {
                        Thread.sleep(2000);
                        j.reload();
                    } catch (Exception e) {
                        LOGGER.warning("Background reload failed: " + e.getMessage());
                    }
                }
            }.start();
        }
    }

    private String buildRedirectUrl(String error, String timeStr, String pattern) {
        StringBuilder sb = new StringBuilder(".?error=").append(error);
        if (timeStr != null) {
            sb.append("&time=").append(URLEncoder.encode(timeStr, StandardCharsets.UTF_8));
        }
        if (pattern != null) {
            sb.append("&pattern=").append(URLEncoder.encode(pattern, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * Job 匹配结果的数据对象
     */
    public static class JobMatch {
        private final String name;
        private final String currentCron;

        public JobMatch(String name, String currentCron) {
            this.name = name;
            this.currentCron = currentCron;
        }

        public String getName() {
            return name;
        }

        public String getCurrentCron() {
            return currentCron;
        }

        public boolean hasTrigger() {
            return currentCron != null && !currentCron.isEmpty();
        }
    }
}
