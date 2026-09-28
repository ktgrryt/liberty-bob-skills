/*
 * liberty-datasource-create の接続チェック用プログラム。
 *
 * 使い方（Java 11 以上はソースファイルをそのまま実行できる）:
 *   java -cp "<ドライバー JAR のディレクトリ>/*" JdbcPing.java [--env-file <server.env>] <jdbcUrl> <user> <パスワードの環境変数名> [タイムアウト秒]
 *
 * パスワードは引数では受け取らず、指定された名前の環境変数から読む
 * （コマンドの承認画面やプロセス一覧にパスワードが表示されないようにするため）。
 * 環境変数が無ければ、--env-file で指定したファイル（Liberty の server.env の形式）から読む。
 * server.env をシェルで読み込むと、値の $ や & などが解釈されて別の値になるので、このプログラムで KEY=VALUE をそのまま読む。
 * 出力は KEY=VALUE 形式の行だけで、パスワードは表示しない。
 *
 * 終了コード: 0 = 接続成功、1 = 接続失敗、2 = 引数や環境変数の誤り
 * Java 8 でもコンパイルできるように書いている。
 */
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SSLException;

public class JdbcPing {

    private static final String USAGE =
            "Usage: JdbcPing [--env-file <server.env>] <jdbcUrl> <user> <passwordEnvVar> [timeoutSeconds]";
    private static final int DEFAULT_TIMEOUT_SECONDS = 10;
    private static final int MAX_CAUSE_DEPTH = 10;

    public static void main(String[] args) {
        List<String> rest = new ArrayList<String>(Arrays.asList(args));
        Path envFile = null;
        if (rest.size() >= 2 && rest.get(0).equals("--env-file")) {
            envFile = Paths.get(rest.get(1));
            rest = rest.subList(2, rest.size());
        }
        if (rest.size() < 3 || rest.size() > 4) {
            usageError(USAGE);
        }
        String url = rest.get(0);
        String user = rest.get(1);
        String passwordEnv = rest.get(2);
        int timeout = DEFAULT_TIMEOUT_SECONDS;
        if (rest.size() == 4) {
            try {
                timeout = Integer.parseInt(rest.get(3));
            } catch (NumberFormatException e) {
                usageError("timeoutSeconds must be an integer: " + rest.get(3));
            }
        }

        String password = System.getenv(passwordEnv);
        String source = "environment";
        if ((password == null || password.isEmpty()) && envFile != null) {
            password = readEnvFile(envFile, passwordEnv);
            source = envFile.toString();
        }
        if (password == null || password.isEmpty()) {
            System.out.println("RESULT=ERROR");
            System.out.println("CATEGORY=PASSWORD_ENV_NOT_SET");
            System.out.println("MESSAGE=Environment variable " + passwordEnv + " is not set"
                    + (envFile == null ? "" : " and is not defined in " + envFile));
            System.exit(2);
        }
        System.out.println("PASSWORD_SOURCE=" + source);

        DriverManager.setLoginTimeout(timeout);
        try (Connection c = DriverManager.getConnection(url, user, password)) {
            DatabaseMetaData m = c.getMetaData();
            System.out.println("RESULT=OK");
            System.out.println("PRODUCT=" + oneLine(m.getDatabaseProductName() + " " + m.getDatabaseProductVersion(), password));
            System.out.println("DRIVER=" + oneLine(m.getDriverName() + " " + m.getDriverVersion(), password));
            System.exit(0);
        } catch (Exception e) {
            // ドライバーによっては SQLException 以外の実行時例外を投げるので、まとめて受ける
            System.out.println("RESULT=FAIL");
            System.out.println("CATEGORY=" + classify(e));
            System.out.println("EXCEPTION=" + e.getClass().getName());
            if (e instanceof SQLException) {
                SQLException s = (SQLException) e;
                if (s.getSQLState() != null) {
                    System.out.println("SQLSTATE=" + s.getSQLState());
                }
                System.out.println("ERROR_CODE=" + s.getErrorCode());
            }
            System.out.println("MESSAGE=" + oneLine(e.getMessage(), password));
            Throwable t = e.getCause();
            for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++) {
                System.out.println("CAUSE=" + t.getClass().getName() + ": " + oneLine(t.getMessage(), password));
                t = next(t);
            }
            System.exit(1);
        }
    }

    /** 失敗の原因を分類する。上から順に判定し、最初に当てはまったものを返す。 */
    static String classify(Exception e) {
        if (String.valueOf(e.getMessage()).contains("No suitable driver")) {
            return "DRIVER";
        }
        if (hasCause(e, UnknownHostException.class)) {
            return "DNS";
        }
        if (hasCause(e, SSLException.class) || causeMessageContains(e, "PKIX")) {
            return "SSL";
        }
        if (hasCause(e, ConnectException.class) || hasCause(e, SocketTimeoutException.class)
                || hasCause(e, NoRouteToHostException.class)) {
            return "NETWORK";
        }
        if (e instanceof SQLException) {
            SQLException s = (SQLException) e;
            String state = s.getSQLState() == null ? "" : s.getSQLState();
            int code = s.getErrorCode();
            // SQLSTATE 28xxx: 認証エラー（標準）
            // 1045: MySQL / MariaDB、18456: SQL Server、1017: Oracle（ORA-01017）
            if (state.startsWith("28") || code == 1045 || code == 18456 || code == 1017) {
                return "AUTH";
            }
            // 3D000: PostgreSQL、1049: MySQL / MariaDB、4060: SQL Server、12514: Oracle（ORA-12514）
            if (state.equals("3D000") || code == 1049 || code == 4060 || code == 12514) {
                return "DATABASE";
            }
            // SQLSTATE 08xxx: 接続エラー（標準）
            if (state.startsWith("08")) {
                return "NETWORK";
            }
        }
        return "UNKNOWN";
    }

    static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (type.isInstance(t)) {
                return true;
            }
            t = next(t);
        }
        return false;
    }

    static boolean causeMessageContains(Throwable e, String text) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (String.valueOf(t.getMessage()).contains(text)) {
                return true;
            }
            t = next(t);
        }
        return false;
    }

    static Throwable next(Throwable t) {
        Throwable cause = t.getCause();
        return cause == t ? null : cause;
    }

    /**
     * server.env の形式のファイルから、name の値を読む。値は = の後ろをそのまま使う（引用符や $ を解釈しない）。
     * # で始まる行は無視し、同じ名前が複数あれば後のものを使う。
     */
    static String readEnvFile(Path file, String name) {
        List<String> lines = null;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("RESULT=ERROR");
            System.out.println("CATEGORY=ENV_FILE_NOT_READABLE");
            System.out.println("MESSAGE=Could not read " + file + ": " + e);
            System.exit(2);
        }
        String value = null;
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (line.trim().startsWith("#") || eq < 0) {
                continue;
            }
            if (line.substring(0, eq).trim().equals(name)) {
                value = line.substring(eq + 1);
            }
        }
        if (value != null && value.length() >= 2
                && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
            System.out.println("NOTE=The value of " + name + " in " + file + " is quoted. The quotes are used as part of the value");
        }
        return value;
    }

    /** 1 行にまとめ、万一パスワードが含まれていれば伏せる。 */
    static String oneLine(String s, String password) {
        if (s == null) {
            return "";
        }
        if (password != null && !password.isEmpty()) {
            s = s.replace(password, "****");
        }
        return s.replace("\r", " ").replace("\n", " ");
    }

    static void usageError(String message) {
        System.err.println(message);
        System.exit(2);
    }
}
