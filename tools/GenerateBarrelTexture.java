import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;

/** Native 16x16 pixel asset source. No resampling, antialiasing, blur or generated-image conversion. */
public final class GenerateBarrelTexture {
    private static int pixel(int x,int y){
        int edge=Math.min(Math.min(x,y),Math.min(15-x,15-y));
        int color=switch(edge){case 0->0;case 1->3;case 2->5;default->2;};
        if((x==2 || x==13) && (y==2 || y==13))color=4;
        int u=Math.abs(2*x-15),v=Math.abs(2*y-15);
        if((u<=1 && v<=11) || (v<=1 && u<=11))color=8;
        if(Math.max(u,v)<=7 && u+v<=10){
            color=6;
            if(Math.max(u,v)<=5 && u+v<=8)color=7;
            if(Math.max(u,v)<=3)color=8;
            if(u+v<=4)color=9;
            if(u<=1 && v<=1)color=10;
        }
        return color;
    }
    private static BufferedImage texture(boolean active){
        String colors=active?"26303b,3d4854,65717d,9ba7b3,c8d0d7,485563,12363f,156d79,28a9b7,5bdde2,acf4e7":"26303b,3d4854,65717d,9ba7b3,c8d0d7,485563,102a30,163e45,1b525b,29646a,438087";
        int[] palette=Arrays.stream(colors.split(",")).mapToInt(c->0xff000000|Integer.parseInt(c,16)).toArray();
        var result=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<16;y++){
            for(int x=0;x<16;x++){
                int c=pixel(x,y);
                if(c!=pixel(15-x,15-y) || c!=pixel(15-y,x))throw new IllegalStateException("Symmetry violation");
                result.setRGB(x,y,palette[c]);
            }
        }
        return result;
    }
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args.length==0?".":args[0]).toAbsolutePath().normalize();
        Path textures=root.resolve("src/main/resources/assets/toms_virtual_storage/textures/block");Files.createDirectories(textures);
        var inactive=texture(false);var active=texture(true);
        ImageIO.write(inactive,"png",textures.resolve("tom_barrel.png").toFile());
        ImageIO.write(active,"png",textures.resolve("tom_barrel_active.png").toFile());
        var preview=new BufferedImage(512,256,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<256;y++)for(int x=0;x<512;x++)preview.setRGB(x,y,(x<256?inactive:active).getRGB((x%256)/16,y/16));
        Path out=root.resolve("docs/tvsn/art");Files.createDirectories(out);ImageIO.write(preview,"png",out.resolve("tom-barrel-off-on-preview.png").toFile());
        for(String name:List.of("tom_barrel.png","tom_barrel_active.png")){
            var read=ImageIO.read(textures.resolve(name).toFile());if(read.getWidth()!=16 || read.getHeight()!=16)throw new IllegalStateException("Texture dimensions changed");
        }
        System.out.println("Created two true 16x16, 11-color textures and an integer nearest-neighbor preview; left=inactive, right=active.");
    }
}
