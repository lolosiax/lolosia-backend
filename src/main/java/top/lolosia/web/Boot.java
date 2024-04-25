package top.lolosia.web;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Boot {
    public static void main(String[] args) throws Exception {
        Boot boot = new Boot();
        boot.preInitEnvironment();
        boot.boot(args);
    }

    private final ClassLoader classLoader = Boot.class.getClassLoader();

    private void preInitEnvironment() throws Exception {

        List<String> libFiles = new ArrayList<>(getLibFiles());

        File libPathFile = new File("lib");
        if (!libPathFile.exists()) libPathFile.mkdir();

        File[] files = libPathFile.listFiles((dir, name) -> name.endsWith(".jar"));

        List<String> names = Arrays.stream(files).map(File::getName).toList();
        for (String name : names) {
            if (!libFiles.contains(name)) {
                new File("lib/" + name).delete();
            } else {
                libFiles.remove(name);
            }
        }

        for (String libFile : libFiles) {
            releaseLib(libFile);
        }
    }

    private List<String> getLibFiles() throws Exception {
        byte[] bytes = classLoader.getResourceAsStream("lib/fileList.txt").readAllBytes();
        return Arrays.stream(new String(bytes).split("\n")).toList();
    }

    private void releaseLib(String name) throws IOException {
        File out = new File("lib/" + name);
        FileOutputStream os = new FileOutputStream(out);
        classLoader.getResourceAsStream("lib/" + name).transferTo(os);
    }

    private void boot(String[] args) throws Exception {
        Class<?> clazz = Class.forName("top.lolosia.web.LolosiaApplication");
        try {
            clazz.getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (NoSuchMethodException e) {
            clazz.getMethod("main").invoke(null);
        }
    }
}
