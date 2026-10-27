package io.github.samlinb.openbone.io;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.regex.*;
import static io.github.samlinb.openbone.i18n.M.m;

/** Explicit source-version routing; fallback approval is tied to the exact source bytes and parser. */
public final class SpineImportPolicy {
    private SpineImportPolicy() {}
    public enum Format { JSON, BINARY, NATIVE }
    public enum Parser {
        JSON_38(Format.JSON,"3.8.99"), JSON_40(Format.JSON,"4.0.64"), JSON_41(Format.JSON,"4.1.24"),
        JSON_42(Format.JSON,"4.2.22"), JSON_43(Format.JSON,"4.3.26"),
        BINARY_38(Format.BINARY,"3.8.99"), BINARY_40(Format.BINARY,"4.0.64"),
        BINARY_41(Format.BINARY,"4.1.24"), BINARY_42(Format.BINARY,"4.2.22"), BINARY_43(Format.BINARY,"4.3.26"),
        NATIVE_38(Format.NATIVE,"3.8.99"), NATIVE_40(Format.NATIVE,"4.0.64"), NATIVE_41(Format.NATIVE,"4.1.24"),
        NATIVE_42(Format.NATIVE,"4.2.22"), NATIVE_43(Format.NATIVE,"4.3.26");
        private final Format format;
        private final String version;
        Parser(Format format,String version){this.format=format;this.version=version;}
        public String version(){return version;}
        public Format format(){return format;}
        public SpineVersion family(){return SpineVersion.identify(version).orElseThrow();}
    }
    public record Approval(String sourceHash,String parserId) {}
    public record Selection(String sourceVersion,Format format,Parser parser,String sourceHash,boolean known) {
        public Approval approval(){return new Approval(sourceHash,parser.name());}
        public String message(){return m("spineImport.versionRisk",sourceVersion,format.name(),parser.version());}
    }
    private record Version(int major,int minor,int patch) implements Comparable<Version> {
        public int compareTo(Version other){
            int c=Integer.compare(major,other.major);if(c!=0)return c;
            c=Integer.compare(minor,other.minor);return c!=0?c:Integer.compare(patch,other.patch);
        }
    }
    private static final Pattern VERSION=Pattern.compile("(\\d{1,4})\\.(\\d{1,4})\\.(\\d{1,6})(?:[-+][A-Za-z0-9.-]{1,64})?");
    private static Version version(String text)throws ProjectFormatException {
        var match=VERSION.matcher(text==null?"":text);
        if(!match.matches())throw new ProjectFormatException(m("spineImport.invalidVersion",text));
        return new Version(Integer.parseInt(match.group(1)),Integer.parseInt(match.group(2)),Integer.parseInt(match.group(3)));
    }
    public static Selection select(byte[] bytes,Format format,Approval approval)throws IOException {
        String source=switch(format){case BINARY->binaryVersion(bytes);case NATIVE->SpineNativeHeader.read(bytes).version();case JSON->new ProjectCodec().parseTree(bytes).path("skeleton").path("spine").asText();};
        Selection selection=format==Format.JSON && source.isEmpty()
                ? new Selection("unspecified",format,Parser.JSON_38,hash(bytes),false)
                : plan(source,format,hash(bytes));
        if(approval!=null&&!selection.approval().equals(approval))throw new ProjectFormatException(m("spineImport.approvalChanged"));
        if(!selection.known()&&approval==null)throw new SpineVersionRiskException(selection);
        return selection;
    }
    public static boolean isJsonExport(byte[] bytes)throws IOException {
        var tree=new ProjectCodec().parseTree(bytes);
        return !tree.has("schemaVersion") && (tree.path("skeleton").has("spine")
                || tree.path("skeleton").isObject() && tree.path("bones").isArray() && tree.path("slots").isArray()
                && (tree.path("skins").isArray() || tree.path("skins").isObject()));
    }
    static Selection plan(String source,Format format,String hash)throws ProjectFormatException {
        Version actual=version(source);
        Parser known=null;
        var family=SpineVersion.identify(source);
        if(family.isPresent())for(var candidate:Parser.values())if(candidate.format()==format&&candidate.family()==family.get())known=candidate;
        // The public 3.8 reader explicitly rejects this historical binary layout revision.
        if(format==Format.BINARY&&"3.8.75".equals(source))known=null;
        if(known!=null)return new Selection(source,format,known,hash,true);
        var candidates=new ArrayList<Parser>();for(var parser:Parser.values())if(parser.format()==format)candidates.add(parser);
        candidates.sort(Comparator.comparing(p->{try{return version(p.version());}catch(IOException impossible){throw new IllegalStateException(impossible);}}));
        // Prefer the nearest newer implementation. Without a newer one, the newest older is nearest.
        Parser chosen=candidates.get(candidates.size()-1);
        for(var parser:candidates)if(version(parser.version()).compareTo(actual)>=0){chosen=parser;break;}
        return new Selection(source,format,chosen,hash,false);
    }
    private static String hash(byte[] bytes){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    /** 4.x uses a raw 8-byte hash; older exports may use a length-prefixed hash string. */
    static String binaryVersion(byte[] bytes)throws IOException {
        if(bytes.length>ProjectCodec.MAX_BYTES)throw new ProjectFormatException(m("spineBinary.tooLarge"));
        try{var in=new ByteArrayInputStream(bytes);in.skipNBytes(8);String value=headerText(in);version(value);return value;}
        catch(IOException modern){
            try{var in=new ByteArrayInputStream(bytes);headerText(in);String value=headerText(in);version(value);return value;}
            catch(IOException older){throw new ProjectFormatException(m("spineImport.invalidHeader"),modern);}
        }
    }
    private static String headerText(ByteArrayInputStream in)throws IOException {
        int n=0;
        for(int shift=0;;shift+=7){int b=in.read();if(b<0||shift>21)throw new EOFException();n|=(b&127)<<shift;if((b&128)==0)break;}
        if(n==0)return null;
        if(n>4097||n-1>in.available())throw new EOFException();
        return new String(in.readNBytes(n-1),StandardCharsets.UTF_8);
    }
}
