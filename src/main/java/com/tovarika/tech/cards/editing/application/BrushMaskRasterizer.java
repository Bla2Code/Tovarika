package com.tovarika.tech.cards.editing.application;

import com.tovarika.tech.cards.editing.domain.ImageEdit;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

/** Builds a binary alpha mask in source pixels, without fetching any client-supplied asset or URL. */
final class BrushMaskRasterizer {
    private BrushMaskRasterizer() {}

    static BufferedImage rasterize(int width,int height,ImageEdit.BrushMask selection) {
        selection.validate();
        var mask=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) mask.setRGB(x,y,0xff000000);
        var graphics=mask.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_OFF);
            graphics.setComposite(AlphaComposite.Clear);
            for(var stroke:selection.strokes()) {
                double radius=stroke.radius()*Math.min(width,height);
                graphics.setStroke(new BasicStroke((float)(radius*2),BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
                var first=stroke.points().getFirst();
                var path=new Path2D.Double();path.moveTo(first.x()*width,first.y()*height);
                for(var point:stroke.points()) path.lineTo(point.x()*width,point.y()*height);
                graphics.draw(path);
                // A click and repeated stationary points must also produce a round footprint.
                graphics.fill(new Ellipse2D.Double(first.x()*width-radius,first.y()*height-radius,radius*2,radius*2));
            }
        } finally { graphics.dispose(); }
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) if((mask.getRGB(x,y)>>>24)==0) return mask;
        throw new IllegalArgumentException("Brush mask selects no source pixels");
    }
}
