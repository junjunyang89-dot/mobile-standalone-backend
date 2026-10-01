package com.mobile.standalone.service.buildhost;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 构建机 Maven 缓存检查的命令和输出解析。
 * <p>
 * 远程只拼已经校验过的坐标，避免把版本号当路径穿越。
 */
public final class MavenCacheCommand {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern JAR_LINE = Pattern.compile(
            ".*?\\s(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2})\\s+(\\S+\\.jar)\\s*$");

    private MavenCacheCommand() {
    }

    public static String requireToken(String raw, String label) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty() || !TOKEN.matcher(v).matches()) {
            throw new IllegalArgumentException(label + " 只能是字母数字和 ._-，收到的不像 Maven 坐标");
        }
        return v;
    }

    public static String groupPath(String groupId) {
        String g = requireToken(groupId.replace('/', '.'), "groupId").replace('.', '/');
        if (g.contains("..")) {
            throw new IllegalArgumentException("groupId 不合法");
        }
        return g;
    }

    public static String inspectScript(String configuredRepo, String workspace,
                                      String groupId, String artifactId, String version) {
        String repo = configuredRepo == null ? "" : configuredRepo.trim();
        String ws = workspace == null ? "" : workspace.trim();
        String g = groupPath(groupId);
        String a = requireToken(artifactId, "artifactId");
        String v = requireToken(version, "version");
        StringBuilder sb = new StringBuilder();
        sb.append("set +e; ");
        if (!repo.isEmpty()) {
            sb.append("if [ -d ").append(shQuote(repo)).append(" ]; then REPO=").append(shQuote(repo)).append("; ");
            sb.append("elif [ -d \"$HOME/.m2/repository\" ]; then REPO=\"$HOME/.m2/repository\"; ");
            sb.append("elif [ -d /root/.m2/repository ]; then REPO=/root/.m2/repository; ");
            sb.append("else REPO=; fi; ");
        } else {
            sb.append("if [ -d \"$HOME/.m2/repository\" ]; then REPO=\"$HOME/.m2/repository\"; ");
            sb.append("elif [ -d /root/.m2/repository ]; then REPO=/root/.m2/repository; ");
            sb.append("else REPO=; fi; ");
        }
        sb.append("echo REPO=$REPO; ");
        sb.append("if [ -z \"$REPO\" ]; then echo M2_MISSING; exit 0; fi; ");
        sb.append("DIR=\"$REPO/").append(g).append('/').append(a).append('/').append(v).append("\"; ");
        sb.append("echo DIR=$DIR; ");
        sb.append("if [ ! -d \"$DIR\" ]; then echo DIR_MISSING; else ");
        sb.append("echo BEGIN_JARS; ");
        sb.append("ls -l --time-style=long-iso \"$DIR\"/*.jar 2>/dev/null || ls -l \"$DIR\"/*.jar 2>/dev/null; ");
        sb.append("echo END_JARS; fi; ");
        if (!ws.isEmpty()) {
            sb.append("WS=").append(shQuote(ws)).append("; ");
            sb.append("if [ -d \"$WS\" ]; then echo BEGIN_POMS; ");
            sb.append("grep -R --include=pom.xml -n ").append(shQuote(a)).append(" \"$WS\" 2>/dev/null | grep -v '/.git/' | head -n 40; ");
            sb.append("echo END_POMS; else echo WS_MISSING; fi; ");
        }
        return sb.toString();
    }

    public static String cleanScript(String configuredRepo, String groupId, String artifactId, String version) {
        String inspect = inspectScript(configuredRepo, "", groupId, artifactId, version);
        return inspect + "if [ -n \"$DIR\" ] && [ -d \"$DIR\" ]; then rm -rf -- \"$DIR\"; echo CLEANED=$DIR; else echo CLEAN_SKIP; fi; ";
    }

    public static List<String> sshArgv(String user, String host, int port, String identityFile, String remoteScript) {
        if (host == null || host.isBlank() || user == null || user.isBlank()) {
            throw new IllegalStateException("还没配构建机 SSH：设置 BUILD_SSH_HOST / BUILD_SSH_USER");
        }
        List<String> cmd = new ArrayList<>();
        cmd.add("ssh");
        cmd.add("-o");
        cmd.add("BatchMode=yes");
        cmd.add("-o");
        cmd.add("ConnectTimeout=10");
        cmd.add("-o");
        cmd.add("StrictHostKeyChecking=accept-new");
        if (port > 0 && port != 22) {
            cmd.add("-p");
            cmd.add(String.valueOf(port));
        }
        if (identityFile != null && !identityFile.isBlank()) {
            cmd.add("-i");
            cmd.add(identityFile.trim());
        }
        cmd.add(user.trim() + "@" + host.trim());
        cmd.add(remoteScript);
        return cmd;
    }

    public record JarRow(String name, String date, String time) {
    }

    public static List<JarRow> parseJars(String output) {
        List<JarRow> out = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return out;
        }
        boolean in = false;
        for (String line : output.split("\\R")) {
            if ("BEGIN_JARS".equals(line.trim())) {
                in = true;
                continue;
            }
            if ("END_JARS".equals(line.trim())) {
                break;
            }
            if (!in) {
                continue;
            }
            Matcher m = JAR_LINE.matcher(line);
            if (m.matches()) {
                String name = m.group(3);
                if (name.contains("sources") || name.contains("javadoc")) {
                    continue;
                }
                out.add(new JarRow(name, m.group(1), m.group(2)));
            }
        }
        return out;
    }

    static String shQuote(String raw) {
        return "'" + raw.replace("'", "'\"'\"'") + "'";
    }
}
