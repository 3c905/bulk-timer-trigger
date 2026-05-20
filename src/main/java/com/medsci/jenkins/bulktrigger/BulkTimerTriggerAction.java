package com.medsci.jenkins.bulktrigger;

import hudson.Extension;
import hudson.model.RootAction;
import hudson.model.AbstractProject;
import hudson.triggers.TimerTrigger;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.View;
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

    /**
     * Stapler 入口：GET 请求到 /bulk-timer-trigger 时直接渲染 index.jelly
     */
    public void doIndex(StaplerRequest req, StaplerResponse rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        View view = req.getView(this, "index");
        if (view == null) {
            rsp.sendError(jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND, "index view not found");
            return;
        }
        view.forward(req, rsp);
    }

    @Override
    public String getUrlName() {
        return "bulk-timer-trigger";
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
        if (pattern == null || pattern.isEmpty()) {
            return result;
        }
        Pattern regex = Pattern.compile(pattern);
        Jenkins jenkins = Jenkins.get();
        for (AbstractProject<?, ?> job : jenkins.getAllItems(AbstractProject.class)) {
            if (regex.matcher(job.getFullName()).find()) {
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
    public void doPreview(StaplerRequest req, StaplerResponse rsp) throws Exception {
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

        // 如果时间在当前时间之前，自动调整为明天同一时间
        boolean adjusted = false;
        LocalDateTime now = LocalDateTime.now();
        if (scheduleTime.isBefore(now)) {
            scheduleTime = scheduleTime.plusDays(1);
            adjusted = true;
        }

        // 生成 Cron 表达式（每天该时间执行）
        String cron = String.format("0 %d %d * * *", scheduleTime.getMinute(), scheduleTime.getHour());

        req.setAttribute("scheduleTime", scheduleTime.format(DISPLAY_FMT));
        req.setAttribute("cron", cron);
        req.setAttribute("adjusted", adjusted);
        req.setAttribute("selectedJobs", selectedJobs);
        req.setAttribute("originalTime", timeStr.trim());
        req.setAttribute("jobCount", selectedJobs.length);

        View view = req.getView(this, "preview");
        if (view == null) {
            rsp.sendError(jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND, "preview view not found");
            return;
        }
        view.forward(req, rsp);
    }

    /**
     * 表单提交处理（真正执行设置）
     */
    @POST
    public void doApply(StaplerRequest req, StaplerResponse rsp) throws Exception {
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
                // 移除旧的 TimerTrigger（通过 TriggerDescriptor，兼容 Jenkins 2.479+）
                List<TriggerDescriptor> toRemove = new ArrayList<>();
                Map<TriggerDescriptor, Trigger<?>> triggers = job.getTriggers();
                for (Map.Entry<TriggerDescriptor, Trigger<?>> entry : triggers.entrySet()) {
                    if (entry.getValue() instanceof TimerTrigger) {
                        toRemove.add(entry.getKey());
                    }
                }
                for (TriggerDescriptor td : toRemove) {
                    job.removeTrigger(td);
                }

                // 添加新的 TimerTrigger
                job.addTrigger(new TimerTrigger(cron));
                job.save();
                success++;
            } catch (Exception e) {
                fail++;
            }
        }

        // Reload Jenkins
        if (success > 0 && doReload) {
            jenkins.reload();
        }

        rsp.sendRedirect(".?result=done&success=" + success + "&fail=" + fail);
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
