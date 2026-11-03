package dev.tpc.testlauncher;

import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.net.URI;

/** Test launcher only. Fabric needs the real entries hidden by Gradle's Windows pathing JAR. */
public final class ExpandedClasspathMain {
    public static void main(String[] args)throws Exception {
        LinkedHashSet<Path> paths=new LinkedHashSet<>();
        for(String part:System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            Path p=Path.of(part).toAbsolutePath().normalize();
            if(Files.isRegularFile(p) && p.getFileName().toString().startsWith("gradle-javaexec-classpath")) {
                try(JarFile jar=new JarFile(p.toFile())) {
                    String manifest=jar.getManifest().getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
                    if(manifest!=null)for(String entry:manifest.split(" ")) {
                        URI uri=p.getParent().toUri().resolve(entry);paths.add(Path.of(uri).toAbsolutePath().normalize());
                    }
                }
            } else paths.add(p);
        }
        System.setProperty("java.class.path",String.join(java.io.File.pathSeparator,paths.stream().map(Path::toString).toList()));
        try {Class.forName("net.fabricmc.devlaunchinjector.Main").getMethod("main",String[].class).invoke(null,(Object)args);}
        catch(java.lang.reflect.InvocationTargetException e){Throwable cause=e.getCause();if(cause instanceof Exception x)throw x;if(cause instanceof Error x)throw x;throw e;}
    }
}
