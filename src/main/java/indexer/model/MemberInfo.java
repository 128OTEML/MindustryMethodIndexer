package indexer.model;
public class MemberInfo {
    public String kind;
    public String signature;
    public String desc;
    public String[] overloads;
    public String name;
    public String returnType;
    public String[] modifiers;
    public ParamInfo[] parameters;
    public String[] throwsList;
    public String[] annotations;
    public int line;
    public String[] calls;
    public int callCount;
}