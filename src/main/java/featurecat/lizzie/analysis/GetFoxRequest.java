package featurecat.lizzie.analysis;

import featurecat.lizzie.gui.FoxKifuDownload;
import featurecat.lizzie.util.Utils;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.SwingUtilities;

/**
 * Command driver for the FoxWQ (野狐围棋) kifu downloader.
 *
 * <p>Originally this spawned a black-box sub-process (foxReq/FoxRequest.jar) that talked to the
 * deprecated Tencent mobile CGI, which no longer works (2026): parameters changed and the service
 * moved to newframe.foxwq.com behind an account login. This class keeps the legacy line-protocol
 * API used by {@link FoxKifuDownload}, but performs real HTTPS requests through {@link FoxApi}
 * asynchronously (never blocking the EDT) and feeds each raw JSON response back to {@link
 * FoxKifuDownload#receiveResult(String)} — so the UI/parsing layer stays unchanged.
 */
public class GetFoxRequest {

  private final FoxKifuDownload foxKifuDownload;
  private final ExecutorService executor;
  private final FoxApi api;
  /** The target user uid that a search resolves to; used by pagination. */
  private volatile long currentDstUid = 0;

  /** @return the uid of the user currently being browsed (0 if not resolved yet). */
  public long getCurrentDstUid() {
    return currentDstUid;
  }

  public GetFoxRequest(FoxKifuDownload foxKifuDownload) {
    this.foxKifuDownload = foxKifuDownload;
    this.api = new FoxApi();
    this.executor = Executors.newSingleThreadExecutor();
  }

  /**
   * Provide manually captured FoxWQ credentials. When a real session is available (obtained from
   * the web/desktop client), it is used directly; the login step is then skipped.
   */
  public void setManualCredentials(String token, String session, String uid) {
    if (session != null && !session.isEmpty()) {
      api.setSessionCredentials(token, session, uid);
    }
  }

  /**
   * Start a search on the background worker: log in, resolve the displayed name, then fetch the
   * first page. {@code passwordMd5} is the pre-hashed (MD5) password; empty string reuses the
   * stored hash.
   */
  /**
   * Start a search on the background worker: log in with {@code loginUser}, then resolve {
   *
   * @code targetUser} (the "unique keyword" whose uid we search) and fetch the first page of the
   *     target's games. The resolved uid is remembered so later {@code uid} commands paginate the
   *     same user.
   */
  public void startSearch(
      final String loginUser, final String passwordMd5, final String targetUser) {
    executor.execute(
        () -> {
          try {
            // If the user supplied a real session manually, skip login entirely.
            if (!api.isLoggedIn()) {
              if (!api.login(loginUser == null ? "" : loginUser, passwordMd5)) {
                deliverError("登录野狐失败，请检查账号或密码，并确保账号未被限制。");
                return;
              }
              if (!api.isLoggedIn()) {
                deliverError("登录成功但缺少 session。请从野狐网页登录后（F12→网络）复制 session 填入『野狐棋谱』窗口的 Session 框。");
                return;
              }
            }
            String query = (targetUser == null || targetUser.isEmpty()) ? loginUser : targetUser;
            long dstUid = api.queryUid(query);
            if (dstUid > 0) {
              currentDstUid = dstUid;
              String list = api.fetchChessList(dstUid, "0", 100);
              if (list != null) deliver(list);
              else deliverError("无法获取棋谱列表，请检查野狐账号或稍后再试。");
            } else {
              deliverError("找不到玩家：" + query);
            }
          } catch (Exception e) {
            e.printStackTrace();
          }
        });
  }

  /**
   * Dispatch a legacy line command asynchronously (used for pagination and single-game download):
   *
   * <ul>
   *   <li>{@code uid <dstuid> <lastChessid>} — fetch the next page for that uid.
   *   <li>{@code chessid <id>} — download a single game's SGF.
   * </ul>
   */
  public void sendCommand(String command) {
    if (command == null) return;
    String cmd = command.trim();
    if (cmd.isEmpty()) return;

    executor.execute(
        () -> {
          try {
            if (cmd.startsWith("uid")) {
              String[] parts = cmd.split("\\s+");
              long dstUid = 0;
              String last = "0";
              if (parts.length >= 2) {
                try {
                  dstUid = Long.parseLong(parts[1]);
                } catch (NumberFormatException ignored) {
                }
              }
              if (parts.length >= 3) last = parts[2];
              if (dstUid > 0) {
                String list = api.fetchChessList(dstUid, last, 100);
                if (list != null) deliver(list);
              }
            } else if (cmd.startsWith("chessid")) {
              String id = cmd.substring("chessid".length()).trim();
              if (!id.isEmpty()) {
                String chess = api.fetchChess(id);
                if (chess != null) deliver(chess);
                else deliverError("无法获取棋谱：" + id);
              }
            }
          } catch (Exception e) {
            e.printStackTrace();
          }
        });
  }

  private void deliver(String json) {
    SwingUtilities.invokeLater(() -> foxKifuDownload.receiveResult(json));
  }

  private void deliverError(String message) {
    SwingUtilities.invokeLater(() -> Utils.showMsg(message, foxKifuDownload));
  }

  /** Release the background worker. */
  public void shutdown() {
    if (executor != null) {
      try {
        executor.shutdownNow();
      } catch (Exception ignored) {
      }
    }
  }
}
