package ru.cherdakmd.chrdkregister;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Read-only проверка, что все загруженные CHRDK-плагины принадлежат одному комплекту. */
final class BundleVersionAudit {
    record Mismatch(String plugin, String expected, String actual) { }

    private BundleVersionAudit() { }

    static List<Mismatch> find(Map<String, String> pluginVersions, String coreVersion) {
        if (pluginVersions == null || coreVersion == null || coreVersion.isBlank()) {
            return List.of(new Mismatch("CHRDK_Core", coreVersion, "unknown"));
        }
        var sorted = new TreeMap<>(pluginVersions);
        var result = new ArrayList<Mismatch>();
        for (var row : sorted.entrySet()) {
            if (row.getKey() != null && row.getKey().startsWith("CHRDK_")
                    && !coreVersion.equals(row.getValue())) {
                result.add(new Mismatch(row.getKey(), coreVersion, row.getValue()));
            }
        }
        return List.copyOf(result);
    }

    static String summary(List<Mismatch> mismatches) {
        if (mismatches == null || mismatches.isEmpty()) return "";
        var text = new StringBuilder();
        for (var row : mismatches) {
            if (!text.isEmpty()) text.append("; ");
            text.append(row.plugin()).append("=").append(row.actual())
                    .append(" (Core=").append(row.expected()).append(')');
        }
        return text.toString();
    }
}
