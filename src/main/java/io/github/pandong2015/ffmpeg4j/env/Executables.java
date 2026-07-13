package io.github.pandong2015.ffmpeg4j.env;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 可执行文件解析工具：把一个「命令名或路径」解析为磁盘上真实、可执行的 {@link Path}。
 *
 * <p>支持三种输入：绝对路径、相对/含分隔符的路径、以及裸命令名（走 {@code PATH} 搜索）。
 * Windows 下会依据 {@code PATHEXT} 追加 {@code .exe}/{@code .cmd} 等扩展名。
 */
final class Executables {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private Executables() {
    }

    /**
     * 解析命令名或路径为可执行文件。
     *
     * @param nameOrPath 裸命令名（如 {@code "ffmpeg"}）或路径（绝对/相对）
     * @return 解析到的可执行文件路径；无法解析时为 {@link Optional#empty()}
     */
    static Optional<Path> resolve(String nameOrPath) {
        if (nameOrPath == null || nameOrPath.isBlank()) {
            return Optional.empty();
        }
        String cmd = nameOrPath.trim();
        boolean looksLikePath =
                cmd.contains("/") || cmd.contains(File.separator) || Path.of(cmd).isAbsolute();

        if (looksLikePath) {
            return firstExecutable(candidatesFor(Path.of(cmd)));
        }

        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return Optional.empty();
        }
        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Optional<Path> hit = firstExecutable(candidatesFor(Path.of(dir, cmd)));
            if (hit.isPresent()) {
                return hit;
            }
        }
        return Optional.empty();
    }

    /** 为一个基准路径生成候选路径（Windows 下追加可执行扩展名）。 */
    private static List<Path> candidatesFor(Path base) {
        List<Path> out = new ArrayList<>();
        out.add(base);
        if (WINDOWS && !hasExecutableExtension(base)) {
            String ext = System.getenv("PATHEXT");
            String pathext = (ext == null || ext.isBlank()) ? ".COM;.EXE;.BAT;.CMD" : ext;
            for (String e : pathext.split(File.pathSeparator)) {
                if (!e.isBlank()) {
                    out.add(base.resolveSibling(base.getFileName().toString() + e.toLowerCase(Locale.ROOT)));
                }
            }
        }
        return out;
    }

    private static boolean hasExecutableExtension(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".exe") || name.endsWith(".com")
                || name.endsWith(".bat") || name.endsWith(".cmd");
    }

    private static Optional<Path> firstExecutable(List<Path> candidates) {
        for (Path p : candidates) {
            if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                return Optional.of(p.toAbsolutePath().normalize());
            }
        }
        return Optional.empty();
    }
}
