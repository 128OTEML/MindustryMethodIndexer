package indexer.model;
import java.util.ArrayList;
import java.util.List;
public class InnerInfo {
    public String kind;
    public String signature;
    public String simpleName;
    public String desc;
    public List<MemberInfo> fields = new ArrayList<>();
    public List<MemberInfo> constructors = new ArrayList<>();
    public List<MemberInfo> methods = new ArrayList<>();
    public List<InnerInfo> inner = new ArrayList<>();
}