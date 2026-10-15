package com.tovarika.tech.cards.editing.application;

/** Single rounding algorithm shared by admission and raster processing. */
public record ResizePlan(int width,int height,int scaledWidth,int scaledHeight,int left,int top,boolean aiRequired) {
    public static ResizePlan of(int sourceWidth,int sourceHeight,String ratio,String mode) {
        if(sourceWidth<=0 || sourceHeight<=0) throw new IllegalArgumentException("Missing source dimensions");
        int[] size=switch(ratio) {
            case "1:1" -> new int[]{1024,1024}; case "3:4" -> new int[]{1152,1536};
            case "4:5" -> new int[]{1024,1280}; case "16:9" -> new int[]{1536,864};
            default -> throw new IllegalArgumentException("Unsupported ratio");
        };
        int w=size[0],h=size[1];
        double scale="crop".equals(mode)?Math.max((double)w/sourceWidth,(double)h/sourceHeight)
                :Math.min((double)w/sourceWidth,(double)h/sourceHeight);
        int sw=Math.max(1,(int)Math.round(sourceWidth*scale)),sh=Math.max(1,(int)Math.round(sourceHeight*scale));
        return new ResizePlan(w,h,sw,sh,(w-sw)/2,(h-sh)/2,"outpaint".equals(mode) && (sw!=w || sh!=h));
    }
}
