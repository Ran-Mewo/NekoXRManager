package dev.lewds.ran.nekoxrmanager.installers;

/** Result of an APK install attempt. */
public final class InstallResult {

    public enum Kind { SUCCESS, FAILED, PENDING_USER_CONFIRM }

    public final Kind kind;
    public final String message;

    private InstallResult(Kind kind, String message) {
        this.kind = kind;
        this.message = message;
    }

    public static InstallResult success() {
        return new InstallResult(Kind.SUCCESS, "ok");
    }
    public static InstallResult failed(String reason) {
        return new InstallResult(Kind.FAILED, reason);
    }
    public static InstallResult pendingUserConfirm() {
        return new InstallResult(Kind.PENDING_USER_CONFIRM, "system installer dialog launched");
    }

    public boolean ok() { return kind == Kind.SUCCESS; }
}
