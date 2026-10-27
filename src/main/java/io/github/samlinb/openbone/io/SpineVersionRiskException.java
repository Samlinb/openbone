package io.github.samlinb.openbone.io;

/** A request for explicit user choice, raised before parsing an unverified source version. */
public final class SpineVersionRiskException extends ProjectFormatException {
    private final SpineImportPolicy.Selection selection;
    public SpineVersionRiskException(SpineImportPolicy.Selection selection){super(selection.message());this.selection=selection;}
    public SpineImportPolicy.Selection selection(){return selection;}
}
