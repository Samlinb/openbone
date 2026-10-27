package io.github.samlinb.openbone.io;

import java.io.*;
import java.util.zip.*;
import static io.github.samlinb.openbone.i18n.M.m;

/** Bounded inspection of the native raw-DEFLATE container. Not an object graph deserializer. */
public record SpineNativeHeader(int format,String version) {
    public static SpineNativeHeader read(byte[] source)throws IOException {
        if(source.length>64*1024*1024)throw new ProjectFormatException(m("spineNative.tooLarge"));
        var inflater=new Inflater(true);
        try(var stream=new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(source),inflater,256))) {
            int format=varint(stream);
            if(format<13||format>1024||stream.readInt()!=-1340035040)throw new ProjectFormatException(m("spineNative.invalidHeader"));
            String version=asciiVersion(stream);
            if(!version.matches("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,6}(?:-beta)?"))throw new ProjectFormatException(m("spineNative.invalidHeader"));
            return new SpineNativeHeader(format,version);
        } catch(EOFException|ZipException invalid) {throw new ProjectFormatException(m("spineNative.invalidHeader"),invalid);}
        finally{inflater.end();}
    }
    private static int varint(DataInputStream in)throws IOException {
        int value=0;for(int shift=0;shift<35;shift+=7){int b=in.readUnsignedByte();value|=(b&127)<<shift;if((b&128)==0)return value;}throw new IOException(m("spineNative.invalidHeader"));
    }
    private static String asciiVersion(DataInputStream in)throws IOException {
        int first=in.readUnsignedByte();var text=new StringBuilder();
        if((first&128)!=0) {
            // Native Kryo UTF-8 path: the version is ASCII, its character count includes one sentinel.
            int length=first&63;if((first&64)!=0)throw new IOException(m("spineNative.invalidHeader"));
            if(length<2||length>65)throw new IOException(m("spineNative.invalidHeader"));
            for(int i=1;i<length;i++){int b=in.readUnsignedByte();if(b>127)throw new IOException(m("spineNative.invalidHeader"));text.append((char)b);}
            return text.toString();
        }
        text.append((char)first);
        for(int i=1;i<64;i++){int b=in.readUnsignedByte();text.append((char)(b&127));if((b&128)!=0)return text.toString();}
        throw new IOException(m("spineNative.invalidHeader"));
    }
}
