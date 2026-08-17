package featurecat.lizzie.analysis;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.util.Utils;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.json.JSONException;

/**
 * Remote-engine SSH transport. Uses the maintained JSch fork ({@code com.github.mwiede:jsch})
 * instead of the deprecated ganymed-ssh2, whose old ssh-rsa key exchange is rejected by modern
 * OpenSSH servers. Method names are kept identical to the previous ganymed version so callers
 * (Leelaz and friends) are unaffected.
 */
public class SSHController {
  private Session session;
  private ChannelExec channel;
  private RemoteConnect newConnect;
  private Leelaz owner;

  public SSHController(Leelaz owner, String ip, String port) {
    this.owner = owner;
    this.newConnect = new RemoteConnect();
    this.newConnect.setIp(ip);
    this.newConnect.setPort(port);
  }

  public Boolean login(String command, String userName, String password) {
    boolean flag = false;
    try {
      flag = openChannel(command, userName, null, Utils.doDecrypt(password));
      if (!flag) {
        owner.isLoaded = false;
        Utils.showMsg(Lizzie.resourceBundle.getString("SSHController.connectFailed"));
        LizzieFrame.openMoreEngineDialog();
        close();
      }
    } catch (Exception e) {
      owner.isLoaded = false;
      e.printStackTrace();
      String err = e.getLocalizedMessage();
      try {
        this.owner.tryToDignostic(
            String.valueOf(Lizzie.resourceBundle.getString("SSHController.engineFailed"))
                + ": "
                + ((err == null)
                    ? Lizzie.resourceBundle.getString("Leelaz.engineStartNoExceptionMessage")
                    : err),
            true);
        LizzieFrame.openMoreEngineDialog();
      } catch (JSONException e1) {
        e1.printStackTrace();
      }
    }
    return Boolean.valueOf(flag);
  }

  public Boolean loginByFileKey(String command, String userName, File keyFile) {
    boolean flag = false;
    try {
      flag = openChannel(command, userName, keyFile, null);
      if (!flag) {
        owner.isLoaded = false;
        Utils.showMsg(Lizzie.resourceBundle.getString("SSHController.connectFailed"));
        LizzieFrame.openMoreEngineDialog();
        close();
      }
    } catch (Exception e) {
      owner.isLoaded = false;
      e.printStackTrace();
      String err = e.getLocalizedMessage();
      try {
        this.owner.tryToDignostic(
            String.valueOf(Lizzie.resourceBundle.getString("SSHController.engineFailed"))
                + ": "
                + ((err == null)
                    ? Lizzie.resourceBundle.getString("Leelaz.engineStartNoExceptionMessage")
                    : err),
            true);
        LizzieFrame.openMoreEngineDialog();
      } catch (JSONException e1) {
        e1.printStackTrace();
      }
    }
    return Boolean.valueOf(flag);
  }

  /** Connect, authenticate and open an exec channel; returns true on success. */
  private boolean openChannel(String command, String userName, File keyFile, String password)
      throws Exception {
    JSch jsch = new JSch();
    if (keyFile != null) {
      jsch.addIdentity(keyFile.getAbsolutePath());
    }
    int port = newConnect.getPort();
    this.session = jsch.getSession(userName, newConnect.getIp(), port);
    this.session.setConfig("StrictHostKeyChecking", "no");
    this.session.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive");
    if (password != null) this.session.setPassword(password);
    this.session.connect(3000);
    this.channel = (ChannelExec) this.session.openChannel("exec");
    this.channel.setCommand(command);
    this.channel.connect(3000);
    return true;
  }

  public void close() {
    if (channel != null) channel.disconnect();
    if (session != null) session.disconnect();
  }

  public InputStream getStdout() {
    try {
      return channel == null ? null : channel.getInputStream();
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
  }

  public InputStream getSterr() {
    try {
      return channel == null ? null : channel.getErrStream();
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
  }

  public OutputStream getStdin() {
    try {
      return channel == null ? null : channel.getOutputStream();
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
  }
}
