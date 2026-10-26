package io.github.samlinb.openbone;

import java.util.*;
import io.github.samlinb.openbone.core.math.*;
import io.github.samlinb.openbone.core.model.*;

public final class TestData {
    private TestData() {}
    public static EntityId id(int value) { return new EntityId(new UUID(0,value).toString()); }
    public static Bone bone(int id,String name,EntityId parent,Transform local) { return new Bone(id(id),name,parent,local,80); }
    public static DocumentSnapshot hierarchy() {
        return new DocumentSnapshot(List.of(bone(1,"根骨",null,new Transform(20,-10,30,-1.2,.7)),
                bone(2,"child",id(1),new Transform(100,0,-45,1,2)),
                bone(3,"tip",id(2),new Transform(70,0,65,.5,-1)),bone(4,"other",null,Transform.IDENTITY)),
                List.of(new Slot(id(10),"前景",id(3),id(20)),new Slot(id(11),"other",id(4),id(22))),
                List.of(new RegionAttachment(id(20),"图片",id(10),"贴图/角色.png",new Transform(3,4,12,-1,1),64,48),
                        new RegionAttachment(id(21),"inactive",id(10),"missing.png",Transform.IDENTITY,10,20),
                        new RegionAttachment(id(22),"图片",id(11),"other.png",Transform.IDENTITY,1,1)));
    }
}
