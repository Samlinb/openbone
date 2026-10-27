package io.github.samlinb.openbone.io;

import java.util.*;

/** Audited major/minor wire families, independent of the internal interchange version. */
public enum SpineVersion {
    V38("3.8", "3.8.99"), V40("4.0", "4.0.64"), V41("4.1", "4.1.24"),
    V42("4.2", "4.2.22"), V43("4.3", "4.3.26");

    private final String family, exportVersion;
    SpineVersion(String family, String exportVersion) { this.family=family; this.exportVersion=exportVersion; }
    public String family() { return family; }
    public String exportVersion() { return exportVersion; }
    public boolean legacy() { return this==V38; }
    public boolean atLeast(SpineVersion other) { return ordinal()>=other.ordinal(); }
    public static Optional<SpineVersion> identify(String version) {
        return Arrays.stream(values()).filter(v->version!=null&&version.matches(java.util.regex.Pattern.quote(v.family)+"\\.[0-9]+" )).findFirst();
    }
    public static SpineVersion target(String version) throws ProjectFormatException {
        return identify(version).orElseThrow(()->ProjectCodec.bad("$.skeleton.spine","Unsupported export version: "+version));
    }
    public static String[] exportVersions() {
        return new String[]{V43.exportVersion,V42.exportVersion,V41.exportVersion,V40.exportVersion,V38.exportVersion};
    }
}
