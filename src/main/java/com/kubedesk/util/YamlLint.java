package com.kubedesk.util;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Client-side YAML syntax checking for the apply screen — fast, offline, as-you-type.
 * It only checks that the text is well-formed YAML (and counts documents); it does NOT validate
 * Kubernetes schema (that's the server-side dry-run).
 */
public final class YamlLint {

    private YamlLint() {
    }

    /** Result of a syntax check: {@code ok} plus a short human message. */
    public record LintResult(boolean ok, String message) {
    }

    public static LintResult check(String text) {
        if (text == null || text.isBlank()) {
            return new LintResult(true, "Empty — nothing to lint yet.");
        }
        try {
            int docs = 0;
            for (Object doc : new Yaml().loadAll(text)) {
                if (doc != null) {
                    docs++;
                }
            }
            if (docs == 0) {
                return new LintResult(true, "No documents found.");
            }
            return new LintResult(true, docs + " document" + (docs == 1 ? "" : "s") + " — valid YAML");
        } catch (MarkedYAMLException e) {
            Mark mark = e.getProblemMark();
            String where = mark != null ? "line " + (mark.getLine() + 1)
                    + ", col " + (mark.getColumn() + 1) + ": " : "";
            String problem = e.getProblem() != null ? e.getProblem() : e.getMessage();
            return new LintResult(false, where + problem);
        } catch (YAMLException e) {
            return new LintResult(false, e.getMessage());
        }
    }
}
