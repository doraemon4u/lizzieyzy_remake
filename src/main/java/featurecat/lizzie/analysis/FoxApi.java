package featurecat.lizzie.analysis;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/**
 * Direct HTTP client for the current FoxWQ (野狐围棋) kifu API.
 *
 * <p>Replaces the legacy black-box sub-process (FoxRequest.jar) that talked to the deprecated
 * Tencent mobile CGI (happyapp.huanle.qq.com). The current service lives at newframe.foxwq.com and
 * requires an account login (password sent MD5-hashed, same as the official client) to obtain a
 * session token. All endpoints are GET/POST JSON and return responses whose shape is compatible
 * with {@code KifuInfo} parsing in FoxKifuDownload (chesslist[] / chess fields).
 *
 * <p>Reverse-engineered from the maintained community implementation
 * (github.com/yiqiaoli/foxwq-sgf-dl), verified reachable in 2026.
 */
public class FoxApi {

  private static final String BASE = "https://newframe.foxwq.com";
  private static final String UA =
      "UnityPlayer/2022.1.16f1 (UnityWebRequest/1.0, libcurl/7.84.0-DEV)";
  private static final String DEVICE_ID_MD5 = "e7ab56438d7225217c9a417a87031fef";
  private static final int CLIENT_TYPE = 13;

  // session state
  private String token = "";
  private String session = "";
  private long uid = 0;

  public boolean isLoggedIn() {
    return token != null && !token.isEmpty() && session != null && !session.isEmpty();
  }

  /**
   * Inject manually captured credentials (token + session) obtained from the FoxWQ web/desktop
   * client (e.g. via browser devtools network tab). The FoxWQ API requires a `session` that cannot
   * be derived from the login API, so users must supply it for fetching chess lists.
   *
   * @param token auth token; empty keeps the token from login
   * @param session session id; cannot be derived, must be supplied
   * @param uid the login account uid; empty keeps the uid from login
   * @return true when we now have both token and session
   */
  public boolean setSessionCredentials(String token, String session, String uid) {
    if (token != null && !token.isEmpty()) this.token = token.trim();
    if (session != null && !session.isEmpty()) this.session = session.trim();
    if (uid != null && !uid.isEmpty()) {
      try {
        this.uid = Long.parseLong(uid.trim());
      } catch (NumberFormatException ignored) {
      }
    }
    return isLoggedIn();
  }

  /**
   * Login with account name/phone plus a pre-hashed (MD5) password. The official FoxWQ client
   * transmits the MD5 digest, so we never store or send plaintext.
   *
   * @return true if a session token was obtained
   */
  public boolean login(String userIdentifier, String passwordMd5) {
    try {
      JSONObject body = new JSONObject();
      body.put("device_id_md5", DEVICE_ID_MD5);
      body.put("client_type", CLIENT_TYPE);
      body.put("password", passwordMd5 == null ? "" : passwordMd5);
      body.put("user_identifier", userIdentifier == null ? "" : userIdentifier);

      JSONObject resp = post("/cgi/LoginByPassword", body);
      if (resp == null) return false;
      Object sessionObj = findField(resp, "session");
      if (sessionObj != null) session = String.valueOf(sessionObj);
      Object tokenObj = findField(resp, "token");
      if (tokenObj != null) token = String.valueOf(tokenObj);
      Object uidObj = findField(resp, "uid");
      if (uidObj != null) {
        try {
          uid = Long.parseLong(String.valueOf(uidObj));
        } catch (Exception ignored) {
        }
      }
      return !token.isEmpty();
    } catch (Exception e) {
      e.printStackTrace();
      return false;
    }
  }

  /** Query user info by username to resolve a UID. Returns -1 if not found. */
  public long queryUid(String username) {
    try {
      String url =
          BASE
              + "/cgi/QueryUserInfoPanel?srcuid="
              + uid
              + "&username="
              + urlEncode(username)
              + "&time_stamp="
              + System.currentTimeMillis() / 1000;
      JSONObject resp = get(url);
      if (resp == null) return -1;
      Object uidObj = findField(resp, "uid");
      if (uidObj != null) {
        try {
          return Long.parseLong(String.valueOf(uidObj));
        } catch (Exception ignored) {
        }
      }
      return -1;
    } catch (Exception e) {
      e.printStackTrace();
      return -1;
    }
  }

  /**
   * Fetch a page of the chess list for a user.
   *
   * @param dstUid the user whose games we want
   * @param lastChessId last chessid seen (for pagination); pass "0"/empty for the first page
   * @param fetchNum how many records to request
   * @return raw JSON text (chesslist[]), or null on error
   */
  public String fetchChessList(long dstUid, String lastChessId, int fetchNum) {
    try {
      String last = (lastChessId == null || lastChessId.isEmpty()) ? "0" : lastChessId;
      long time = System.currentTimeMillis() / 1000;
      StringBuilder url =
          new StringBuilder(BASE)
              .append("/chessbook/TXWQFetchChessList?type=1")
              .append("&fetchnum=")
              .append(fetchNum)
              .append("&dstuid=")
              .append(dstUid)
              .append("&srcuid=")
              .append(uid)
              .append("&time=")
              .append(time)
              .append("&token=")
              .append(urlEncode(token))
              .append("&session=")
              .append(urlEncode(session))
              .append("&lastCode=")
              .append(urlEncode(last));
      return getText(url.toString());
    } catch (Exception e) {
      e.printStackTrace();
      return null;
    }
  }

  /**
   * Fetch a single game's raw SGF wrapper (JSON with a "chess" field).
   *
   * @return raw JSON text, or null on error
   */
  public String fetchChess(String chessId) {
    try {
      long time = System.currentTimeMillis() / 1000;
      StringBuilder url =
          new StringBuilder(BASE)
              .append("/chessbook/TXWQFetchChess?chessid=")
              .append(urlEncode(chessId))
              .append("&trans=")
              .append(urlEncode(chessId))
              .append("&srcuid=")
              .append(uid)
              .append("&time=")
              .append(time)
              .append("&token=")
              .append(urlEncode(token))
              .append("&session=")
              .append(urlEncode(session));
      return getText(url.toString());
    } catch (Exception e) {
      e.printStackTrace();
      return null;
    }
  }

  // ---------------------------------------------------------------------
  // low-level helpers
  // ---------------------------------------------------------------------

  private JSONObject get(String url) throws Exception {
    String text = getText(url);
    return text == null ? null : new JSONObject(text);
  }

  private String getText(String url) throws Exception {
    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
    conn.setRequestMethod("GET");
    conn.setRequestProperty("User-Agent", UA);
    conn.setRequestProperty("X-Unity-Version", "2022.1.16f1");
    conn.setRequestProperty("referer", "http://www.qq.com");
    conn.setRequestProperty("Accept", "application/json");
    conn.setConnectTimeout(15000);
    conn.setReadTimeout(15000);
    int code = conn.getResponseCode();
    if (code != 200) return null;
    return readBody(conn);
  }

  private JSONObject post(String path, JSONObject body) throws Exception {
    HttpURLConnection conn = (HttpURLConnection) new URL(BASE + path).openConnection();
    conn.setRequestMethod("POST");
    conn.setRequestProperty("User-Agent", UA);
    conn.setRequestProperty("X-Unity-Version", "2022.1.16f1");
    conn.setRequestProperty("referer", "http://www.qq.com");
    conn.setRequestProperty("Content-Type", "application/json");
    conn.setDoOutput(true);
    conn.setConnectTimeout(15000);
    conn.setReadTimeout(15000);
    byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
    try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
      out.write(payload);
      out.flush();
    }
    int code = conn.getResponseCode();
    if (code != 200) return null;
    return new JSONObject(readBody(conn));
  }

  private static String readBody(HttpURLConnection conn) throws Exception {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader br =
        new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = br.readLine()) != null) sb.append(line).append('\n');
    }
    return sb.toString();
  }

  /**
   * Locate a field by name either at the top level or nested under common wrapper objects (data /
   * result / user) — the Fox API nests fields differently across endpoints, so we search
   * defensively.
   */
  private static Object findField(JSONObject root, String name) {
    if (root == null) return null;
    if (root.has(name)) return root.opt(name);
    for (String key : new String[] {"data", "result", "user", "Response"}) {
      JSONObject nested = root.optJSONObject(key);
      if (nested != null && nested.has(name)) return nested.opt(name);
    }
    return null;
  }

  /** Public MD5 helper so callers can pre-hash a typed password or reuse a stored hash. */
  public static String md5(String s) {
    try {
      MessageDigest md = MessageDigest.getInstance("MD5");
      byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte b : d) hex.append(String.format("%02x", b));
      return hex.toString();
    } catch (Exception e) {
      e.printStackTrace();
      return "";
    }
  }

  private static String urlEncode(String s) {
    try {
      return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
    } catch (Exception e) {
      return "";
    }
  }
}
