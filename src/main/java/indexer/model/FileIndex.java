package indexer.model;
import java.util.ArrayList;
import java.util.List;
public class FileIndex {
    public String file;
    public String pkg;
    public String type;
    public String typeDesc;
    public List<MemberInfo> fields = new ArrayList<>();
    public List<MemberInfo> constructors = new ArrayList<>();
    public List<MemberInfo> methods = new ArrayList<>();
    public List<InnerInfo> inner = new ArrayList<>();
}