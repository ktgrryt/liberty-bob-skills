/*
 * liberty-feature-min の生成プログラム。アプリが使う API から見た「必要な feature の一覧」を作る。
 *
 * generate-features は、server.xml などに個別に書かれている feature を生成結果に含めない
 * （書かれている feature が本当に使われているのか区別できない）。また、versionless の feature が書かれていると失敗する。
 * そこで、構成ファイルのコピーを作り、コピーの <feature> と <platform> をすべてコメントアウトしてから、
 * コピーを対象に generate-features を実行する。元のファイルは読むだけで、変更しない（書き込むのは出力ディレクトリの中だけ）。
 *
 * コピーの作り方（MODE）:
 *   CONFIG_COPY  : Maven のとき。server.xml のあるディレクトリ（構成ディレクトリ）をコピーし、
 *                  -DconfigDirectory と -DserverXmlFile でコピーを指定して、元のプロジェクトでビルドする。
 *   PROJECT_COPY : Gradle（構成の場所をコマンドラインで変えられない）、pom.xml で構成の場所を指定している Maven
 *                  （pom.xml の指定が -D より優先される）、それ以外のコマンドのとき。
 *                  カレントディレクトリ（ビルドのルート）をコピーし、コピーの中でビルドする。
 *                  .git とビルドの出力（target/、build/、.gradle/）はコピーしない。
 * コピーの include は、コピーを指す絶対パスに書き換える（元のファイルを読まれないように）。
 *
 * プラグインが本当にコピーを使ったかを確かめるため、コピーの generated-features.xml を目印の内容にしておく。
 * generate-features は、成功すると（追加の feature が無くても）このファイルを書き直すので
 * （liberty-maven-plugin 3.7 以降と liberty-gradle-plugin 3.8.3 以降で確認）、書き直されていなければ結果を使わない。
 *
 * 使い方（プロジェクトのルートで実行する。Java 11 以上はソースファイルをそのまま実行できる）:
 *   java GenerateRequiredFeatures.java --server-xml <server.xml> --out <出力ディレクトリ> [--timeout-minutes N] -- <生成コマンド...>
 *
 * 例:
 *   java GenerateRequiredFeatures.java --server-xml src/main/liberty/config/server.xml --out target/liberty-feature-min \
 *       -- ./mvnw compile liberty:generate-features
 *
 * 生成コマンドは 1 つだけ渡し、sh -c などで複数のコマンドをまとめない。止めるときは子孫のプロセスまで止めるが、
 * 子孫を辿れるのは Java 9 以上だけなので、Java 8 ではビルドの本体が止まらずに残ることがある。
 *
 * 出力は KEY=VALUE 形式の行。ビルドの出力は <出力ディレクトリ>/build.log に保存する。
 * コピーは <出力ディレクトリ>/work に作り、終了時に削除する（強制終了で残った場合は、次の実行で削除する）。
 *
 * 終了コード: 0 = 生成した、1 = 生成に失敗した（ビルドの失敗）、
 *            2 = 生成しなかった・結果を使えない（引数の誤り、ファイルを読み書きできない、コピーが使われなかった、
 *                クラスを調べなかった）
 * Java 8 でもコンパイルできるように書いている。
 */
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class GenerateRequiredFeatures {

    private static final String USAGE =
            "Usage: GenerateRequiredFeatures --server-xml <server.xml> --out <dir> [--timeout-minutes N] -- <command...>";

    // バイト列をそのまま扱うため ISO-8859-1 で読み書きする（feature 名は ASCII なので置換に影響しない）
    private static final Charset RAW = StandardCharsets.ISO_8859_1;
    private static final String MARK = "liberty-feature-min:";
    private static final String GENERATED = "generated-features.xml";
    /** コピーの generated-features.xml に置く目印。generate-features が書き直せば、コピーを使ったと分かる。 */
    private static final byte[] PLACEHOLDER = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!-- " + MARK
            + " placeholder. generate-features rewrites this file when it uses this copy -->\n<server/>\n")
            .getBytes(StandardCharsets.UTF_8);
    /**
     * generate-features が調べるクラスを見つけられなかったときの警告の一部（Maven は "classes directory"、Gradle は "class files"）。
     * この警告が出たときは、追加の feature が無いのではなく、調べていないので結果を使わない。
     */
    private static final String NO_CLASSES_WARNING = "Liberty features will not be generated";
    /** 作業用のディレクトリがこのプログラムで作ったものであることを示すファイル（無ければ削除しない） */
    private static final String WORK_MARKER = ".liberty-feature-min-work";
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern FEATURE_OR_PLATFORM = Pattern.compile(
            "<feature>\\s*([^<\\s]+)\\s*</feature>|<platform>\\s*([^<\\s]+)\\s*</platform>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FEATURE = Pattern.compile("<feature>\\s*([^<\\s]+)\\s*</feature>", Pattern.CASE_INSENSITIVE);
    private static final Pattern INCLUDE = Pattern.compile(
            "<include\\b[^>]*?\\blocation\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE);
    /** pom.xml での構成の場所の指定（configFile は serverXmlFile の別名）。pom.xml の指定は -D より優先される。 */
    private static final Pattern PLUGIN_LOCATION = Pattern.compile("<(configDirectory|serverXmlFile|configFile)[\\s/>]");
    private static final Pattern PARENT = Pattern.compile("<parent>(.*?)</parent>", Pattern.DOTALL);
    private static final Pattern RELATIVE_PATH = Pattern.compile(
            "<relativePath\\s*/>|<relativePath>\\s*([^<]*?)\\s*</relativePath>");
    private static final int DEFAULT_TIMEOUT_MINUTES = 30;
    private static final int LOG_TAIL_LINES = 40;
    /** ビルドを止めたあと、プロセスが終わるのを待つ時間 */
    private static final int KILL_WAIT_SECONDS = 10;

    /** 作業用のディレクトリ。コピーはすべてこの中に作る。 */
    private static Path workDir;
    /** コピーする元（CONFIG_COPY は構成ディレクトリ、PROJECT_COPY はカレントディレクトリ）と、コピー先 */
    private static Path copyFrom;
    private static Path copyTo;
    private static volatile Process build;
    /** Ctrl+C などで止められた */
    private static volatile boolean interrupted;
    private static boolean resultPrinted;

    /** 構成ファイル 1 つ分の、元の内容と include。 */
    static final class ConfigFile {
        final byte[] bytes;
        final String text;
        final List<Include> includes = new ArrayList<Include>();

        ConfigFile(byte[] bytes) {
            this.bytes = bytes;
            this.text = new String(bytes, RAW);
        }
    }

    /** include の location の値の位置と、その解決先（ファイルかディレクトリ）。 */
    static final class Include {
        final int start;
        final int end;
        final Path target;

        Include(int start, int end, Path target) {
            this.start = start;
            this.end = end;
            this.target = target;
        }
    }

    public static void main(String[] args) {
        Path serverXml = null;
        Path out = null;
        int timeout = DEFAULT_TIMEOUT_MINUTES;
        List<String> command = new ArrayList<String>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--")) {
                command.addAll(Arrays.asList(args).subList(i + 1, args.length));
                break;
            }
            if (i + 1 >= args.length) {
                usageError("Missing value for " + a);
            }
            if (a.equals("--server-xml")) {
                serverXml = Paths.get(args[++i]);
            } else if (a.equals("--out")) {
                out = Paths.get(args[++i]);
            } else if (a.equals("--timeout-minutes")) {
                try {
                    timeout = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    usageError("--timeout-minutes must be an integer: " + args[i]);
                }
                if (timeout < 1) {
                    usageError("--timeout-minutes must be 1 or more: " + args[i]);
                }
            } else {
                usageError("Unknown option: " + a);
            }
        }
        if (serverXml == null || out == null || command.isEmpty()) {
            usageError(USAGE);
        }
        for (String arg : command) {
            if (arg.equals("clean") || arg.endsWith(":clean")) {
                // 出力ディレクトリ（target/ など）にあるコピーや結果が消えるため
                error("CLEAN_NOT_ALLOWED", "Do not include 'clean' in the command");
            }
        }

        Path projectDir = Paths.get("").toAbsolutePath().normalize();
        serverXml = underProject(serverXml.toAbsolutePath().normalize(), projectDir);
        out = underProject(out.toAbsolutePath().normalize(), projectDir);
        if (!Files.isRegularFile(serverXml)) {
            error("SERVER_XML_NOT_FOUND", "server.xml not found: " + serverXml);
        }
        Path configDir = serverXml.getParent();
        if (out.startsWith(configDir)) {
            error("BAD_OUT_DIR", "--out must not be inside the server config directory: " + configDir);
        }
        if (projectDir.startsWith(out)) {
            error("BAD_OUT_DIR", "--out must not be the current directory or its parent: " + out);
        }
        workDir = out.resolve("work");
        if (Files.exists(workDir) && !Files.exists(workDir.resolve(WORK_MARKER))) {
            error("BAD_OUT_DIR", workDir + " exists but was not created by this program (move or delete it first)");
        }
        Path generated = configDir.resolve("configDropins").resolve("overrides").resolve(GENERATED);

        System.out.println("SERVER_XML=" + serverXml);
        System.out.println("OUT_DIR=" + out);

        String tool = buildTool(command.get(0));
        boolean projectCopy = true;
        Map<Path, ConfigFile> files = null;
        byte[] generatedBefore = null;
        // コピーに書く内容（まだ書き込まない）
        Map<Path, byte[]> copies = new LinkedHashMap<Path, byte[]>();
        try {
            files = collectConfigFiles(serverXml, configDir, generated);
            for (Map.Entry<Path, ConfigFile> e : files.entrySet()) {
                System.out.println("CONFIG_FILE=" + e.getKey());
                if (e.getValue().text.contains(MARK)) {
                    Path backup = out.resolve("backup");
                    error("LEFTOVER_MARK", "Found '" + MARK + "' in " + e.getKey()
                            + ". A temporary change by an earlier version of this program may remain."
                            + " Restore the file first (for example with git)"
                            + (Files.exists(backup.resolve("manifest.txt")) ? "; the original files are in " + backup : ""));
                }
            }

            String reason;
            if (tool.equals("maven")) {
                String setting = pluginLocationSetting(projectDir, out);
                projectCopy = setting != null;
                reason = projectCopy ? setting + " (it takes precedence over -D)"
                        : "Maven (the copy is passed with -DconfigDirectory and -DserverXmlFile)";
            } else if (tool.equals("gradle")) {
                reason = "Gradle (the config directory cannot be set from the command line)";
            } else {
                reason = "not a Maven or Gradle command";
            }
            System.out.println("MODE=" + (projectCopy ? "PROJECT_COPY" : "CONFIG_COPY"));
            System.out.println("MODE_REASON=" + reason);
            if (projectCopy) {
                checkProjectCopy(projectDir, configDir, tool, command);
            }
            copyFrom = projectCopy ? projectDir : configDir;
            Path name = projectDir.getFileName();
            // Gradle はディレクトリ名をプロジェクト名にするので、元と同じ名前のディレクトリにコピーする
            copyTo = projectCopy ? workDir.resolve("project").resolve(name == null ? "root" : name.toString())
                    : workDir.resolve("config");

            boolean declared = false;
            for (Map.Entry<Path, ConfigFile> e : files.entrySet()) {
                List<String> names = new ArrayList<String>();
                String text = commentOutFeatures(rewriteIncludes(e.getValue()), names);
                for (String n : names) {
                    System.out.println("DECLARED=" + e.getKey() + ": " + n);
                    declared = true;
                }
                copies.put(copyOf(e.getKey()), text.getBytes(RAW));
            }
            if (!declared) {
                System.out.println("DECLARED=NONE");
            }
            Path serverEnv = configDir.resolve("server.env");
            if (Files.isRegularFile(serverEnv)
                    && new String(Files.readAllBytes(serverEnv), RAW).contains("PREFERRED_PLATFORM_VERSIONS")) {
                System.out.println("NOTE=server.env sets PREFERRED_PLATFORM_VERSIONS (not changed by this program)");
            }

            Files.createDirectories(out);
            Files.deleteIfExists(out.resolve("generated-features.before.xml"));
            Files.deleteIfExists(out.resolve("generated-features.required.xml"));
            if (Files.exists(generated)) {
                generatedBefore = Files.readAllBytes(generated);
                Files.write(out.resolve("generated-features.before.xml"), generatedBefore);
                System.out.println("GENERATED_BEFORE=" + out.resolve("generated-features.before.xml"));
                for (String feature : features(new String(generatedBefore, RAW))) {
                    System.out.println("GENERATED_BEFORE_FEATURE=" + feature);
                }
            } else {
                System.out.println("GENERATED_BEFORE=NONE");
            }
        } catch (IOException e) {
            error("IO_ERROR", e.toString());
        }

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                // ビルドを起動している途中に割り込まないように、runBuild と同じロックを取る
                synchronized (GenerateRequiredFeatures.class) {
                    interrupted = true;
                    Process p = build;
                    if (p != null) {
                        destroyBuild(p);
                    }
                }
                deleteWork();
                // main はこの後に JVM が止まって RESULT を出力できないことがあるので、ここで出力する
                // （main が先に出力していれば何もしない）
                printResult("INTERRUPTED", null, null);
            }
        }));

        Path copyGenerated = copyOf(generated);
        try {
            deleteWork();
            Files.createDirectories(workDir);
            Files.write(workDir.resolve(WORK_MARKER), new byte[0]);
            int count = copyTree(copyFrom, copyTo, out);
            for (Map.Entry<Path, byte[]> e : copies.entrySet()) {
                writeCopy(e.getKey(), e.getValue());
            }
            writeCopy(copyGenerated, PLACEHOLDER);
            if (projectCopy && tool.equals("gradle") && !hasGradleSettings(projectDir)) {
                // 無いと、Gradle はコピーより上のディレクトリ（元のプロジェクトなど）の settings を探す
                writeCopy(copyTo.resolve("settings.gradle"), new byte[0]);
            }
            System.out.println("COPY_DIR=" + copyTo);
            System.out.println("COPIED_FILES=" + count);
        } catch (IOException e) {
            error("IO_ERROR", e.toString());
        }

        List<String> run = new ArrayList<String>(command);
        String executable = command.get(0);
        if (projectCopy && (executable.contains("/") || executable.contains(File.separator))
                && !Paths.get(executable).isAbsolute()) {
            // Windows では相対パスのコマンドが元のプロジェクトから探されるので、コピーの中のパスにする
            run.set(0, copyTo.resolve(executable).normalize().toString());
        }
        if (tool.equals("maven")) {
            if (!projectCopy) {
                run.add("-DconfigDirectory=" + copyTo);
                run.add("-DserverXmlFile=" + copyOf(serverXml));
            }
            // liberty-maven-plugin の次の版（main ブランチ）は、既定で target/ のサーバーの構成を読み、そこに書く。
            // 構成ディレクトリ（コピー）を対象にさせる。この指定を知らない版では無視される。
            run.add("-DgenerateToSrc=true");
        }
        long started = System.currentTimeMillis();
        Path log = out.resolve("build.log");
        int exit = runBuild(run, projectCopy ? copyTo : projectDir, log, timeout);
        if (interrupted) {
            // RESULT は終了処理が出力する
            return;
        }

        String ioError = null;
        boolean generatedInCopy = false;
        boolean noClasses = false;
        try {
            Set<Path> originals = new HashSet<Path>(files.keySet());
            originals.add(generated);
            for (Path f : changedOriginals(files, generated, generatedBefore)) {
                // このプログラムは元のファイルに書かないので、ビルドかほかのプログラムが書いた
                System.out.println("CHANGED_ORIGINAL=" + f);
            }
            if (exit != 0) {
                // 失敗したビルドが途中まで書いたファイルは信用できないので使わない
                System.out.println("REQUIRED=NONE");
            } else {
                printGeneratedFiles(projectCopy ? copyTo : projectDir, copyGenerated, started, originals);
                byte[] result = Files.isRegularFile(copyGenerated) ? Files.readAllBytes(copyGenerated) : null;
                generatedInCopy = result != null && !Arrays.equals(result, PLACEHOLDER);
                noClasses = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).contains(NO_CLASSES_WARNING);
                if (generatedInCopy && !noClasses) {
                    Path saved = out.resolve("generated-features.required.xml");
                    Files.write(saved, result);
                    System.out.println("REQUIRED=" + saved);
                    // 1 つも無ければ、API から必要と判定された feature が無い
                    for (String feature : features(new String(result, RAW))) {
                        System.out.println("REQUIRED_FEATURE=" + feature);
                    }
                } else {
                    System.out.println("REQUIRED=NONE");
                }
            }
        } catch (IOException e) {
            ioError = e.toString();
        }
        deleteWork();

        if (ioError != null) {
            error("IO_ERROR", ioError);
        }
        if (exit != 0) {
            finish("BUILD_FAILED", null, null, 1);
        }
        if (noClasses) {
            error("NO_CLASSES_SCANNED", "generate-features found no class files to scan (see " + log
                    + "). The Gradle plugin scans only the classes of the project that applies the Liberty plugin");
        }
        if (!generatedInCopy) {
            error("NOT_GENERATED_IN_COPY", "generate-features did not rewrite " + copyGenerated
                    + ", so it did not use the copy. See GENERATED_FILE, CHANGED_ORIGINAL and " + out.resolve("build.log"));
        }
        finish("OK", null, null, 0);
    }

    /** 実行するコマンドから、ビルドツールを判定する（maven / gradle / other）。 */
    static String buildTool(String executable) {
        String name = executable.replaceAll(".*[/\\\\]", "").toLowerCase(Locale.ROOT).replaceAll("\\.(cmd|bat|exe)$", "");
        if (name.equals("mvn") || name.equals("mvnw") || name.equals("mvnd")) {
            return "maven";
        }
        if (name.equals("gradle") || name.equals("gradlew")) {
            return "gradle";
        }
        return "other";
    }

    /** pom.xml で構成の場所を指定していれば、その説明を返す。無ければ null。 */
    static String pluginLocationSetting(final Path projectDir, final Path out) throws IOException {
        final String[] found = {null};
        Files.walkFileTree(projectDir, new SimpleFileVisitor<Path>() {
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return !dir.equals(projectDir) && skipDir(dir, out) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.getFileName().toString().equals("pom.xml")) {
                    String pom = COMMENT.matcher(new String(Files.readAllBytes(file), RAW)).replaceAll("");
                    Matcher m = PLUGIN_LOCATION.matcher(pom);
                    if (m.find()) {
                        found[0] = file + " sets <" + m.group(1) + ">";
                        return FileVisitResult.TERMINATE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return found[0];
    }

    /** PROJECT_COPY で、コピーの中のビルドが元のプロジェクトと同じものを対象にするかを確かめる。 */
    static void checkProjectCopy(Path projectDir, Path configDir, String tool, List<String> command) throws IOException {
        if (!configDir.startsWith(projectDir)) {
            error("SERVER_XML_OUTSIDE_PROJECT",
                    "The server config directory must be inside the current directory (run this program in the project root): "
                    + configDir);
        }
        // 同じディレクトリの別の書き方（シンボリックリンクを含むパス）も調べる
        Set<String> spellings = new HashSet<String>();
        spellings.add(projectDir.toString());
        spellings.add(projectDir.toRealPath().toString());
        String pwd = System.getenv("PWD");
        if (pwd != null && Files.isDirectory(Paths.get(pwd)) && Files.isSameFile(Paths.get(pwd), projectDir)) {
            spellings.add(Paths.get(pwd).normalize().toString());
        }
        for (String arg : command) {
            for (String dir : spellings) {
                if (arg.contains(dir + File.separator) || arg.endsWith(dir)) {
                    error("BAD_COMMAND", "Use relative paths in the command (it runs in a copy of the project): " + arg);
                }
            }
        }
        if (tool.equals("maven")) {
            Path parent = parentPomOutside(projectDir);
            if (parent != null) {
                error("NOT_BUILD_ROOT", "pom.xml has a parent outside the current directory: " + parent
                        + ". Run this program in the root of the build");
            }
        } else if (tool.equals("gradle") && !hasGradleSettings(projectDir)) {
            for (Path d = projectDir.getParent(); d != null; d = d.getParent()) {
                if (hasGradleSettings(d)) {
                    error("NOT_BUILD_ROOT", "The Gradle settings file is in " + d + ". Run this program in the root of the build");
                }
            }
        }
    }

    /** pom.xml の親（relativePath）がカレントディレクトリの外にあれば、そのパスを返す。 */
    static Path parentPomOutside(Path projectDir) throws IOException {
        Path pom = projectDir.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return null;
        }
        Matcher m = PARENT.matcher(COMMENT.matcher(new String(Files.readAllBytes(pom), RAW)).replaceAll(""));
        if (!m.find()) {
            return null;
        }
        Matcher r = RELATIVE_PATH.matcher(m.group(1));
        String relative = !r.find() ? "../pom.xml" : r.group(1) == null ? "" : r.group(1);
        if (relative.isEmpty()) {
            return null;
        }
        Path p = projectDir.resolve(relative).normalize();
        if (Files.isDirectory(p)) {
            p = p.resolve("pom.xml");
        }
        return !p.startsWith(projectDir) && Files.isRegularFile(p) ? p : null;
    }

    static boolean hasGradleSettings(Path dir) {
        return Files.exists(dir.resolve("settings.gradle")) || Files.exists(dir.resolve("settings.gradle.kts"));
    }

    /** コピーしないディレクトリ：.git、ビルドの出力（target/、build/、.gradle/）、出力ディレクトリ */
    static boolean skipDir(Path dir, Path out) {
        String name = String.valueOf(dir.getFileName());
        Path parent = dir.getParent();
        if (name.equals(".git") || isSame(dir, out)) {
            return true;
        }
        if (name.equals("target")) {
            return Files.exists(parent.resolve("pom.xml"));
        }
        if (name.equals("build") || name.equals(".gradle")) {
            return hasGradleSettings(parent) || Files.exists(parent.resolve("build.gradle"))
                    || Files.exists(parent.resolve("build.gradle.kts"));
        }
        return false;
    }

    static boolean isSame(Path a, Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (IOException e) {
            return false;
        }
    }

    /** p がカレントディレクトリの中を別の書き方（シンボリックリンクを含むパス）で指していれば、カレントディレクトリからのパスに直す。 */
    static Path underProject(Path p, Path projectDir) {
        if (p.startsWith(projectDir)) {
            return p;
        }
        try {
            Path existing = p;
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            if (existing != null) {
                Path real = existing.toRealPath().resolve(existing.relativize(p).toString());
                Path realProject = projectDir.toRealPath();
                if (real.startsWith(realProject)) {
                    return projectDir.resolve(realProject.relativize(real).toString());
                }
            }
        } catch (IOException e) {
            // 直せなければ、そのまま使う
        }
        return p;
    }

    /** server.xml、include 先（再帰的に）、configDropins の XML を集める。generated-features.xml は除く。 */
    static Map<Path, ConfigFile> collectConfigFiles(Path serverXml, Path configDir, Path generated) throws IOException {
        Map<Path, ConfigFile> result = new LinkedHashMap<Path, ConfigFile>();
        List<Path> todo = new ArrayList<Path>();
        todo.add(serverXml);
        for (String sub : new String[] {"defaults", "overrides"}) {
            todo.addAll(xmlFiles(configDir.resolve("configDropins").resolve(sub)));
        }
        while (!todo.isEmpty()) {
            Path f = todo.remove(0).toAbsolutePath().normalize();
            if (f.equals(generated) || result.containsKey(f)) {
                continue;
            }
            ConfigFile cf = new ConfigFile(Files.readAllBytes(f));
            result.put(f, cf);
            List<int[]> comments = comments(cf.text);
            Matcher m = INCLUDE.matcher(cf.text);
            while (m.find()) {
                if (insideComment(comments, m.start())) {
                    continue;
                }
                int group = m.group(1) != null ? 1 : 2;
                String location = m.group(group);
                if (location.startsWith("${server.config.dir}")) {
                    location = configDir + location.substring("${server.config.dir}".length());
                }
                if (location.contains("${") || location.contains("://")) {
                    // generate-features も変数や URL で指定した include 先は考慮しない
                    System.out.println("SKIPPED_INCLUDE=" + location + " (variable or URL)");
                    continue;
                }
                Path p = Paths.get(location);
                if (!p.isAbsolute()) {
                    p = Files.exists(f.getParent().resolve(p)) ? f.getParent().resolve(p) : configDir.resolve(p);
                }
                p = p.toAbsolutePath().normalize();
                if (Files.isDirectory(p)) {
                    todo.addAll(xmlFiles(p));
                } else if (Files.isRegularFile(p)) {
                    todo.add(p);
                } else {
                    System.out.println("SKIPPED_INCLUDE=" + location + " (not found)");
                    continue;
                }
                cf.includes.add(new Include(m.start(group), m.end(group), p));
            }
        }
        return result;
    }

    static List<Path> xmlFiles(Path dir) throws IOException {
        List<Path> files = new ArrayList<Path>();
        if (!Files.isDirectory(dir)) {
            return files;
        }
        DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.xml");
        try {
            for (Path p : stream) {
                files.add(p);
            }
        } finally {
            stream.close();
        }
        Collections.sort(files);
        return files;
    }

    /** 元のファイルのコピーの場所。コピーする範囲の外にあるもの（include 先）は work/external の下に、元の絶対パスの形で置く。 */
    static Path copyOf(Path p) {
        if (p.startsWith(copyFrom)) {
            return copyTo.resolve(copyFrom.relativize(p).toString());
        }
        Path external = workDir.resolve("external");
        String drive = p.getRoot().toString().replaceAll("[^A-Za-z0-9]", "");
        if (!drive.isEmpty()) {
            external = external.resolve(drive);
        }
        return external.resolve(p.getRoot().relativize(p).toString());
    }

    /** include の location を、コピーを指す絶対パスに書き換える。 */
    static String rewriteIncludes(ConfigFile cf) {
        StringBuilder sb = new StringBuilder();
        int last = 0;
        for (Include include : cf.includes) {
            sb.append(cf.text, last, include.start).append(attributeValue(copyOf(include.target)));
            last = include.end;
        }
        return sb.append(cf.text.substring(last)).toString();
    }

    /** XML の属性値として書く。元の内容と同じ ISO-8859-1 の文字列にするため、UTF-8 のバイト列を経由する。 */
    static String attributeValue(Path p) {
        String s = p.toString().replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;").replace("'", "&apos;");
        return new String(s.getBytes(StandardCharsets.UTF_8), RAW);
    }

    /** コメントの外にある <feature> と <platform> をすべてコメントアウトし、その名前を names に入れる。 */
    static String commentOutFeatures(String text, List<String> names) {
        List<int[]> comments = comments(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        Matcher m = FEATURE_OR_PLATFORM.matcher(text);
        while (m.find()) {
            if (insideComment(comments, m.start())) {
                continue;
            }
            names.add(m.group(1) != null ? m.group(1) : "platform " + m.group(2));
            sb.append(text, last, m.start()).append("<!-- ").append(MARK).append(' ').append(m.group()).append(" -->");
            last = m.end();
        }
        return sb.append(text.substring(last)).toString();
    }

    static List<int[]> comments(String text) {
        List<int[]> comments = new ArrayList<int[]>();
        Matcher c = COMMENT.matcher(text);
        while (c.find()) {
            comments.add(new int[] {c.start(), c.end()});
        }
        return comments;
    }

    static boolean insideComment(List<int[]> comments, int pos) {
        for (int[] r : comments) {
            if (pos >= r[0] && pos < r[1]) {
                return true;
            }
        }
        return false;
    }

    static List<String> features(String xml) {
        List<String> list = new ArrayList<String>();
        Matcher m = FEATURE.matcher(COMMENT.matcher(xml).replaceAll(""));
        while (m.find()) {
            list.add(m.group(1));
        }
        return list;
    }

    /** from を to にコピーし、コピーしたファイルの数を返す。シンボリックリンクはたどって中身をコピーする（コピーに書いても元に届かないように）。 */
    static int copyTree(final Path from, final Path to, final Path out) throws IOException {
        final int[] count = {0};
        Files.walkFileTree(from, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, new SimpleFileVisitor<Path>() {
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(from) && skipDir(dir, out)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                // .git がファイルのこともある（git worktree、submodule）
                if (attrs.isRegularFile() && !file.getFileName().toString().equals(".git")) {
                    Files.copy(file, to.resolve(from.relativize(file).toString()),
                            StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
                    count[0]++;
                }
                return FileVisitResult.CONTINUE;
            }

            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                if (exc instanceof FileSystemLoopException) {
                    System.out.println("NOTE=skipped a symbolic link loop: " + file);
                    return FileVisitResult.CONTINUE;
                }
                throw exc;
            }
        });
        return count[0];
    }

    /** コピーに書く。書き込むのは作業用のディレクトリの中だけ（元のファイルに書かないことを、ここでも確かめる）。 */
    static void writeCopy(Path target, byte[] data) throws IOException {
        if (!target.normalize().startsWith(workDir)) {
            throw new IOException("Refusing to write outside " + workDir + ": " + target);
        }
        Files.createDirectories(target.getParent());
        if (Files.isSymbolicLink(target)) {
            Files.delete(target);
        }
        // 元が読み取り専用のファイルでも、コピーには書けるようにする
        target.toFile().setWritable(true);
        Files.write(target, data);
    }

    /** 作業用のディレクトリ（コピー）を削除する。このプログラムが作ったもの（印のファイルがある）だけを削除する。 */
    static void deleteWork() {
        if (workDir == null || !Files.exists(workDir.resolve(WORK_MARKER))) {
            return;
        }
        final Path marker = workDir.resolve(WORK_MARKER);
        try {
            // シンボリックリンクはたどらない（リンク自体を削除する）
            Files.walkFileTree(workDir, new SimpleFileVisitor<Path>() {
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!file.equals(marker)) {
                        if (attrs.isRegularFile()) {
                            // Windows では読み取り専用のファイルを削除できない
                            file.toFile().setWritable(true);
                        }
                        Files.delete(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc != null) {
                        throw exc;
                    }
                    if (dir.equals(workDir)) {
                        // 途中で失敗しても次の実行で削除できるように、印は最後に削除する
                        Files.delete(marker);
                    }
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            System.out.println("NOTE=could not delete " + workDir + " (it is deleted on the next run): " + e);
        }
    }

    /** 元のファイルのうち、実行の前と内容が変わったもの（generated-features.xml は、できた・消えたも含む）。 */
    static List<Path> changedOriginals(Map<Path, ConfigFile> files, Path generated, byte[] generatedBefore) throws IOException {
        List<Path> changed = new ArrayList<Path>();
        for (Map.Entry<Path, ConfigFile> e : files.entrySet()) {
            Path f = e.getKey();
            if (!Files.isRegularFile(f) || !Arrays.equals(e.getValue().bytes, Files.readAllBytes(f))) {
                changed.add(f);
            }
        }
        byte[] now = Files.isRegularFile(generated) ? Files.readAllBytes(generated) : null;
        if (!Arrays.equals(generatedBefore, now)) {
            changed.add(generated);
        }
        return changed;
    }

    /** ビルドの間に、コピーの目印の場所以外に書かれた generated-features.xml を示す（元のファイルは CHANGED_ORIGINAL で示す）。 */
    static void printGeneratedFiles(Path root, final Path expected, final long since, final Set<Path> originals)
            throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = String.valueOf(dir.getFileName());
                return name.equals(".git") || name.equals("node_modules") || isSame(dir, workDir)
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                // 更新日時の精度が 1〜2 秒のファイルシステムがあるので、少し前から数える
                if (file.getFileName().toString().equals(GENERATED) && !file.equals(expected) && !originals.contains(file)
                        && attrs.lastModifiedTime().toMillis() >= since - 2000) {
                    System.out.println("GENERATED_FILE=" + file);
                }
                return FileVisitResult.CONTINUE;
            }

            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static int runBuild(List<String> command, Path dir, Path log, int timeoutMinutes) {
        StringBuilder line = new StringBuilder();
        for (String s : command) {
            line.append(line.length() == 0 ? "" : " ").append(s);
        }
        System.out.println("BUILD_COMMAND=" + line);
        System.out.println("BUILD_DIR=" + dir);
        System.out.println("BUILD_LOG=" + log);
        int exit;
        try {
            Process p;
            // 終了処理と同じロックの中で起動する（起動してから build に入れるまでの間に止められると、ビルドが残るため）
            synchronized (GenerateRequiredFeatures.class) {
                if (interrupted) {
                    return -1;
                }
                p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
                        .redirectOutput(log.toFile()).start();
                build = p;
            }
            if (p.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                exit = p.exitValue();
            } else {
                destroyBuild(p);
                System.out.println("BUILD_TIMEOUT=" + timeoutMinutes + " minutes");
                exit = -1;
            }
        } catch (IOException e) {
            System.out.println("BUILD_START_FAILED=" + e.getMessage());
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exit = -1;
        } finally {
            build = null;
        }
        System.out.println("BUILD_EXIT=" + exit);
        if (exit != 0) {
            printLogTail(log);
        }
        return exit;
    }

    /**
     * ビルドのプロセスを、子孫のプロセスも含めて止め、終わるまで待つ（止まる前にコピーを削除すると、その後にファイルを書かれるため）。
     * sh -c などを経由すると、ビルドの本体は子孫のプロセスになる。
     * 子孫を辿る ProcessHandle は Java 9 以上なので、Java 8 でもコンパイルできるようにリフレクションで呼ぶ。
     */
    static void destroyBuild(Process p) {
        List<Object> descendants = new ArrayList<Object>();
        Method destroy = null;
        Method alive = null;
        try {
            Class<?> handle = Class.forName("java.lang.ProcessHandle");
            destroy = handle.getMethod("destroyForcibly");
            alive = handle.getMethod("isAlive");
            // 親を止めると子孫を辿れなくなるので、先に集める
            Collections.addAll(descendants, ((Stream<?>) Process.class.getMethod("descendants").invoke(p)).toArray());
        } catch (Exception e) {
            System.out.println("NOTE=could not find child processes of the build (Java 9 or later is required): " + e);
        }
        // 親を先に止める（子孫を止めたときに、親が次のコマンドを起動しないように）
        p.destroyForcibly();
        for (Object h : descendants) {
            invokeQuietly(destroy, h);
        }
        try {
            p.waitFor(KILL_WAIT_SECONDS, TimeUnit.SECONDS);
            long deadline = System.currentTimeMillis() + KILL_WAIT_SECONDS * 1000L;
            for (Object h : descendants) {
                while (Boolean.TRUE.equals(invokeQuietly(alive, h)) && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static Object invokeQuietly(Method method, Object target) {
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(target);
        } catch (Exception e) {
            return null;
        }
    }

    static void printLogTail(Path log) {
        try {
            String[] lines = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).split("\r?\n");
            for (int i = Math.max(0, lines.length - LOG_TAIL_LINES); i < lines.length; i++) {
                System.out.println("BUILD_LOG_TAIL=" + lines[i]);
            }
        } catch (IOException e) {
            System.out.println("BUILD_LOG_TAIL=(could not read log: " + e.getMessage() + ")");
        }
    }

    /** RESULT の行を 1 回だけ出力する（main と終了処理の両方から呼ばれる）。 */
    static synchronized void printResult(String result, String category, String message) {
        if (resultPrinted) {
            return;
        }
        resultPrinted = true;
        if (category != null) {
            System.out.println("CATEGORY=" + category);
            System.out.println("MESSAGE=" + message);
        }
        System.out.println("RESULT=" + result);
    }

    /** RESULT を出力して終了する。止められた後なら、RESULT は終了処理が出力している。 */
    static void finish(String result, String category, String message, int exitCode) {
        printResult(result, category, message);
        // ロックを持ったまま呼ばない（終了処理が同じロックを待つため）
        System.exit(exitCode);
    }

    static void error(String category, String message) {
        finish("ERROR", category, message, 2);
    }

    static void usageError(String message) {
        System.err.println(message);
        System.exit(2);
    }
}
