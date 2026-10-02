package indexer;
import indexer.model.FileIndex;
import indexer.model.InnerInfo;
import indexer.model.MemberInfo;

import java.util.List;
public class TxtRenderer {
    public String renderCompact(FileIndex file) {
        StringBuilder sb = new StringBuilder();

        sb.append(file.file).append('\n');
        sb.append(file.type == null ? "(unknown)" : file.type).append('\n');
        if (file.typeDesc != null) {
            sb.append("// ").append(file.typeDesc).append('\n');
        }

        renderMembersCompact(sb, file.fields, "fields");
        renderMembersCompact(sb, file.constructors, "ctors");
        renderMembersCompact(sb, file.methods, "methods");

        for (InnerInfo inner : file.inner) {
            renderInnerCompact(sb, inner, 0);
        }

        sb.append('\n');
        return sb.toString();
    }

    private void renderInnerCompact(StringBuilder sb, InnerInfo inner, int depth) {
        String indent = "  ".repeat(depth);
        sb.append(indent).append(inner.kind).append(": ").append(inner.signature).append('\n');
        if (inner.desc != null) {
            sb.append(indent).append("// ").append(inner.desc).append('\n');
        }
        renderMembersCompactIndent(sb, inner.fields, "fields", depth + 1);
        renderMembersCompactIndent(sb, inner.constructors, "ctors", depth + 1);
        renderMembersCompactIndent(sb, inner.methods, "methods", depth + 1);
        for (InnerInfo nested : inner.inner) {
            renderInnerCompact(sb, nested, depth + 1);
        }
    }

    private void renderMembersCompact(StringBuilder sb, List<MemberInfo> items, String title) {
        renderMembersCompactIndent(sb, items, title, 0);
    }

    private void renderMembersCompactIndent(StringBuilder sb, List<MemberInfo> items,
                                            String title, int depth) {
        if (items == null || items.isEmpty()) return;
        String indent = "  ".repeat(depth);
        sb.append(indent).append(title).append(":\n");
        String itemIndent = "  ".repeat(depth + 1);
        for (MemberInfo m : items) {
            sb.append(itemIndent).append(m.signature);
            if (m.desc != null) {
                sb.append("  // ").append(m.desc);
            }
            sb.append('\n');
        }
    }
    public String renderCalls(FileIndex file) {
        StringBuilder sb = new StringBuilder();
        boolean hasAny = renderCallsFor(sb, file.constructors, null)
                | renderCallsFor(sb, file.methods, null);
        for (InnerInfo inner : file.inner) {
            hasAny |= renderCallsInner(sb, inner, null);
        }
        if (!hasAny) return "";
        return sb.toString();
    }
    private boolean renderCallsInner(StringBuilder sb, InnerInfo inner, String prefix) {
        String scope = (prefix == null ? "" : prefix + ".") + inner.signature;
        boolean has = renderCallsFor(sb, inner.constructors, scope)
                | renderCallsFor(sb, inner.methods, scope);
        for (InnerInfo nested : inner.inner) {
            has |= renderCallsInner(sb, nested, scope);
        }
        return has;
    }
    private boolean renderCallsFor(StringBuilder sb, List<MemberInfo> items, String scope) {
        if (items == null || items.isEmpty()) return false;
        boolean any = false;
        for (MemberInfo m : items) {
            if (m.calls == null || m.calls.length == 0) continue;
            String owner = (scope == null ? "" : scope + "::") + m.name;
            sb.append(owner).append('\n');
            for (String call : m.calls) {
                sb.append("  -> ").append(call).append('\n');
            }
            any = true;
        }
        return any;
    }
    public String renderCallers(String pkg, List<CallerEntry> entries) {
        if (entries == null || entries.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();

        String currentTarget = null;
        for (CallerEntry e : entries) {
            if (!e.target.equals(currentTarget)) {
                currentTarget = e.target;
                sb.append(currentTarget).append('\n');
            }
            sb.append("  <- ").append(e.caller);
            if (e.file != null) {
                sb.append("  (").append(e.file).append(')');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public static class CallerEntry {
        public String target;
        public String caller;
        public String file;

        public CallerEntry(String target, String caller, String file) {
            this.target = target;
            this.caller = caller;
            this.file = file;
        }
    }
}