package com.mobile.standalone.service.pack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * DevEco 自动签名的 profile 可能不带 {@code READ_PASTEBOARD} ACL，
 * 安装时报 9568289。
 * <p>
 * 只有「本次实际用来签名的那份 .p7b」里没有该 ACL 时，才临时从
 * {@code entry/src/main/module.json5} 去掉权限声明（不提交，下次同步前由
 * {@link HarmonyGitSync} 还原），保证包能装上；此时抖音开箱读剪贴板不可用。
 */
final class HarmonyPasteboardPermissionPatch {

    static final String PERMISSION = "ohos.permission.READ_PASTEBOARD";
    static final String MODULE_REL = "entry/src/main/module.json5";

    private static final Pattern PERM_OBJECT = Pattern.compile(
            ",?\\s*\\{\\s*\"name\"\\s*:\\s*\"ohos\\.permission\\.READ_PASTEBOARD\"\\s*,"
                    + "\\s*\"reason\"\\s*:\\s*\"[^\"]*\"\\s*,"
                    + "\\s*\"usedScene\"\\s*:\\s*\\{[^}]*\\}\\s*\\}",
            Pattern.MULTILINE);

    private HarmonyPasteboardPermissionPatch() {
    }

    /**
     * @param signingName 本次产品使用的 signingConfig 名
     * @return 给打包日志的说明；未改动则 {@code null}
     */
    static String applyIfProfileLacksAcl(Path projectDir, String signingName) {
        Path module = projectDir.resolve(MODULE_REL);
        Path buildProfile = projectDir.resolve("build-profile.json5");
        if (!Files.isRegularFile(module) || !Files.isRegularFile(buildProfile)) {
            return null;
        }
        try {
            String text = Files.readString(module, StandardCharsets.UTF_8);
            if (!text.contains(PERMISSION)) {
                return null;
            }
            Path profile = HarmonyLocalSigning.profileOf(
                    Files.readString(buildProfile, StandardCharsets.UTF_8), signingName);
            // 找不到 profile 时签名本身就会失败，不在这里改权限掩盖问题
            if (profile == null || profileMentionsPasteboard(profile)) {
                return null;
            }
            String next = stripPermission(text);
            if (next.equals(text)) {
                return null;
            }
            Files.writeString(module, next, StandardCharsets.UTF_8);
            return "签名证书（" + signingName + " → " + profile.getFileName()
                    + "）没有剪贴板权限 READ_PASTEBOARD，本次已临时去掉该权限以便安装，"
                    + "抖音开箱读剪贴板不可用。要测该功能，需在 DevEco 重新签发带该权限的证书。";
        } catch (Exception e) {
            return null;
        }
    }

    static String stripPermission(String moduleJson5) {
        return PERM_OBJECT.matcher(moduleJson5).replaceAll("").replaceAll(",\\s*,", ",");
    }

    static boolean profileMentionsPasteboard(Path p7b) {
        try {
            String ascii = new String(Files.readAllBytes(p7b), StandardCharsets.ISO_8859_1);
            return ascii.contains("READ_PASTEBOARD");
        } catch (Exception e) {
            return false;
        }
    }
}
