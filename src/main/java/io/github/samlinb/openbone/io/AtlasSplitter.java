package io.github.samlinb.openbone.io;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import io.github.samlinb.openbone.application.MeshEdits;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.viewport.ImageAssets;

/** Extracts logical, untrimmed images without modifying the imported skeleton or atlas. */
public final class AtlasSplitter {
    private AtlasSplitter() {}

    public record Result(DocumentSnapshot document, Path directory, int images) {}
    private record Key(Path page, AtlasRegion region) {}

    /** Only offer extraction for a same-named atlas with imported PNG pages beside the JSON. */
    public static boolean available(Path json, DocumentSnapshot document) {
        Path source=json.toAbsolutePath().normalize();
        Path atlas=source.resolveSibling(Spine40Binary.stem(source)+".atlas");
        if(!Files.isRegularFile(atlas) && !Files.isRegularFile(source.resolveSibling(Spine40Binary.stem(source)+".atlas.txt"))) return false;
        try {
            var regions=AtlasSubset.read(source,true,new ArrayList<>());
            if(regions==null || regions.isEmpty() || regions.values().stream().anyMatch(r->!Files.isRegularFile(r.path()))) return false;
            return document.attachments().stream().filter(a->a.source()!=null).anyMatch(a->{
                Path page=Path.of(a.source()).toAbsolutePath().normalize();
                return page.getParent().equals(source.getParent()) && page.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")
                        && Files.isRegularFile(page) && regions.values().stream().anyMatch(r->r.path().equals(page)
                                && Objects.equals(r.metadata(),document.resources().atlas().get(a.id())));
            });
        } catch(IOException|InvalidPathException invalid) { return false; }
    }

    public static Result split(Path json, DocumentSnapshot document) throws IOException {
        if(!available(json,document)) throw new IOException("No imported PNG and atlas beside JSON");
        Path parent=json.toAbsolutePath().normalize().getParent();
        String name=json.getFileName().toString(); int dot=name.lastIndexOf('.');
        String prefix=(dot<0?name:name.substring(0,dot)).replaceAll("[^\\p{L}\\p{N}._-]","_");
        if(prefix.length()>80) prefix=prefix.substring(0,80);
        Path directory=Files.createTempDirectory(parent,prefix+"_images-");
        try {
            var pages=new HashMap<Path,BufferedImage>();
            var extracted=new LinkedHashMap<Key,Path>();
            var usedNames=new HashSet<String>();
            var attachments=new ArrayList<Attachment>();
            var metadata=new HashMap<>(document.resources().atlas());
            for(var attachment:document.attachments()) {
                if(attachment.source()==null) { attachments.add(attachment); continue; }
                Path page=Path.of(attachment.source()).toAbsolutePath().normalize();
                var region=metadata.get(attachment.id());
                var key=new Key(page,region);
                Path output=extracted.get(key);
                if(output==null) {
                    BufferedImage pixels=pages.get(page);
                    if(pixels==null) { pixels=ImageAssets.decodePng(RuntimePng.readCompatible(page)); pages.put(page,pixels); }
                    BufferedImage image=unpack(pixels,region);
                    String relative=attachment.name()+".png";
                    try { ResourcePaths.contained(directory,relative); }
                    catch(IOException|InvalidPathException invalid) { relative="region_"+extracted.size()+".png"; }
                    String base=relative.substring(0,relative.length()-4);
                    int suffix=1;
                    while(!usedNames.add(relative.toLowerCase(Locale.ROOT))) relative=base+"_"+(++suffix)+".png";
                    output=ResourcePaths.contained(directory,relative);
                    Files.createDirectories(output.getParent());
                    if(!ImageIO.write(image,"PNG",output.toFile())) throw new IOException("PNG writer unavailable");
                    extracted.put(key,output);
                }
                attachments.add(MeshEdits.source(attachment,output.toString()));
                if (region != null && region.premultiplied()) {
                    metadata.put(attachment.id(), new AtlasRegion(region.originalWidth(), region.originalHeight(),
                            0,0,region.originalWidth(),region.originalHeight(),region.originalWidth(),region.originalHeight(),0,0,false,true));
                } else metadata.remove(attachment.id());
            }
            var resources=document.resources();
            var result=new DocumentSnapshot(document.bones(),document.slots(),attachments,document.animations(),document.constraints(),
                    new Resources(resources.skins(),resources.activeSkin(),resources.events(),metadata,resources.metadata(),resources.activation()));
            return new Result(result,directory,extracted.size());
        } catch(IOException|RuntimeException failure) {
            // This directory was freshly created for this operation. Never clean source resources.
            if(directory.toAbsolutePath().normalize().getParent().equals(parent)) {
                try(var files=Files.walk(directory)) {
                    for(Path file:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
                } catch(IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    static BufferedImage unpack(BufferedImage page,AtlasRegion region) throws IOException {
        if(region==null) {
            var result=new BufferedImage(page.getWidth(),page.getHeight(),BufferedImage.TYPE_INT_ARGB);
            for(int y=0;y<page.getHeight();y++) for(int x=0;x<page.getWidth();x++) result.setRGB(x,y,page.getRGB(x,y));
            return result;
        }
        if(page.getWidth()!=region.pageWidth() || page.getHeight()!=region.pageHeight())
            throw new IOException("Atlas page dimensions changed before extraction");
        var result=new BufferedImage(region.originalWidth(),region.originalHeight(),BufferedImage.TYPE_INT_ARGB);
        int top=region.originalHeight()-region.offsetY()-region.height();
        for(int y=0;y<region.height();y++) for(int x=0;x<region.width();x++) {
            int sourceX=region.x()+switch(region.degrees()) {
                case 90 -> y;
                case 180 -> region.width()-1-x;
                case 270 -> region.height()-1-y;
                default -> x;
            };
            int sourceY=region.y()+switch(region.degrees()) {
                case 90 -> region.width()-1-x;
                case 180 -> region.height()-1-y;
                case 270 -> x;
                default -> y;
            };
            result.setRGB(region.offsetX()+x,top+y,page.getRGB(sourceX,sourceY));
        }
        return result;
    }
}
