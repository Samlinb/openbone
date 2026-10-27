package io.github.samlinb.openbone.io;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import io.github.samlinb.openbone.application.MeshEdits;
import io.github.samlinb.openbone.core.model.*;
import io.github.samlinb.openbone.viewport.ImageAssets;

/** One logical image can be shared by attachments in several slots or skins. */
public final class ImageResources {
    private ImageResources() {}
    public record Key(Path source, AtlasRegion region) {}
    public record Entry(Key key, String name, List<EntityId> attachments) {
        public Entry { attachments=List.copyOf(attachments); }
        @Override public String toString() { return name; }
    }

    public static List<Entry> list(DocumentSnapshot document, Path base) throws IOException {
        var ids=new LinkedHashMap<Key,List<EntityId>>();
        var names=new LinkedHashMap<Key,String>();
        for(var attachment:document.attachments()) {
            if(attachment.source()==null) continue;
            var key=new Key(ResourcePaths.resolve(base,attachment.source()),document.resources().atlas().get(attachment.id()));
            ids.computeIfAbsent(key,k->new ArrayList<>()).add(attachment.id());
            names.putIfAbsent(key,attachment.name());
        }
        return ids.entrySet().stream().map(e->new Entry(e.getKey(),names.get(e.getKey()),e.getValue())).toList();
    }

    public static BufferedImage preview(Entry entry) throws IOException {
        return AtlasSplitter.unpack(ImageAssets.readPng(entry.key().source()),entry.key().region());
    }

    /** Worker-owned cache: a packed page is decoded once for all of its logical regions. */
    public static class PreviewCache {
        private final Map<Path,BufferedImage> pages=new HashMap<>();
        private final Map<Path,IOException> failures=new HashMap<>();
        protected BufferedImage read(Path source) throws IOException { return ImageAssets.readPng(source); }
        public BufferedImage preview(Entry entry) throws IOException {
            Path source=entry.key().source();
            if(failures.containsKey(source))throw failures.get(source);
            var page=pages.get(source);
            if(page==null) {
                try {page=read(source);pages.put(source,page);}
                catch(IOException invalid) {failures.put(source,invalid);throw invalid;}
            }
            return AtlasSplitter.unpack(page,entry.key().region());
        }
    }

    /** Relink every reference to this logical image; retain IDs, geometry, size and all timelines. */
    public static DocumentSnapshot replace(DocumentSnapshot document, Path base, Entry entry, Path replacement) throws IOException {
        Path source=replacement.toAbsolutePath().normalize();
        ImageAssets.readPng(source); // Validate before publishing any model change.
        var current=list(document,base).stream().filter(e->e.key().equals(entry.key())).findFirst()
                .orElseThrow(()->new IOException("Image resource is no longer present"));
        var ids=new HashSet<>(current.attachments());
        var attachments=document.attachments().stream()
                .map(a->ids.contains(a.id())?MeshEdits.source(a,source.toString()):a).toList();
        var atlas=new HashMap<>(document.resources().atlas());
        ids.forEach(atlas::remove); // Replacement is an independent logical image, not a packed page.
        var resources=document.resources();
        return new DocumentSnapshot(document.bones(),document.slots(),attachments,document.animations(),document.constraints(),
                new Resources(resources.skins(),resources.activeSkin(),resources.events(),atlas,resources.metadata(),resources.activation()));
    }
}
