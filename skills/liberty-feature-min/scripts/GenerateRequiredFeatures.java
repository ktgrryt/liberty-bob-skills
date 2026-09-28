/*
 * liberty-feature-min の生成プログラム。アプリが使う API から見た「必要な feature の一覧」を作る。
 *
 * generate-features は、server.xml などに個別に書かれている feature を生成結果に含めない
 * （書かれている feature が本当に使われているのか区別できない）。また、versionless の feature が書かれていると失敗する。
 * そこで、server.xml・include 先・configDropins の <feature> と <platform> をすべて一時的にコメントアウトし、
 * 既存の generated-features.xml も一時的に退避してから generate-features を実行する。
 * 生成された generated-features.xml は出力ディレクトリに保存し、
 * 終了時（ビルド失敗や Ctrl+C を含む）には、変更したファイルと generated-features.xml を必ず元の内容に戻す。
 *
 * 使い方（Java 11 以上はソースファイルをそのまま実行できる）:
 *   java GenerateRequiredFeatures.java --server-xml <server.xml> --out <出力ディレクトリ> [--timeout-minutes N] -- <生成コマンド...>
 *   java GenerateRequiredFeatures.java --restore <出力ディレクトリ>
 *
 * 例:
 *   java GenerateRequiredFeatures.java --server-xml src/main/liberty/config/server.xml --out target/liberty-feature-min \
 *       -- ./mvnw compile liberty:generate-features
 *
 * 出力は KEY=VALUE 形式の行。ビルドの出力は <出力ディレクトリ>/build.log に保存する。
 * 強制終了などで元に戻せなかった場合は、<出力ディレクトリ>/backup に残したバックアップから --restore で戻せる。
 *
 * 終了コード: 0 = 生成して元に戻した、1 = 生成に失敗した（元には戻した）、
 *            2 = 引数の誤り・前回の実行が戻されていない、3 = 元に戻せなかった
 * Java 8 でもコンパイルできるように書いている。
 */
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GenerateRequiredFeatures {

    private static final String USAGE =
            "Usage: GenerateRequiredFeatures --server-xml <server.xml> --out <dir> [--timeout-minutes N] -- <command...>\n"
            + "       GenerateRequiredFeatures --restore <dir>";

    // バイト列をそのまま往復させるため ISO-8859-1 で読み書きする（feature 名は ASCII なので置換に影響しない）
    private static final Charset RAW = StandardCharsets.ISO_8859_1;
    private static final String MARK = "liberty-feature-min:";
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern FEATURE_OR_PLATFORM = Pattern.compile(
            "<feature>\\s*([^<\\s]+)\\s*</feature>|<platform>\\s*([^<\\s]+)\\s*</platform>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FEATURE = Pattern.compile("<feature>\\s*([^<\\s]+)\\s*</feature>", Pattern.CASE_INSENSITIVE);
    private static final Pattern INCLUDE = Pattern.compile(
            "<include\\b[^>]*?\\blocation\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE);
    private static final int DEFAULT_TIMEOUT_MINUTES = 30;
    private static final int LOG_TAIL_LINES = 40;

    /** 元に戻す内容。値が null のファイルは、もともと存在しなかったので削除する。 */
    private static final Map<Path, byte[]> originals = new LinkedHashMap<Path, byte[]>();
    /** もともと存在しなかったディレクトリ（深い順）。空なら削除する。 */
    private static final List<Path> missingDirs = new ArrayList<Path>();
    private static Path backupDir;
    private static volatile Process build;
    /** Ctrl+C などで止められた。元に戻した後のファイルを生成結果として扱わないために使う。 */
    private static volatile boolean interrupted;
    private static boolean restoreDone;
    private static boolean restoreOk;

    public static void main(String[] args) {
        if (args.length == 2 && args[0].equals("--restore")) {
            restoreFromDisk(Paths.get(args[1]).toAbsolutePath().normalize().resolve("backup"));
            return;
        }

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
            } else {
                usageError("Unknown option: " + a);
            }
        }
        if (serverXml == null || out == null || command.isEmpty()) {
            usageError(USAGE);
        }
        for (String arg : command) {
            if (arg.equals("clean") || arg.endsWith(":clean")) {
                // 出力ディレクトリ（target/ など）のバックアップや結果が消えるため
                error("CLEAN_NOT_ALLOWED", "Do not include 'clean' in the command");
            }
        }

        serverXml = serverXml.toAbsolutePath().normalize();
        out = out.toAbsolutePath().normalize();
        if (!Files.isRegularFile(serverXml)) {
            error("SERVER_XML_NOT_FOUND", "server.xml not found: " + serverXml);
        }
        Path configDir = serverXml.getParent();
        if (out.startsWith(configDir)) {
            error("BAD_OUT_DIR", "--out must not be inside the server config directory: " + configDir);
        }
        Path generated = configDir.resolve("configDropins").resolve("overrides").resolve("generated-features.xml");
        backupDir = out.resolve("backup");
        if (Files.exists(backupDir.resolve("manifest.txt"))) {
            System.out.println("RESTORE_COMMAND=java GenerateRequiredFeatures.java --restore " + out);
            error("PREVIOUS_RUN_NOT_RESTORED", "A previous run was not restored. Run --restore first");
        }

        System.out.println("SERVER_XML=" + serverXml);
        System.out.println("OUT_DIR=" + out);

        // 対象ファイルを集め、<feature> と <platform> をコメントアウトした内容を作る（まだ書き込まない）
        Map<Path, String> changes = new LinkedHashMap<Path, String>();
        try {
            for (Path f : collectConfigFiles(serverXml, configDir, generated)) {
                System.out.println("CONFIG_FILE=" + f);
                String text = new String(Files.readAllBytes(f), RAW);
                if (text.contains(MARK)) {
                    error("LEFTOVER_MARK", "Found '" + MARK + "' in " + f + " (a previous temporary change may remain)");
                }
                String changed = commentOutFeatures(text, f);
                if (!changed.equals(text)) {
                    changes.put(f, changed);
                }
            }
            if (changes.isEmpty()) {
                System.out.println("COMMENTED_OUT=NONE");
            }
            Path serverEnv = configDir.resolve("server.env");
            if (Files.isRegularFile(serverEnv)
                    && new String(Files.readAllBytes(serverEnv), RAW).contains("PREFERRED_PLATFORM_VERSIONS")) {
                System.out.println("NOTE=server.env sets PREFERRED_PLATFORM_VERSIONS (not changed by this program)");
            }

            // 変更する前に、元の内容をメモリとディスクの両方に保存する
            for (Path f : changes.keySet()) {
                originals.put(f, Files.readAllBytes(f));
            }
            originals.put(generated, Files.exists(generated) ? Files.readAllBytes(generated) : null);
            for (Path d = generated.getParent(); !d.equals(configDir) && !Files.exists(d); d = d.getParent()) {
                missingDirs.add(d);
            }
            Files.createDirectories(out);
            Files.deleteIfExists(out.resolve("generated-features.before.xml"));
            Files.deleteIfExists(out.resolve("generated-features.required.xml"));
            if (originals.get(generated) != null) {
                Files.write(out.resolve("generated-features.before.xml"), originals.get(generated));
                System.out.println("GENERATED_BEFORE=" + out.resolve("generated-features.before.xml"));
                for (String feature : features(new String(originals.get(generated), RAW))) {
                    System.out.println("GENERATED_BEFORE_FEATURE=" + feature);
                }
            } else {
                System.out.println("GENERATED_BEFORE=NONE");
            }
            writeBackups();
        } catch (IOException e) {
            error("IO_ERROR", e.toString());
        }

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                interrupted = true;
                Process p = build;
                if (p != null) {
                    p.destroyForcibly();
                }
                restore();
            }
        }));

        int exit = -1;
        try {
            for (Map.Entry<Path, String> e : changes.entrySet()) {
                Files.write(e.getKey(), e.getValue().getBytes(RAW));
            }
            Files.deleteIfExists(generated);
            exit = runBuild(command, out.resolve("build.log"), timeout);
            if (interrupted || exit != 0) {
                // 失敗したビルドが途中まで書いたファイルは信用できないので使わない
                System.out.println("REQUIRED=NONE");
            } else if (Files.exists(generated)) {
                Path result = out.resolve("generated-features.required.xml");
                Files.copy(generated, result, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("REQUIRED=" + result);
                for (String feature : features(new String(Files.readAllBytes(result), RAW))) {
                    System.out.println("REQUIRED_FEATURE=" + feature);
                }
            } else {
                // 生成が成功してもファイルが無い場合は、API から必要と判定された feature が無い
                System.out.println("REQUIRED=NONE");
            }
        } catch (IOException e) {
            System.out.println("IO_ERROR=" + e);
        } finally {
            restore();
        }

        if (!restoreOk) {
            System.out.println("RESTORE_COMMAND=java GenerateRequiredFeatures.java --restore " + out);
            System.out.println("RESULT=RESTORE_FAILED");
            System.exit(3);
        }
        if (interrupted) {
            // 終了処理の途中なので System.exit は呼ばない（呼ぶと終了処理が終わるまで待ち続ける）
            System.out.println("RESULT=INTERRUPTED");
            return;
        }
        System.out.println(exit == 0 ? "RESULT=OK" : "RESULT=BUILD_FAILED");
        System.exit(exit == 0 ? 0 : 1);
    }

    /** server.xml、include 先（再帰的に）、configDropins の XML を集める。generated-features.xml は除く。 */
    static Set<Path> collectConfigFiles(Path serverXml, Path configDir, Path generated) throws IOException {
        Set<Path> result = new LinkedHashSet<Path>();
        List<Path> todo = new ArrayList<Path>();
        todo.add(serverXml);
        for (String sub : new String[] {"defaults", "overrides"}) {
            todo.addAll(xmlFiles(configDir.resolve("configDropins").resolve(sub)));
        }
        while (!todo.isEmpty()) {
            Path f = todo.remove(0).toAbsolutePath().normalize();
            if (f.equals(generated) || !result.add(f)) {
                continue;
            }
            String text = COMMENT.matcher(new String(Files.readAllBytes(f), RAW)).replaceAll("");
            Matcher m = INCLUDE.matcher(text);
            while (m.find()) {
                String location = m.group(1) != null ? m.group(1) : m.group(2);
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
                if (Files.isDirectory(p)) {
                    todo.addAll(xmlFiles(p));
                } else if (Files.isRegularFile(p)) {
                    todo.add(p);
                } else {
                    System.out.println("SKIPPED_INCLUDE=" + location + " (not found)");
                }
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

    /** コメントの外にある <feature> と <platform> をすべてコメントアウトする。 */
    static String commentOutFeatures(String text, Path file) {
        List<int[]> comments = new ArrayList<int[]>();
        Matcher c = COMMENT.matcher(text);
        while (c.find()) {
            comments.add(new int[] {c.start(), c.end()});
        }
        StringBuilder sb = new StringBuilder();
        int last = 0;
        Matcher m = FEATURE_OR_PLATFORM.matcher(text);
        while (m.find()) {
            if (insideComment(comments, m.start())) {
                continue;
            }
            String name = m.group(1) != null ? m.group(1) : "platform " + m.group(2);
            System.out.println("COMMENTED_OUT=" + file + ": " + name);
            sb.append(text, last, m.start()).append("<!-- ").append(MARK).append(' ').append(m.group()).append(" -->");
            last = m.end();
        }
        return sb.append(text.substring(last)).toString();
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

    static int runBuild(List<String> command, Path log, int timeoutMinutes) {
        StringBuilder line = new StringBuilder();
        for (String s : command) {
            line.append(line.length() == 0 ? "" : " ").append(s);
        }
        System.out.println("BUILD_COMMAND=" + line);
        System.out.println("BUILD_LOG=" + log);
        int exit;
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            build = p;
            if (p.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                exit = p.exitValue();
            } else {
                p.destroyForcibly();
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

    /**
     * 強制終了に備えてディスクにもバックアップを書く。manifest.txt の各行は
     * F（バックアップから戻す）/ A（もともと無かったので削除する）/ D（もともと無かったディレクトリ。空なら削除する）。
     */
    static void writeBackups() throws IOException {
        Files.createDirectories(backupDir);
        StringBuilder manifest = new StringBuilder();
        int n = 0;
        for (Map.Entry<Path, byte[]> e : originals.entrySet()) {
            if (e.getValue() == null) {
                manifest.append("A\t-\t").append(e.getKey()).append('\n');
            } else {
                String name = "file" + (n++) + ".bak";
                Files.write(backupDir.resolve(name), e.getValue());
                manifest.append("F\t").append(name).append('\t').append(e.getKey()).append('\n');
            }
        }
        for (Path d : missingDirs) {
            manifest.append("D\t-\t").append(d).append('\n');
        }
        Path tmp = backupDir.resolve("manifest.tmp");
        Files.write(tmp, manifest.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, backupDir.resolve("manifest.txt"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        System.out.println("BACKUP_DIR=" + backupDir);
    }

    /** メモリに保存した内容で元に戻す。何度呼ばれても 1 回だけ実行する。 */
    static synchronized void restore() {
        if (restoreDone) {
            return;
        }
        restoreDone = true;
        boolean ok = true;
        for (Map.Entry<Path, byte[]> e : originals.entrySet()) {
            ok &= restoreFile(e.getKey(), e.getValue());
        }
        for (Path d : missingDirs) {
            removeIfEmpty(d);
        }
        if (ok) {
            deleteBackups(backupDir);
        }
        restoreOk = ok;
    }

    /** --restore：ディスクのバックアップから元に戻す。 */
    static void restoreFromDisk(Path dir) {
        Path manifest = dir.resolve("manifest.txt");
        if (!Files.exists(manifest)) {
            System.out.println("RESULT=NOTHING_TO_RESTORE");
            System.exit(0);
        }
        boolean ok = true;
        try {
            for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t", 3);
                if (parts.length != 3) {
                    continue;
                }
                Path target = Paths.get(parts[2]);
                if (parts[0].equals("F")) {
                    ok &= restoreFile(target, Files.readAllBytes(dir.resolve(parts[1])));
                } else if (parts[0].equals("A")) {
                    ok &= restoreFile(target, null);
                } else if (parts[0].equals("D")) {
                    removeIfEmpty(target);
                }
            }
        } catch (IOException e) {
            System.out.println("RESTORE_FAILED=" + manifest + ": " + e);
            ok = false;
        }
        if (ok) {
            deleteBackups(dir);
        }
        System.out.println(ok ? "RESULT=RESTORED" : "RESULT=RESTORE_FAILED");
        System.exit(ok ? 0 : 3);
    }

    /** 元の内容を書き戻し（content が null なら削除し）、一致することを確かめる。 */
    static boolean restoreFile(Path target, byte[] content) {
        try {
            if (content == null) {
                Files.deleteIfExists(target);
                if (Files.exists(target)) {
                    throw new IOException("could not delete");
                }
            } else {
                // 上書きで書くので、ファイルの権限はそのまま残る
                Files.write(target, content);
                if (!Arrays.equals(content, Files.readAllBytes(target))) {
                    throw new IOException("content differs after restore");
                }
            }
            System.out.println("RESTORED=" + target);
            return true;
        } catch (IOException e) {
            System.out.println("RESTORE_FAILED=" + target + ": " + e.getMessage());
            return false;
        }
    }

    static void removeIfEmpty(Path dir) {
        try {
            if (Files.isDirectory(dir)) {
                DirectoryStream<Path> s = Files.newDirectoryStream(dir);
                boolean empty;
                try {
                    empty = !s.iterator().hasNext();
                } finally {
                    s.close();
                }
                if (empty) {
                    Files.delete(dir);
                }
            }
        } catch (IOException e) {
            // 空のディレクトリが残るだけなので無視する
        }
    }

    static void deleteBackups(Path dir) {
        try {
            if (!Files.isDirectory(dir)) {
                return;
            }
            DirectoryStream<Path> s = Files.newDirectoryStream(dir);
            try {
                for (Path p : s) {
                    Files.delete(p);
                }
            } finally {
                s.close();
            }
            Files.delete(dir);
        } catch (IOException e) {
            System.out.println("NOTE=could not delete backup directory: " + e.getMessage());
        }
    }

    static void error(String category, String message) {
        System.out.println("RESULT=ERROR");
        System.out.println("CATEGORY=" + category);
        System.out.println("MESSAGE=" + message);
        System.exit(2);
    }

    static void usageError(String message) {
        System.err.println(message);
        System.exit(2);
    }
}
